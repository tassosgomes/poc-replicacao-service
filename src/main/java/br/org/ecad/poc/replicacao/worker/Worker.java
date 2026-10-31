package br.org.ecad.poc.replicacao.worker;

import br.org.ecad.poc.replicacao.Config;
import br.org.ecad.poc.replicacao.core.Esquema;
import br.org.ecad.poc.replicacao.core.TableSpec;
import br.org.ecad.poc.replicacao.core.Uuid7;
import br.org.ecad.poc.replicacao.oracle.Conexoes;
import br.org.ecad.poc.replicacao.oracle.OracleOutboxSource;
import br.org.ecad.poc.replicacao.oracle.OracleReplicaSink;
import br.org.ecad.poc.replicacao.oracle.OracleStateProvider;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadFactory;

/**
 * One process owning every partition (static assignment, no dynamic lease: V-07). Each
 * (table, partition) gets its own loop with its own two connections. Loops run on virtual threads
 * unless {@code VIRTUAL_THREADS=false}.
 */
public final class Worker implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Worker.class.getName());

    private final List<Thread> threads = new ArrayList<>();
    private final List<AutoCloseable> recursos = new ArrayList<>();
    private volatile boolean parar;

    private Worker() {}

    /** Opens the connections of every partition and starts its loop. */
    public static Worker iniciar(Config config, Clock clock) {
        Worker worker = new Worker();
        try {
            worker.abrir(config, clock);
        } catch (RuntimeException e) {
            worker.close();
            throw e;
        }
        return worker;
    }

    private void abrir(Config config, Clock clock) {
        Uuid7 ids = new Uuid7(clock);
        ThreadFactory fabrica = config.virtualThreads()
                ? Thread.ofVirtual().name("particao-", 0).factory()
                : Thread.ofPlatform().name("particao-", 0).factory();
        List<Runnable> loops = new ArrayList<>();
        for (TableSpec spec : Esquema.TABELAS) {
            for (int particao = 0; particao < Esquema.PARTICOES; particao++) {
                Connection legado =
                        registrar(Conexoes.abrir(config.legadoUrl(), config.legadoUsuario(), config.legadoSenha()));
                Connection novo = registrar(Conexoes.abrir(config.novoUrl(), config.novoUsuario(), config.novoSenha()));
                OracleOutboxSource source = new OracleOutboxSource(legado);
                OracleStateProvider estado = new OracleStateProvider(legado);
                OracleReplicaSink sink = new OracleReplicaSink(novo, ids);
                ProcessadorParticao processador =
                        new ProcessadorParticao(spec, particao, config.loteMax(), source, estado, sink);
                String nome = spec.nome() + "/" + particao;
                loops.add(() -> rodar(nome, processador, config.pollMs()));
            }
        }
        for (Runnable loop : loops) {
            Thread thread = fabrica.newThread(loop);
            threads.add(thread);
            thread.start();
        }
        LOG.log(System.Logger.Level.INFO, "worker " + config.workerId() + " running " + loops.size() + " partitions");
    }

    private void rodar(String nome, ProcessadorParticao processador, int pollMs) {
        try {
            processador.executar(() -> parar, pollMs);
        } catch (RuntimeException e) {
            if (!parar) {
                LOG.log(System.Logger.Level.ERROR, "partition " + nome + " stopped on failure", e);
            }
        }
    }

    private Connection registrar(Connection conexao) {
        recursos.add(conexao);
        return conexao;
    }

    /** Blocks until every loop ends (after {@link #close()} or a failure). */
    public void aguardar() throws InterruptedException {
        for (Thread thread : threads) {
            thread.join();
        }
    }

    /** Stops the loops, waits for them and closes the connections. */
    @Override
    public void close() {
        parar = true;
        for (Thread thread : threads) {
            thread.interrupt();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (AutoCloseable recurso : recursos) {
            try {
                recurso.close();
            } catch (Exception e) {
                LOG.log(System.Logger.Level.WARNING, "failed to close a connection", e);
            }
        }
        recursos.clear();
    }
}
