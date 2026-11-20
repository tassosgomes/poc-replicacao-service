package br.org.ecad.poc.replicacao.bootstrap.kafka.execucao;

import br.org.ecad.poc.replicacao.application.coordenacao.LeasePerdidoException;
import br.org.ecad.poc.replicacao.application.port.StateProvider;
import br.org.ecad.poc.replicacao.application.replicacao.ProcessadorParticao;
import br.org.ecad.poc.replicacao.application.replicacao.ResultadoLote;
import br.org.ecad.poc.replicacao.bootstrap.kafka.config.Config;
import br.org.ecad.poc.replicacao.bootstrap.observabilidade.Metricas;
import br.org.ecad.poc.replicacao.domain.coordenacao.Particao;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import br.org.ecad.poc.replicacao.domain.replicacao.Uuid7;
import br.org.ecad.poc.replicacao.infra.kafka.replicacao.KafkaChangeSource;
import br.org.ecad.poc.replicacao.infra.kafka.replicacao.TradutorDebezium;
import br.org.ecad.poc.replicacao.infra.oracle.conexao.Conexoes;
import br.org.ecad.poc.replicacao.infra.oracle.coordenacao.LeaseRepositoryAdapter;
import br.org.ecad.poc.replicacao.infra.oracle.replicacao.ReplicaSinkAdapter;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * One partition thread: destination connection, Kafka consumer and recovery after transient failures.
 * Mirrors the executor of the lab Java without the legacy connection: every attempt reopens the
 * consumer, which positions at the committed offset (redelivery of what was not acknowledged).
 */
final class ExecutorParticao implements Runnable {
    private static final System.Logger LOG = System.getLogger(ExecutorParticao.class.getName());
    private static final long RETRY_MS = 2_000;
    private static final long LENTA_MS = 1_000;
    /** Events always carry their state: the origin is never queried. */
    private static final StateProvider SEM_ORIGEM = (spec, codigos) -> {
        throw new ReplicacaoException("the Kafka source carries the state; there is no origin to query", null);
    };

    private final Config config;
    private final LeaseRepositoryAdapter leases;
    private final Uuid7 ids;
    private final Particao p;
    private final Runnable hook;
    private final Metricas metricas;
    private final Clock clock;
    private final AtomicInteger ativas;
    private final LongAdder anomalias;
    private final AtomicInteger possuidas;
    private final KafkaChangeSource.FabricaConsumidor fabrica;
    private final TradutorDebezium tradutor;
    private final long reivindicada = System.nanoTime();
    volatile long deadline;
    volatile boolean stopped;
    final Thread thread;

    ExecutorParticao(Recursos recursos, Particao p, Runnable hook, long deadline) {
        this.config = recursos.config();
        this.leases = recursos.leases();
        this.ids = recursos.ids();
        this.metricas = recursos.metricas();
        this.clock = recursos.clock();
        this.ativas = recursos.ativas();
        this.anomalias = recursos.anomalias();
        this.possuidas = recursos.possuidas();
        this.fabrica = recursos.fabrica();
        this.tradutor = recursos.tradutor();
        this.p = p;
        this.hook = hook;
        this.deadline = deadline;
        thread = config.virtualThreads()
                ? Thread.ofVirtual().name("particao-" + p.numero()).unstarted(this)
                : Thread.ofPlatform().name("particao-" + p.numero()).unstarted(this);
    }

    record Recursos(
            Config config,
            LeaseRepositoryAdapter leases,
            Uuid7 ids,
            Metricas metricas,
            Clock clock,
            AtomicInteger ativas,
            LongAdder anomalias,
            AtomicInteger possuidas,
            KafkaChangeSource.FabricaConsumidor fabrica,
            TradutorDebezium tradutor) {}

    @Override
    public void run() {
        while (!stopped && !Thread.currentThread().isInterrupted()) {
            try (Connection novo = Conexoes.abrir(config.novoUrl(), config.novoUsuario(), config.novoSenha());
                    KafkaChangeSource source = new KafkaChangeSource(
                            fabrica, config.topicos(), tradutor, Duration.ofMillis(config.kafkaPollMs()));
                    ReplicaSinkAdapter sink = new ReplicaSinkAdapter(novo, ids)) {
                leases.limitarOperacoes(novo);
                ProcessadorParticao processador = new ProcessadorParticao(
                        p.tabela(), p.numero(), config.loteMax(), source, SEM_ORIGEM, sink, hook);
                processador.instrumentar(metricas, clock);
                processador.cercar(leases.cerca(novo, p, () -> deadline));
                int ativasAgora = ativas.incrementAndGet();
                long ativacaoMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - reivindicada);
                if (ativacaoMs >= LENTA_MS) {
                    LOG.log(
                            System.Logger.Level.INFO,
                            "partition " + p.tabela().nome() + "/" + p.numero() + " active " + ativacaoMs
                                    + " ms after claim");
                }
                // Marks the end of a takeover: every owned partition has a running processor.
                if (ativasAgora == possuidas.get()) {
                    LOG.log(System.Logger.Level.INFO, "all " + ativasAgora + " owned partitions active");
                }
                try {
                    while (!stopped && !Thread.currentThread().isInterrupted()) {
                        if (System.nanoTime() >= deadline) {
                            Thread.sleep(100);
                            continue;
                        }
                        ResultadoLote resultado = processador.processarLote();
                        anomalias.add(resultado.anomalias());
                        if (resultado.eventos() == 0) Thread.sleep(config.pollMs());
                    }
                } finally {
                    ativas.decrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (LeasePerdidoException e) {
                // Nothing of the batch was applied after the deadline; wait for renewal or for the stop.
                LOG.log(System.Logger.Level.INFO, e.getMessage());
                try {
                    Thread.sleep(config.pollMs());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (RuntimeException | SQLException e) {
                if (!stopped) {
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "partition " + p.tabela().nome() + "/" + p.numero() + " failed; retrying",
                            e);
                    try {
                        Thread.sleep(RETRY_MS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }
}
