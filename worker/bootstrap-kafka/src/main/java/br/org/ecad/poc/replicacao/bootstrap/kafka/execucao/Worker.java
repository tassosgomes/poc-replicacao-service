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
 * <p>Where the transactional variant (phase 4 of the plan) would enter: a processor of
 * {@code application} that groups the events of one origin transaction across tables (by
 * {@code source.txId}, with the Debezium transaction topic as the boundary) would replace
 * {@code ProcessadorParticao} in {@code ExecutorParticao}; the source, lease and sink stay as they are.
 */
public final class Worker implements AutoCloseable {
    private final LongAdder anomalias = new LongAdder();
    private final Thread monitor;
    private final Thread coordinator;
    private final CoordenadorParticoes coordenador;

    private Worker(Config config, Clock clock, ExecutorParticoes.Falha falha) {
        AtomicInteger ativas = new AtomicInteger();
        Metricas metricas =
                new Metricas(Path.of(config.resultadosDir()), config.workerId(), clock, config.eventosCsv());
        LeaseRepositoryAdapter leases = new LeaseRepositoryAdapter(
                config.workerId(),
                config.leaseSeg(),
                () -> Conexoes.abrir(config.novoUrl(), config.novoUsuario(), config.novoSenha()));
        Map<String, Object> props =
                PropriedadesConsumidor.montar(config.bootstrapServers(), config.groupId(), config.propriedadesKafka());
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
        coordenador.solicitarParada();
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
