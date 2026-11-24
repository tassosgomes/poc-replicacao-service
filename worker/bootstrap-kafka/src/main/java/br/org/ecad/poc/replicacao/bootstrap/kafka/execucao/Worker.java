package br.org.ecad.poc.replicacao.bootstrap.kafka.execucao;

import br.org.ecad.poc.replicacao.application.coordenacao.CoordenadorParticoes;
import br.org.ecad.poc.replicacao.bootstrap.kafka.config.Config;
import br.org.ecad.poc.replicacao.bootstrap.kafka.observabilidade.MonitorMetricas;
import br.org.ecad.poc.replicacao.bootstrap.observabilidade.Metricas;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import br.org.ecad.poc.replicacao.domain.replicacao.Uuid7;
import br.org.ecad.poc.replicacao.infra.kafka.replicacao.PropriedadesConsumidor;
import br.org.ecad.poc.replicacao.infra.kafka.replicacao.TradutorDebezium;
import br.org.ecad.poc.replicacao.infra.oracle.conexao.Conexoes;
import br.org.ecad.poc.replicacao.infra.oracle.coordenacao.LeaseRepositoryAdapter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Composes the Kafka service and owns its startup and ordered shutdown. Lease and fence live in the
 * destination database (as in the lab Java); there is no legacy connection.
 *
 * <p>Two modes ({@code MODO}). {@code atual} (default): leases per (table, partition) and one
 * {@code ProcessadorParticao} thread each. {@code transacional} (phase 4): one global lease and one
 * {@code ProcessadorTransacional} over every stream, which applies whole origin transactions.
 */
public final class Worker implements AutoCloseable {
    private final LongAdder anomalias = new LongAdder();
    private final Thread monitor;
    private final Thread coordinator;
    private final CoordenadorParticoes coordenador;
    private final ExecutorTransacional transacional;

    private Worker(Config config, Clock clock, ExecutorParticoes.Falha falha) {
        AtomicInteger ativas = new AtomicInteger();
        boolean modoTransacional = config.modo() == Config.Modo.TRANSACIONAL;
        Metricas metricas =
                new Metricas(Path.of(config.resultadosDir()), config.workerId(), clock, config.eventosCsv());
        LeaseRepositoryAdapter leases = new LeaseRepositoryAdapter(
                config.workerId(),
                config.leaseSeg(),
                () -> Conexoes.abrir(config.novoUrl(), config.novoUsuario(), config.novoSenha()));
        Map<String, Object> props =
                PropriedadesConsumidor.montar(config.bootstrapServers(), config.groupId(), config.propriedadesKafka());
        if (modoTransacional) {
            props.put(org.apache.kafka.clients.consumer.ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1_000);
            coordenador = null;
            transacional = new ExecutorTransacional(
                    config, props, new Uuid7(clock), metricas, clock, ativas, new TradutorDebezium(config.formato()));
            monitor = Thread.ofPlatform()
                    .name("metricas")
                    .start(new MonitorMetricas(config, props, clock, metricas, ativas::get));
            coordinator = transacional.thread;
            coordinator.start();
            return;
        }
        transacional = null;
        ExecutorParticao.Recursos recursos = new ExecutorParticao.Recursos(
                config,
                leases,
                new Uuid7(clock),
                metricas,
                clock,
                ativas,
                anomalias,
                new AtomicInteger(),
                PropriedadesConsumidor.fabrica(props, config.workerId()),
                new TradutorDebezium(config.formato()));
        ExecutorParticoes execucao = new ExecutorParticoes(recursos, falha);
        coordenador = new CoordenadorParticoes(leases, execucao, config.leaseSeg());
        monitor = Thread.ofPlatform()
                .name("metricas")
                .start(new MonitorMetricas(config, props, clock, metricas, ativas::get));
        coordinator = Thread.ofPlatform().name("leases").start(coordenador);
    }

    public static Worker iniciar(Config config, Clock clock) {
        return iniciar(config, clock, null, -1, () -> {});
    }

    /** Test hook between destination commit and origin acknowledgement. */
    public static Worker iniciar(Config config, Clock clock, TableSpec tabela, int particao, Runnable falha) {
        return new Worker(config, clock, new ExecutorParticoes.Falha(tabela, particao, falha));
    }

    public long anomalias() {
        return anomalias.sum();
    }

    public void aguardar() throws InterruptedException {
        coordinator.join();
    }

    @Override
    public synchronized void close() {
        if (transacional != null) {
            transacional.parar();
        } else {
            coordenador.solicitarParada();
            coordinator.interrupt();
        }
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
