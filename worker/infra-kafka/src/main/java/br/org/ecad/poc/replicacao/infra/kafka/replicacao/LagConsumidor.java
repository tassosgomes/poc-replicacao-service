package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/**
 * Backlog of the worker in Kafka: for each (table, partition), end offset minus committed offset (the
 * beginning offset when nothing was committed yet). Uses its own consumer, which never fetches:
 * it only asks the broker for offsets. Not thread-safe: for the single metrics thread.
 */
public final class LagConsumidor implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final Consumer<String, String> consumer;
    private final List<TopicPartition> particoes;

    public LagConsumidor(Consumer<String, String> consumer, MapaTopicos topicos) {
        this.consumer = consumer;
        this.particoes = topicos.todasAsParticoes();
        consumer.assign(particoes);
    }

    /** Lag of each partition; a failure (broker down, topic missing) propagates to the caller. */
    public Map<TopicPartition, Long> porParticao() {
        Map<TopicPartition, Long> fim = consumer.endOffsets(particoes, TIMEOUT);
        Map<TopicPartition, OffsetAndMetadata> commitados = consumer.committed(Set.copyOf(particoes), TIMEOUT);
        Map<TopicPartition, Long> inicio = null;
        Map<TopicPartition, Long> lag = new HashMap<>();
        for (TopicPartition tp : particoes) {
            OffsetAndMetadata commitado = commitados.get(tp);
            long base;
            if (commitado != null) {
                base = commitado.offset();
            } else {
                if (inicio == null) {
                    inicio = consumer.beginningOffsets(particoes, TIMEOUT);
                }
                base = inicio.get(tp);
            }
            lag.put(tp, Math.max(0, fim.get(tp) - base));
        }
        return lag;
    }

    public long total() {
        return porParticao().values().stream().mapToLong(Long::longValue).sum();
    }

    @Override
    public void close() {
        consumer.close(Duration.ofSeconds(5));
    }
}
