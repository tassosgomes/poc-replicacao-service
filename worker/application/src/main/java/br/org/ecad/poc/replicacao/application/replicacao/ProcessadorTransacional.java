package br.org.ecad.poc.replicacao.application.replicacao;

import br.org.ecad.poc.replicacao.application.coordenacao.LeasePerdidoException;
import br.org.ecad.poc.replicacao.application.port.Cerca;
import br.org.ecad.poc.replicacao.application.port.Fluxo;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.EventoTabela;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.FimTransacao;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.Ignorado;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.Registro;
import br.org.ecad.poc.replicacao.application.port.MetricasLote;
import br.org.ecad.poc.replicacao.application.port.ReplicaSink;
import br.org.ecad.poc.replicacao.application.port.RepositorioOffsets;
import br.org.ecad.poc.replicacao.domain.replicacao.ChangeEvent;
import br.org.ecad.poc.replicacao.domain.replicacao.Coalescer;
import br.org.ecad.poc.replicacao.domain.replicacao.Esquema;
import br.org.ecad.poc.replicacao.domain.replicacao.Linha;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Applies origin transactions atomically across tables, in commit order, as the GoldenGate Replicat does.
 * Alternative to {@link ProcessadorParticao}; one active instance consumes every stream.
 *
 * <p><b>Model.</b> Table events carry the id of their origin transaction; the END marker of the
 * transaction topic (one partition, so a total order = commit order) says how many events the transaction
 * has, in total and per table. Events and marker arrive in any relative order (the END may come before the
 * events). A transaction is <em>complete</em> when every counted event is in memory. Transactions are
 * applied strictly in END order: the head of the queue blocks the ones behind it, so the destination
 * only ever shows states the origin had. Consecutive complete transactions share one destination commit
 * (like GROUPTRANSOPS), never splitting one.
 *
 * <p><b>Positions.</b> The offset of every stream is written in the same destination transaction as the
 * data and is, per stream, the offset of the oldest event read and not yet applied (or the next one to read
 * when nothing is pending). A restart (or another instance) therefore resumes exactly where the
 * destination is; redelivery is idempotent anyway (the sink guards by version).
 *
 * <p><b>Limits and anomalies.</b> At most {@code bufferMax} events wait in memory; beyond that the
 * processor fails loudly (a stuck head would otherwise grow without bound) and the caller restarts it from the
 * persisted positions. An event with no transaction (snapshot) is applied alone. An event whose transaction
 * shows no END within {@code orfa} is applied alone with a warning (it can only come from a cut at
 * start-up; atomicity is not promised for it). A transaction whose END arrived and whose events never complete
 * is <em>not</em> skipped: consistency over availability.
 *
 * <p>Single-threaded; the lease fence guards every commit.
 */
public final class ProcessadorTransacional {

    private static final System.Logger LOG = System.getLogger(ProcessadorTransacional.class.getName());
    private static final Comparator<EventoTabela> POR_ORDEM =
            Comparator.comparingLong(EventoTabela::ordemNaTransacao).thenComparingLong(EventoTabela::offset);

    /**
     * Limits of one instance.
     *
     * @param transacoesPorCommit most transactions grouped in one destination commit
     * @param eventosPorCommit soft cap of events per commit (a transaction larger than that goes alone)
     * @param bufferMax most events held in memory waiting for their transaction
     * @param orfa how long an event without END waits before it is applied alone
     */
    public record Parametros(int transacoesPorCommit, int eventosPorCommit, int bufferMax, Duration orfa) {}

    private static final class Transacao {
        final String id;
        final Instant primeiroEvento;
        final List<EventoTabela> eventos = new ArrayList<>();
        final Map<String, Integer> recebidosPorTabela = new HashMap<>();
        FimTransacao fim;

        Transacao(String id, Instant agora) {
            this.id = id;
            this.primeiroEvento = agora;
        }

        boolean completa() {
            return fim != null && eventos.size() == fim.eventos() && porTabelaConfere();
        }

        private boolean porTabelaConfere() {
            for (Map.Entry<String, Integer> esperado : fim.porTabela().entrySet()) {
                if (!esperado.getValue().equals(recebidosPorTabela.getOrDefault(esperado.getKey(), 0))) {
                    return false;
                }
            }
            return true;
        }
    }

    private final FonteTransacional fonte;
    private final ReplicaSink sink;
    private final RepositorioOffsets offsets;
    private final Cerca cerca;
    private final Clock clock;
    private final Parametros parametros;
    private MetricasLote metricas;

    private final Map<String, Transacao> abertas = new LinkedHashMap<>();
    private final Deque<String> fila = new ArrayDeque<>();
    private final List<EventoTabela> avulsos = new ArrayList<>();
    private final Map<Fluxo, NavigableSet<Long>> pendentes = new HashMap<>();
    private final Map<Fluxo, Long> lidos = new HashMap<>();
    private final Map<Fluxo, Long> gravados = new HashMap<>();
    private int emMemoria;
    private long orfas;

    public ProcessadorTransacional(
            FonteTransacional fonte,
            ReplicaSink sink,
            RepositorioOffsets offsets,
            Cerca cerca,
            Clock clock,
            Parametros parametros) {
        this.fonte = fonte;
        this.sink = sink;
        this.offsets = offsets;
        this.cerca = cerca;
        this.clock = clock;
        this.parametros = parametros;
    }

    public void instrumentar(MetricasLote metricas) {
        this.metricas = metricas;
    }

    /** Events applied alone because no END ever arrived. */
    public long orfas() {
        return orfas;
    }

    /** Events held in memory waiting for their transaction. */
    public int emMemoria() {
        return emMemoria;
    }

    /**
     * Positions the source from the destination. Streams without a persisted position start where the source
     * is configured to start, and that choice is made durable at once: a restart must not choose again.
     */
    public void iniciar() {
        Map<Fluxo, Long> salvas = offsets.carregar();
        Map<Fluxo, Long> posicoes = fonte.iniciar(salvas);
        gravados.putAll(salvas);
        lidos.putAll(posicoes);
        Map<Fluxo, Long> novas = new HashMap<>(posicoes);
        novas.entrySet().removeIf(e -> salvas.containsKey(e.getKey()));
        if (!novas.isEmpty()) {
            offsets.gravar(novas);
            confirmar("initial positions");
            gravados.putAll(novas);
        }
        try {
            fonte.informar(posicoes);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot publish positions to the broker (monitoring only)", e);
        }
    }

    /** One cycle: read, and apply every complete transaction at the head of the queue in one commit. */
    public ResultadoCiclo processarCiclo() {
        long inicio = System.nanoTime();
        List<Registro> registros = fonte.poll();
        for (Registro registro : registros) {
            receber(registro);
        }
        long fimPoll = System.nanoTime();
        if (emMemoria > parametros.bufferMax()) {
            throw new ReplicacaoException(
                    "transaction buffer exceeded: " + emMemoria + " events waiting (limit " + parametros.bufferMax()
                            + "); head=" + fila.peek(),
                    null);
        }
        List<Transacao> grupo = new ArrayList<>();
        int eventos = 0;
        for (String id : fila) {
            Transacao t = abertas.get(id);
            if (!t.completa()) {
                break;
            }
            if (!grupo.isEmpty()
                    && (grupo.size() >= parametros.transacoesPorCommit()
                            || eventos + t.eventos.size() > parametros.eventosPorCommit())) {
                break;
            }
            grupo.add(t);
            eventos += t.eventos.size();
        }
        List<Transacao> orfaos = vencidas();
        List<EventoTabela> sozinhos = new ArrayList<>(avulsos);
        orfaos.forEach(t -> sozinhos.addAll(t.eventos));
        if (grupo.isEmpty() && sozinhos.isEmpty()) {
            return new ResultadoCiclo(registros.size(), 0, 0);
        }
        return aplicar(grupo, orfaos, sozinhos, registros.size(), inicio, fimPoll);
    }

    private void receber(Registro registro) {
        lidos.put(registro.fluxo(), registro.offset() + 1);
        switch (registro) {
            case Ignorado ignorado -> {
                // nothing to apply; the read position advances
            }
            case FimTransacao fim -> {
                Transacao t = abertas.computeIfAbsent(fim.txId(), id -> new Transacao(id, clock.instant()));
                if (t.fim != null) {
                    LOG.log(System.Logger.Level.WARNING, "duplicate END of transaction " + fim.txId() + " ignored");
                    return;
                }
                t.fim = fim;
                fila.addLast(fim.txId());
                pendente(fim);
                if (t.eventos.size() > fim.eventos()) {
                    throw new ReplicacaoException(
                            "transaction " + fim.txId() + " has " + t.eventos.size() + " events, END says "
                                    + fim.eventos(),
                            null);
                }
            }
            case EventoTabela evento -> {
                pendente(evento);
                emMemoria++;
                if (evento.txId() == null) {
                    avulsos.add(evento);
                    return;
                }
                Transacao t = abertas.computeIfAbsent(evento.txId(), id -> new Transacao(id, clock.instant()));
                t.eventos.add(evento);
                t.recebidosPorTabela.merge(evento.evento().tabela(), 1, Integer::sum);
                if (t.fim != null && t.eventos.size() > t.fim.eventos()) {
                    throw new ReplicacaoException(
                            "transaction " + t.id + " has more events than its END announced (" + t.fim.eventos() + ")",
                            null);
                }
            }
        }
    }

    private void pendente(Registro registro) {
        pendentes.computeIfAbsent(registro.fluxo(), f -> new TreeSet<>()).add(registro.offset());
    }

    /** Transactions with events and no END for longer than the configured wait. */
    private List<Transacao> vencidas() {
        List<Transacao> vencidas = new ArrayList<>();
        Instant limite = clock.instant().minus(parametros.orfa());
        for (Transacao t : abertas.values()) {
            if (t.primeiroEvento.isAfter(limite)) {
                break;
            }
            if (t.fim == null) {
                vencidas.add(t);
            }
        }
        return vencidas;
    }

    private ResultadoCiclo aplicar(
            List<Transacao> grupo,
            List<Transacao> orfaos,
            List<EventoTabela> sozinhos,
            int lidosNoCiclo,
            long inicio,
            long fimPoll) {
        List<EventoTabela> ordem = new ArrayList<>(sozinhos);
        for (Transacao t : grupo) {
            List<EventoTabela> eventos = new ArrayList<>(t.eventos);
            eventos.sort(POR_ORDEM);
            ordem.addAll(eventos);
        }
        Map<String, List<ChangeEvent>> porTabela = new LinkedHashMap<>();
        Map<Fluxo, Set<Long>> aplicados = new HashMap<>();
        for (EventoTabela e : ordem) {
            ChangeEvent evento = e.evento();
            if (!evento.temEstado()) {
                throw new ReplicacaoException(
                        "event of " + evento.tabela() + "/" + evento.codigo() + " without state", null);
            }
            porTabela.computeIfAbsent(evento.tabela(), t -> new ArrayList<>()).add(evento);
            aplicados.computeIfAbsent(e.fluxo(), f -> new HashSet<>()).add(e.offset());
        }
        for (Transacao t : grupo) {
            aplicados.computeIfAbsent(t.fim.fluxo(), f -> new HashSet<>()).add(t.fim.offset());
        }
        int linhas = 0;
        for (Map.Entry<String, List<ChangeEvent>> parte : porTabela.entrySet()) {
            List<Linha> upserts = new ArrayList<>();
            for (Coalescer.Grupo g : Coalescer.coalesce(parte.getValue())) {
                upserts.add(g.ultimo().comoLinha());
            }
            sink.upsert(tabela(parte.getKey()), upserts);
            linhas += upserts.size();
        }
        Map<Fluxo, Long> novas = posicoesDepois(aplicados);
        if (!novas.isEmpty()) {
            offsets.gravar(novas);
        }
        long fimUpsert = System.nanoTime();
        confirmar("batch of " + grupo.size() + " transaction(s)");
        Instant commit = metricas == null ? null : clock.instant();
        long fimCommit = System.nanoTime();

        // The commit is durable: only now does the in-memory state follow.
        for (Map.Entry<Fluxo, Set<Long>> e : aplicados.entrySet()) {
            NavigableSet<Long> p = pendentes.get(e.getKey());
            if (p != null) {
                p.removeAll(e.getValue());
            }
        }
        emMemoria -= ordem.size();
        avulsos.clear();
        for (Transacao t : grupo) {
            fila.removeFirst();
            abertas.remove(t.id);
        }
        for (Transacao t : orfaos) {
            abertas.remove(t.id);
            orfas += t.eventos.size();
            LOG.log(
                    System.Logger.Level.WARNING,
                    "transaction " + t.id + " without END after " + parametros.orfa() + ": " + t.eventos.size()
                            + " event(s) applied alone");
        }
        gravados.putAll(novas);
        try {
            fonte.informar(novas);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot publish positions to the broker (monitoring only)", e);
        }
        if (metricas != null) {
            List<ChangeEvent> mudancas = new ArrayList<>(ordem.size());
            ordem.forEach(e -> mudancas.add(e.evento()));
            metricas.registrar(mudancas, commit, linhas, 0, new long[] {
                fimPoll - inicio, 0, fimUpsert - fimPoll, fimCommit - fimUpsert
            });
        }
        return new ResultadoCiclo(lidosNoCiclo, grupo.size(), ordem.size());
    }

    /**
     * Position of each stream after the given offsets are applied: the oldest event still pending, or the
     * next offset to read. Only streams whose persisted position changes are returned.
     */
    private Map<Fluxo, Long> posicoesDepois(Map<Fluxo, Set<Long>> aplicados) {
        Map<Fluxo, Long> novas = new HashMap<>();
        for (Map.Entry<Fluxo, Long> lido : lidos.entrySet()) {
            Fluxo fluxo = lido.getKey();
            Set<Long> saindo = aplicados.getOrDefault(fluxo, Set.of());
            long posicao = lido.getValue();
            NavigableSet<Long> p = pendentes.get(fluxo);
            if (p != null) {
                for (long offset : p) {
                    if (!saindo.contains(offset)) {
                        posicao = offset;
                        break;
                    }
                }
            }
            if (!Long.valueOf(posicao).equals(gravados.get(fluxo))) {
                novas.put(fluxo, posicao);
            }
        }
        return novas;
    }

    private void confirmar(String descricao) {
        if (!cerca.podeConfirmarDestino()) {
            throw new LeasePerdidoException("lease refused the commit of " + descricao + "; rolled back");
        }
        if (!cerca.confirmarDestino(sink)) {
            throw new LeasePerdidoException("lease expired before the commit of " + descricao + "; rolled back");
        }
    }

    private static TableSpec tabela(String nome) {
        for (TableSpec spec : Esquema.TABELAS) {
            if (spec.nome().equals(nome)) {
                return spec;
            }
        }
        throw new ReplicacaoException("unknown table " + nome, null);
    }
}
