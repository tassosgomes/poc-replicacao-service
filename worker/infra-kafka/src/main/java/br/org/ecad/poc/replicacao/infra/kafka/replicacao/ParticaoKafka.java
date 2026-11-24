package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

import br.org.ecad.poc.replicacao.domain.replicacao.ChangeEvent;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/**
 * One manually assigned Kafka partition (= one logical partition of the worker) with its own
 * consumer. The consumer is not thread-safe, so every operation is synchronized.
 *
 * <p>Invariant: the committed offset is never past an event that was delivered and not acknowledged.
 * Records fetched beyond {@code max} wait in {@link #buffer} (no re-fetch, no seek); delivered but
 * unacknowledged events wait in {@link #entregues} and are put back at the front of the buffer on the
 * next poll (redelivery inside the same instance). After a reopen the consumer starts at the
 * committed offset, which is the redelivery across instances.
 */
final class ParticaoKafka implements AutoCloseable {

    private final TableSpec spec;
    private final TopicPartition tp;
    private final Consumer<String, String> consumer;
    private final TradutorDebezium tradutor;
    private final Duration pollTimeout;
    private final Deque<ChangeEvent> buffer = new ArrayDeque<>();
    private final List<ChangeEvent> entregues = new ArrayList<>();
    private final Map<ChangeEvent, Boolean> conhecidos = new IdentityHashMap<>();
    /** First offset not yet committed. */
    private long commitado;
    /** Next offset after the last record seen, including skipped tombstones. */
    private long visto;
    /** Highest offset acknowledged so far, which may sit above a delivered event still pending. */
    private long maiorConfirmado = -1;

    ParticaoKafka(
            TableSpec spec,
            TopicPartition tp,
            Consumer<String, String> consumer,
            TradutorDebezium tradutor,
            Duration pollTimeout) {
        this.spec = spec;
        this.tp = tp;
        this.consumer = consumer;
        this.tradutor = tradutor;
        this.pollTimeout = pollTimeout;
        consumer.assign(Set.of(tp));
        OffsetAndMetadata ultimo = consumer.committed(Set.of(tp)).get(tp);
        if (ultimo == null) {
            consumer.seekToBeginning(Set.of(tp));
        } else {
            consumer.seek(tp, ultimo.offset());
        }
        commitado = consumer.position(tp);
        visto = commitado;
    }

    synchronized List<ChangeEvent> poll(int max) {
        for (int i = entregues.size() - 1; i >= 0; i--) {
            buffer.addFirst(entregues.get(i));
        }
        entregues.clear();
        if (buffer.isEmpty()) {
            buscar();
        }
        List<ChangeEvent> lote = new ArrayList<>(Math.min(max, buffer.size()));
        while (lote.size() < max && !buffer.isEmpty()) {
            ChangeEvent evento = buffer.pollFirst();
            lote.add(evento);
            entregues.add(evento);
        }
        return List.copyOf(lote);
    }

    private void buscar() {
        for (ConsumerRecord<String, String> r : consumer.poll(pollTimeout).records(tp)) {
            visto = r.offset() + 1;
            var evento = tradutor.traduzir(
                    spec, new MensagemKafka(r.topic(), r.partition(), r.offset(), r.timestamp(), r.value()));
            evento.ifPresent(e -> {
                buffer.addLast(e);
                conhecidos.put(e, Boolean.TRUE);
            });
        }
        // Tombstones at the tail would keep the lag above zero forever; with nothing pending, skip them.
        if (buffer.isEmpty() && entregues.isEmpty() && visto > commitado) {
            confirmarAte(visto);
        }
    }

    /** Commits up to the acknowledged events, never past a delivered event still unacknowledged. */
    synchronized void confirmar(List<ChangeEvent> confirmados) {
        for (ChangeEvent e : confirmados) {
            if (!conhecidos.containsKey(e)) {
                throw new ReplicacaoException(
                        "ack of an event not delivered by this partition (" + tp + "@" + e.eventoId() + ")", null);
            }
            maiorConfirmado = Math.max(maiorConfirmado, e.eventoId());
        }
        List<ChangeEvent> restantes = new ArrayList<>(entregues);
        Set<ChangeEvent> aConfirmar = Collections.newSetFromMap(new IdentityHashMap<>());
        aConfirmar.addAll(confirmados);
        restantes.removeIf(aConfirmar::contains);
        long alvo = maiorConfirmado + 1;
        for (ChangeEvent e : restantes) {
            alvo = Math.min(alvo, e.eventoId());
        }
        if (alvo > commitado) {
            confirmarAte(alvo);
        }
        entregues.clear();
        entregues.addAll(restantes);
        confirmados.forEach(conhecidos::remove);
    }

    private void confirmarAte(long offset) {
        consumer.commitSync(Map.of(tp, new OffsetAndMetadata(offset)));
        commitado = offset;
    }

    @Override
    public synchronized void close() {
        // A stopped partition thread is interrupted; close() would give up at once ("Failed to close fetcher").
        boolean interrompida = Thread.interrupted();
        try {
            consumer.close(Duration.ofSeconds(5));
        } finally {
            if (interrompida) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
