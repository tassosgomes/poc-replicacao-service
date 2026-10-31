package br.org.ecad.poc.replicacao.worker;

import br.org.ecad.poc.replicacao.Config;
import br.org.ecad.poc.replicacao.core.Esquema;
import br.org.ecad.poc.replicacao.core.TableSpec;
import br.org.ecad.poc.replicacao.core.Uuid7;
import br.org.ecad.poc.replicacao.oracle.Conexoes;
import br.org.ecad.poc.replicacao.oracle.OracleOutboxSource;
import br.org.ecad.poc.replicacao.oracle.OracleReplicaSink;
import br.org.ecad.poc.replicacao.oracle.OracleStateProvider;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** One independent, recoverable loop per static partition. */
public final class Worker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(Worker.class.getName());
    private static final long RETRY_MS = 2_000;
    private final List<Thread> threads = new ArrayList<>();
    private final LongAdder anomalias = new LongAdder();
    private final AtomicInteger ativas = new AtomicInteger();
    private volatile boolean parar;
    private Metricas metricas;
    private Clock clock;
    private Thread monitor;

    private Worker() {}

    public static Worker iniciar(Config config, Clock clock) {
        return iniciar(config, clock, null, -1, () -> {});
    }

    /** Explicit test-only fault injection for one partition; the ordinary entry point has no hook. */
    public static Worker iniciar(Config config, Clock clock, TableSpec faultTable, int faultPartition, Runnable fault) {
        Worker worker = new Worker();
        worker.abrir(config, clock, faultTable, faultPartition, fault);
        return worker;
    }

    private void abrir(Config config, Clock clock, TableSpec faultTable, int faultPartition, Runnable fault) {
        this.clock = clock;
        metricas = new Metricas(Path.of(config.resultadosDir()), config.workerId(), clock);
        Uuid7 ids = new Uuid7(clock);
        ThreadFactory fabrica = config.virtualThreads()
                ? Thread.ofVirtual().name("particao-", 0).factory()
                : Thread.ofPlatform().name("particao-", 0).factory();
        for (TableSpec spec : Esquema.TABELAS) {
            for (int particao = 0; particao < Esquema.PARTICOES; particao++) {
                int numero = particao;
                Runnable hook = spec.equals(faultTable) && numero == faultPartition ? fault : () -> {};
                Thread thread = fabrica.newThread(() -> rodar(config, ids, spec, numero, hook));
                threads.add(thread);
                thread.start();
            }
        }
        monitor = Thread.ofPlatform().name("metricas").start(new MonitorMetricas(config, clock, metricas, ativas::get));
        LOG.log(System.Logger.Level.INFO, "worker " + config.workerId() + " running " + threads.size() + " partitions");
    }

    private void rodar(Config config, Uuid7 ids, TableSpec spec, int particao, Runnable hook) {
        while (!parar && !Thread.currentThread().isInterrupted()) {
            try (Connection legado = Conexoes.abrir(config.legadoUrl(), config.legadoUsuario(), config.legadoSenha());
                    Connection novo = Conexoes.abrir(config.novoUrl(), config.novoUsuario(), config.novoSenha());
                    OracleOutboxSource source = new OracleOutboxSource(legado);
                    OracleStateProvider estado = new OracleStateProvider(legado);
                    OracleReplicaSink sink = new OracleReplicaSink(novo, ids)) {
                ProcessadorParticao processador =
                        new ProcessadorParticao(spec, particao, config.loteMax(), source, estado, sink, hook);
                processador.instrumentar(metricas, clock);
                ativas.incrementAndGet();
                try {
                    while (!parar && !Thread.currentThread().isInterrupted()) {
                        ResultadoLote resultado = processador.processarLote();
                        anomalias.add(resultado.anomalias());
                        if (resultado.eventos() == 0) {
                            Thread.sleep(config.pollMs());
                        }
                    }
                } finally {
                    ativas.decrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException | SQLException e) {
                if (!parar) {
                    LOG.log(
                            System.Logger.Level.WARNING,
                            "partition " + spec.nome() + "/" + particao + " failed; retrying",
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

    public long anomalias() {
        return anomalias.sum();
    }

    public void aguardar() throws InterruptedException {
        for (Thread thread : threads) {
            thread.join();
        }
    }

    @Override
    public synchronized void close() {
        parar = true;
        threads.forEach(Thread::interrupt);
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (monitor != null) {
            monitor.interrupt();
            try {
                monitor.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
