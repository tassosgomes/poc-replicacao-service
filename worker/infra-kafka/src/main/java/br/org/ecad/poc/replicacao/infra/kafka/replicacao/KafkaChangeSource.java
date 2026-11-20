package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

import br.org.ecad.poc.replicacao.application.port.ChangeSource;
import br.org.ecad.poc.replicacao.domain.replicacao.ChangeEvent;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;

/**
 * {@link ChangeSource} over Kafka topics written by Debezium. The Kafka partition number is the logical
 * partition of the worker (0..15): one consumer per (table, partition) with manual {@code assign()} and
 * no group rebalance; who may read a partition is decided by the lease of the destination, as in the
 * lab Java.
 *
 * <p><b>Delivery.</b> {@code poll} returns up to {@code max} events in offset order. Events that were
 * delivered and not acknowledged come again (first) on the next poll; after the source is reopened, the
 * consumer is positioned at the committed offset. Records the consumer returned beyond {@code max} are
 * kept in memory for the next poll (not re-fetched), and the consumer is polled again only when that
 * buffer is empty, so memory is bounded by {@code max.poll.records}.
 *
 * <p><b>Ack.</b> {@code ack} commits (synchronously) the offset after the highest acknowledged event of
 * the partition, to be called only after the commit in the destination. It never commits past a delivered
 * event that is still unacknowledged. Acknowledging an event this instance did not deliver fails.
 *
 * <p><b>Failure.</b> Any error while polling (including a message that cannot be translated) discards the
 * consumer of that partition; the next poll reopens it at the committed offset instead of silently
 * skipping the records already fetched. A poison message therefore stalls its partition (it is retried by
 * the partition loop and logged), preserving consistency over availability.
 *
 * <p>Use one instance per partition thread; the partitions of an instance are independent.
 */
public final class KafkaChangeSource implements ChangeSource, AutoCloseable {

    /** Creates the consumer of one partition (a {@code KafkaConsumer}, or a mock in tests). */
    @FunctionalInterface
    public interface FabricaConsumidor {
        Consumer<String, String> criar(TopicPartition particao);
    }

    private final FabricaConsumidor fabrica;
    private final MapaTopicos topicos;
    private final TradutorDebezium tradutor;
    private final Duration pollTimeout;
    private final Map<TopicPartition, ParticaoKafka> particoes = new LinkedHashMap<>();
    private final Map<ChangeEvent, ParticaoKafka> donos = new IdentityHashMap<>();

    public KafkaChangeSource(
            FabricaConsumidor fabrica, MapaTopicos topicos, TradutorDebezium tradutor, Duration pollTimeout) {
        this.fabrica = fabrica;
        this.topicos = topicos;
        this.tradutor = tradutor;
        this.pollTimeout = pollTimeout;
    }

    @Override
    public List<ChangeEvent> poll(String tabela, int particao, int max) {
        TopicPartition tp = topicos.particao(tabela, particao);
        ParticaoKafka p = abrir(tabela, tp);
        try {
            List<ChangeEvent> eventos = p.poll(max);
            registrar(eventos, p);
            return eventos;
        } catch (RuntimeException e) {
            descartar(tp);
            throw e;
        }
    }

    @Override
    public void ack(List<ChangeEvent> eventos) {
        Map<ParticaoKafka, List<ChangeEvent>> porParticao = new LinkedHashMap<>();
        for (ChangeEvent evento : eventos) {
            ParticaoKafka dono = dono(evento);
            if (dono == null) {
                throw new ReplicacaoException(
                        "ack of an event not delivered by this source (" + evento.tabela() + "@" + evento.eventoId()
                                + ")",
                        null);
            }
            porParticao.computeIfAbsent(dono, k -> new ArrayList<>()).add(evento);
        }
        porParticao.forEach((dono, lista) -> {
            dono.confirmar(lista);
            esquecer(lista);
        });
    }

    @Override
    public synchronized void close() {
        RuntimeException falha = null;
        for (ParticaoKafka p : particoes.values()) {
            try {
                p.close();
            } catch (RuntimeException e) {
                falha = e;
            }
        }
        particoes.clear();
        donos.clear();
        if (falha != null) {
            throw falha;
        }
    }

    private synchronized ParticaoKafka abrir(String tabela, TopicPartition tp) {
        ParticaoKafka p = particoes.get(tp);
        if (p == null) {
            p = new ParticaoKafka(TradutorDebezium.tabela(tabela), tp, fabrica.criar(tp), tradutor, pollTimeout);
            particoes.put(tp, p);
        }
        return p;
    }

    private synchronized void descartar(TopicPartition tp) {
        ParticaoKafka p = particoes.remove(tp);
        if (p != null) {
            donos.values().removeIf(d -> d == p);
            try {
                p.close();
            } catch (RuntimeException ignorada) {
                // the original failure is the one that matters
            }
        }
    }

    private synchronized void registrar(List<ChangeEvent> eventos, ParticaoKafka p) {
        eventos.forEach(e -> donos.put(e, p));
    }

    private synchronized ParticaoKafka dono(ChangeEvent evento) {
        return donos.get(evento);
    }

    private synchronized void esquecer(List<ChangeEvent> eventos) {
        eventos.forEach(donos::remove);
    }
}
