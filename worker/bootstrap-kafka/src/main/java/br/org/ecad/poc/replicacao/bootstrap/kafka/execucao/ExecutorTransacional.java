package br.org.ecad.poc.replicacao.bootstrap.kafka.execucao;

import br.org.ecad.poc.replicacao.application.coordenacao.LeasePerdidoException;
import br.org.ecad.poc.replicacao.application.port.Cerca;
import br.org.ecad.poc.replicacao.application.replicacao.ProcessadorTransacional;
import br.org.ecad.poc.replicacao.application.replicacao.ResultadoCiclo;
import br.org.ecad.poc.replicacao.bootstrap.kafka.config.Config;
import br.org.ecad.poc.replicacao.bootstrap.observabilidade.Metricas;
import br.org.ecad.poc.replicacao.domain.replicacao.Esquema;
import br.org.ecad.poc.replicacao.domain.replicacao.Uuid7;
import br.org.ecad.poc.replicacao.infra.kafka.replicacao.FonteTransacionalKafka;
import br.org.ecad.poc.replicacao.infra.kafka.replicacao.TradutorDebezium;
import br.org.ecad.poc.replicacao.infra.kafka.replicacao.TradutorTransacional;
import br.org.ecad.poc.replicacao.infra.oracle.conexao.Conexoes;
import br.org.ecad.poc.replicacao.infra.oracle.coordenacao.LeaseGlobalAdapter;
import br.org.ecad.poc.replicacao.infra.oracle.replicacao.OffsetsAdapter;
import br.org.ecad.poc.replicacao.infra.oracle.replicacao.ReplicaSinkAdapter;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread of the transactional mode: waits for the global lease (the second replica stays here, ready for
 * failover), then runs one {@link ProcessadorTransacional} over every stream until the lease is lost or
 * something fails, and starts over from the positions stored in the destination.
 */
final class ExecutorTransacional implements Runnable {
    private static final System.Logger LOG = System.getLogger(ExecutorTransacional.class.getName());
    private static final long RETRY_MS = 2_000;
    private static final long TENTATIVA_MS = 500;
    private static final int PARTICOES_LOGICAS = Esquema.TABELAS.size() * Esquema.PARTICOES;

    private final Config config;
    private final Map<String, Object> propriedades;
    private final Uuid7 ids;
    private final Metricas metricas;
    private final Clock clock;
    private final AtomicInteger ativas;
    private final LeaseGlobalAdapter lease;
    private final TradutorTransacional tradutor;
    private volatile boolean stopped;
    private volatile long prazo;
    final Thread thread;

    ExecutorTransacional(
            Config config,
            Map<String, Object> propriedades,
            Uuid7 ids,
            Metricas metricas,
            Clock clock,
            AtomicInteger ativas,
            TradutorDebezium tradutor) {
        this.config = config;
        this.propriedades = propriedades;
        this.ids = ids;
        this.metricas = metricas;
        this.clock = clock;
        this.ativas = ativas;
        this.lease = new LeaseGlobalAdapter(config.workerId(), config.leaseSeg());
        this.tradutor = new TradutorTransacional(tradutor);
        this.thread = Thread.ofPlatform().name("transacional").unstarted(this);
    }

    void parar() {
        stopped = true;
        thread.interrupt();
    }

    @Override
    public void run() {
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            try (Connection controle = Conexoes.abrir(config.novoUrl(), config.novoUsuario(), config.novoSenha());
                    Connection novo = Conexoes.abrir(config.novoUrl(), config.novoUsuario(), config.novoSenha())) {
                lease.limitarOperacoes(controle);
                lease.limitarOperacoes(novo);
                long versao = esperarLease(controle);
                try {
                    trabalhar(controle, novo, versao);
                } finally {
                    liberar(controle, versao);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (LeasePerdidoException e) {
                LOG.log(System.Logger.Level.INFO, e.getMessage());
                dormir(config.pollMs());
            } catch (RuntimeException | SQLException e) {
                if (!stopped) {
                    LOG.log(System.Logger.Level.WARNING, "transactional processor failed; retrying", e);
                    dormir(RETRY_MS);
                }
            }
        }
    }

    private long esperarLease(Connection controle) throws SQLException, InterruptedException {
        while (!stopped) {
            long antes = System.nanoTime();
            long versao = lease.assumir(controle);
            if (versao >= 0) {
                prazo = lease.prazo(antes);
                return versao;
            }
            TimeUnit.MILLISECONDS.sleep(TENTATIVA_MS);
        }
        throw new InterruptedException();
    }

    private void trabalhar(Connection controle, Connection novo, long versao)
            throws SQLException, InterruptedException {
        FonteTransacionalKafka.Inicio inicio =
                config.inicioNoFim() ? FonteTransacionalKafka.Inicio.FIM : FonteTransacionalKafka.Inicio.INICIO;
        try (FonteTransacionalKafka fonte = FonteTransacionalKafka.abrir(
                        propriedades,
                        config.workerId(),
                        config.topicos(),
                        config.topicoTransacoes(),
                        tradutor,
                        Duration.ofMillis(config.kafkaPollMs()),
                        inicio);
                ReplicaSinkAdapter sink = new ReplicaSinkAdapter(novo, ids)) {
            Cerca cerca = lease.cerca(novo, versao, () -> prazo, novoPrazo -> prazo = novoPrazo);
            ProcessadorTransacional processador = new ProcessadorTransacional(
                    fonte,
                    sink,
                    new OffsetsAdapter(novo),
                    cerca,
                    clock,
                    new ProcessadorTransacional.Parametros(
                            config.txTransacoesPorCommit(),
                            config.txEventosPorCommit(),
                            config.txBufferMax(),
                            Duration.ofSeconds(config.txOrfaSeg())));
            processador.instrumentar(metricas);
            processador.iniciar();
            ativas.set(PARTICOES_LOGICAS);
            // Same marker the partition mode logs, so the failover scripts measure both modes alike.
            LOG.log(System.Logger.Level.INFO, "all " + PARTICOES_LOGICAS + " owned partitions active");
            long renovacao = TimeUnit.MILLISECONDS.toNanos(Math.max(1, config.leaseSeg() * 1000L / 3));
            long ultima = System.nanoTime();
            try {
                while (!stopped && !Thread.currentThread().isInterrupted()) {
                    if (System.nanoTime() - ultima >= renovacao) {
                        long antes = System.nanoTime();
                        if (!lease.renovar(controle, versao)) {
                            throw new LeasePerdidoException("global lease lost");
                        }
                        prazo = lease.prazo(antes);
                        ultima = antes;
                    }
                    ResultadoCiclo resultado = processador.processarCiclo();
                    if (resultado.eventosAplicados() > 0) {
                        ultima = System.nanoTime(); // a fenced commit extends the lease
                    }
                    if (resultado.ocioso()) {
                        Thread.sleep(config.pollMs());
                    }
                }
            } finally {
                ativas.set(0);
            }
        }
    }

    private void liberar(Connection controle, long versao) {
        boolean interrompida = Thread.interrupted();
        try {
            lease.liberar(controle, versao);
        } catch (SQLException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot release the global lease", e);
        } finally {
            if (interrompida) Thread.currentThread().interrupt();
        }
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
