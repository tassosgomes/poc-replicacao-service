package br.org.ecad.poc.replicacao.application.replicacao;

import br.org.ecad.poc.replicacao.application.coordenacao.LeasePerdidoException;
import br.org.ecad.poc.replicacao.application.port.Cerca;
import br.org.ecad.poc.replicacao.application.port.ChangeSource;
import br.org.ecad.poc.replicacao.application.port.MetricasLote;
import br.org.ecad.poc.replicacao.application.port.ReplicaSink;
import br.org.ecad.poc.replicacao.application.port.StateProvider;
import br.org.ecad.poc.replicacao.domain.replicacao.ChangeEvent;
import br.org.ecad.poc.replicacao.domain.replicacao.Coalescer;
import br.org.ecad.poc.replicacao.domain.replicacao.Estados;
import br.org.ecad.poc.replicacao.domain.replicacao.Linha;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Loop of one (table, partition). One batch: poll, coalesce by key, fetch current state, upsert
 * and commit in the destination, and only then acknowledge (delete) the events in the origin.
 * The two transactions are separate: a crash between them only reprocesses idempotently.
 *
 * A missing origin row is counted and its events are discarded so the partition never stalls on it.
 */
public final class ProcessadorParticao {

    private static final System.Logger LOG = System.getLogger(ProcessadorParticao.class.getName());

    private final TableSpec spec;
    private final int particao;
    private final int loteMax;
    private final ChangeSource source;
    private final StateProvider state;
    private final ReplicaSink sink;
    private final Runnable afterCommit;
    private MetricasLote metricas;
    private Clock clock;
    private Cerca cerca = Cerca.LIVRE;

    public ProcessadorParticao(
            TableSpec spec, int particao, int loteMax, ChangeSource source, StateProvider state, ReplicaSink sink) {
        this(spec, particao, loteMax, source, state, sink, () -> {});
    }

    /** The hook permits fault injection in tests; production uses the no-op constructor. */
    public ProcessadorParticao(
            TableSpec spec,
            int particao,
            int loteMax,
            ChangeSource source,
            StateProvider state,
            ReplicaSink sink,
            Runnable afterCommit) {
        this.spec = spec;
        this.particao = particao;
        this.loteMax = loteMax;
        this.source = source;
        this.state = state;
        this.sink = sink;
        this.afterCommit = afterCommit;
    }

    public void instrumentar(MetricasLote metricas, Clock clock) {
        this.metricas = metricas;
        this.clock = clock;
    }

    /** Guards the destination commit and the origin ack of every batch by the partition lease. */
    public void cercar(Cerca cerca) {
        this.cerca = cerca;
    }

    /**
     * Processes at most one batch; returns {@link ResultadoLote#VAZIO} when the partition is empty.
     * Throws {@link LeasePerdidoException} when the lease refuses the commit or the ack.
     */
    public ResultadoLote processarLote() {
        long inicio = System.nanoTime();
        List<ChangeEvent> eventos = source.poll(spec.nome(), particao, loteMax);
        if (eventos.isEmpty()) {
            return ResultadoLote.VAZIO;
        }
        long fimPoll = System.nanoTime();
        List<Coalescer.Grupo> grupos = Coalescer.coalesce(eventos);

        List<Linha> linhas = new ArrayList<>(grupos.size());
        Set<Long> aBuscar = new LinkedHashSet<>();
        for (Coalescer.Grupo grupo : grupos) {
            ChangeEvent ultimo = grupo.ultimo();
            if (ultimo.temEstado()) {
                linhas.add(ultimo.comoLinha());
            } else {
                aBuscar.add(grupo.codigo());
            }
        }
        int anomalias = 0;
        if (!aBuscar.isEmpty()) {
            Estados estados = state.buscar(spec, aBuscar);
            linhas.addAll(estados.encontradas().values());
            anomalias = estados.ausentes().size();
            if (anomalias > 0) {
                LOG.log(
                        System.Logger.Level.WARNING,
                        "anomaly: " + anomalias + " key(s) of " + spec.nome() + " not found in the origin");
            }
        }
        long fimBusca = System.nanoTime();
        if (!linhas.isEmpty()) {
            sink.upsert(spec, linhas);
        }
        if (!cerca.podeConfirmarDestino()) {
            throw new LeasePerdidoException(
                    "lease of " + spec.nome() + "/" + particao + " refused the commit; batch rolled back");
        }
        if (!cerca.confirmarDestino(sink)) {
            throw new LeasePerdidoException(
                    "lease of " + spec.nome() + "/" + particao + " expired before the commit; batch rolled back");
        }
        Instant commit = metricas == null ? null : clock.instant();
        long fimUpsert = System.nanoTime();
        afterCommit.run();
        if (!cerca.podeConfirmarOrigem()) {
            throw new LeasePerdidoException(
                    "lease of " + spec.nome() + "/" + particao + " refused the ack; events left for the next owner");
        }
        source.ack(eventos);
        long fimAck = System.nanoTime();
        if (metricas != null) {
            metricas.registrar(eventos, commit, linhas.size(), anomalias, new long[] {
                fimPoll - inicio, fimBusca - fimPoll, fimUpsert - fimBusca, fimAck - fimUpsert
            });
        }
        return new ResultadoLote(eventos.size(), linhas.size(), anomalias);
    }

    /** Runs batches until {@code parar} turns true; sleeps {@code pollMs} when the partition is empty. */
    public void executar(BooleanSupplier parar, long pollMs) {
        while (!parar.getAsBoolean()) {
            ResultadoLote resultado = processarLote();
            if (resultado.eventos() == 0 && !dormir(pollMs)) {
                return;
            }
        }
    }

    private static boolean dormir(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
