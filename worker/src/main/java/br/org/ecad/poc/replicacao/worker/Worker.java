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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** One recoverable processor per leased partition. */
public final class Worker implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(Worker.class.getName());
    private static final long RETRY_MS = 2_000;
    /** A processor taking longer than this from claim to active is logged (connection setup). */
    private static final long LENTA_MS = 1_000;

    private final Map<GerenciadorLease.Particao, Processor> processors = new LinkedHashMap<>();
    private final LongAdder anomalias = new LongAdder();
    private final AtomicInteger ativas = new AtomicInteger();
    /** Partitions owned after the last coordination cycle; set before their processors start. */
    private volatile int possuidas;

    private volatile boolean parar;
    private Metricas metricas;
    private Clock clock;
    private Thread monitor;
    private Thread coordinator;

    private static final class Processor {
        volatile long deadline;
        volatile boolean stopped;
        Thread thread;
    }

    private Worker() {}

    public static Worker iniciar(Config config, Clock clock) {
        return iniciar(config, clock, null, -1, () -> {});
    }

    public static Worker iniciar(Config config, Clock clock, TableSpec faultTable, int faultPartition, Runnable fault) {
        Worker worker = new Worker();
        worker.abrir(config, clock, faultTable, faultPartition, fault);
        return worker;
    }

    private void abrir(Config config, Clock clock, TableSpec faultTable, int faultPartition, Runnable fault) {
        this.clock = clock;
        metricas = new Metricas(Path.of(config.resultadosDir()), config.workerId(), clock, config.eventosCsv());
        monitor = Thread.ofPlatform().name("metricas").start(new MonitorMetricas(config, clock, metricas, ativas::get));
        coordinator =
                Thread.ofPlatform().name("leases").start(() -> coordenar(config, faultTable, faultPartition, fault));
    }

    private void coordenar(Config config, TableSpec faultTable, int faultPartition, Runnable fault) {
        GerenciadorLease leases = new GerenciadorLease(config);
        Uuid7 ids = new Uuid7(clock);
        try {
            while (!parar) {
                long start = System.nanoTime();
                try (Connection c = leases.conectar()) {
                    int ativos = leases.heartbeat(c);
                    List<GerenciadorLease.Particao> owned = leases.renovar(c);
                    c.commit();
                    for (GerenciadorLease.Particao p : List.copyOf(processors.keySet())) {
                        if (!owned.contains(p)) stop(p);
                    }
                    long deadline =
                            start + TimeUnit.SECONDS.toNanos(config.leaseSeg() - (long) GerenciadorLease.MARGEM_SEG);
                    for (GerenciadorLease.Particao p : owned) {
                        Processor processor = processors.get(p);
                        if (processor != null) processor.deadline = deadline;
                    }
                    int total = Esquema.TABELAS.size() * Esquema.PARTICOES;
                    int quota = (total + ativos - 1) / ativos;
                    int liberadas = 0;
                    for (GerenciadorLease.Particao p : List.copyOf(owned)) {
                        if (owned.size() <= quota) break;
                        stop(p);
                        leases.liberar(c, p);
                        c.commit();
                        owned.remove(p);
                        liberadas++;
                    }
                    List<GerenciadorLease.Particao> claimed = leases.adquirir(c, quota - owned.size());
                    c.commit();
                    owned.addAll(claimed);
                    possuidas = owned.size();
                    if (liberadas > 0 || !claimed.isEmpty()) {
                        LOG.log(
                                System.Logger.Level.INFO,
                                "leases: workers=" + ativos + " quota=" + quota + " released=" + liberadas + " claimed="
                                        + claimed.size() + " owned=" + owned.size());
                    }
                    for (GerenciadorLease.Particao p : owned) {
                        if (!processors.containsKey(p) && System.nanoTime() < deadline) {
                            Processor processor = new Processor();
                            processor.deadline = deadline;
                            Runnable hook =
                                    p.tabela().equals(faultTable) && p.numero() == faultPartition ? fault : () -> {};
                            long reivindicada = System.nanoTime();
                            Runnable job = () -> rodar(config, leases, ids, p, hook, processor, reivindicada);
                            processor.thread = config.virtualThreads()
                                    ? Thread.ofVirtual()
                                            .name("particao-" + p.numero())
                                            .unstarted(job)
                                    : Thread.ofPlatform()
                                            .name("particao-" + p.numero())
                                            .unstarted(job);
                            processors.put(p, processor);
                            processor.thread.start();
                        }
                    }
                } catch (SQLException | RuntimeException e) {
                    if (!parar) LOG.log(System.Logger.Level.WARNING, "lease coordination failed", e);
                }
                if (!parar) {
                    // One fixed cycle of LEASE_SEG/3 for heartbeat, renewal and claim; never shortened to
                    // claim sooner (consistency over availability: failover waits for the lease to expire).
                    long cicloNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, config.leaseSeg() * 1000L / 3));
                    long espera = cicloNanos - (System.nanoTime() - start);
                    if (espera > 0) TimeUnit.NANOSECONDS.sleep(espera);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            Thread.interrupted();
            // Signal every processor first so they close their connections in parallel, then wait for all.
            for (Processor processor : processors.values()) {
                processor.stopped = true;
                processor.thread.interrupt();
            }
            for (GerenciadorLease.Particao p : List.copyOf(processors.keySet())) stop(p);
            try (Connection c = leases.conectar()) {
                leases.liberarTodos(c);
                leases.sair(c);
                c.commit();
            } catch (SQLException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot release leases during shutdown", e);
            }
        }
    }

    private void stop(GerenciadorLease.Particao p) {
        Processor processor = processors.remove(p);
        if (processor == null) return;
        processor.stopped = true;
        processor.thread.interrupt();
        boolean interrupted = false;
        while (processor.thread.isAlive()) {
            try {
                processor.thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void rodar(
            Config config,
            GerenciadorLease leases,
            Uuid7 ids,
            GerenciadorLease.Particao p,
            Runnable hook,
            Processor processor,
            long reivindicada) {
        while (!parar && !processor.stopped && !Thread.currentThread().isInterrupted()) {
            try (Connection legado = Conexoes.abrir(config.legadoUrl(), config.legadoUsuario(), config.legadoSenha());
                    Connection novo = Conexoes.abrir(config.novoUrl(), config.novoUsuario(), config.novoSenha());
                    OracleOutboxSource source = new OracleOutboxSource(legado);
                    OracleStateProvider estado = new OracleStateProvider(legado);
                    OracleReplicaSink sink = new OracleReplicaSink(novo, ids)) {
                leases.limitarOperacoes(legado);
                leases.limitarOperacoes(novo);
                ProcessadorParticao processador =
                        new ProcessadorParticao(p.tabela(), p.numero(), config.loteMax(), source, estado, sink, hook);
                processador.instrumentar(metricas, clock);
                processador.cercar(leases.cerca(novo, p, () -> processor.deadline));
                int ativasAgora = ativas.incrementAndGet();
                long ativacaoMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - reivindicada);
                if (ativacaoMs >= LENTA_MS) {
                    LOG.log(
                            System.Logger.Level.INFO,
                            "partition " + p.tabela().nome() + "/" + p.numero() + " active " + ativacaoMs
                                    + " ms after claim");
                }
                // Marks the end of a takeover: every owned partition has a running processor (V-07 measures
                // the failover up to this line, observed against the database clock).
                if (ativasAgora == possuidas) {
                    LOG.log(System.Logger.Level.INFO, "all " + ativasAgora + " owned partitions active");
                }
                try {
                    while (!parar
                            && !processor.stopped
                            && !Thread.currentThread().isInterrupted()) {
                        if (System.nanoTime() >= processor.deadline) {
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
                if (!parar && !processor.stopped) {
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

    public long anomalias() {
        return anomalias.sum();
    }

    public void aguardar() throws InterruptedException {
        coordinator.join();
    }

    @Override
    public synchronized void close() {
        parar = true;
        coordinator.interrupt();
        try {
            coordinator.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        monitor.interrupt();
        try {
            monitor.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
