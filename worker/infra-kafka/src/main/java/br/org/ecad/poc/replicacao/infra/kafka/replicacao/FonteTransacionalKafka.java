package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

import br.org.ecad.poc.replicacao.application.port.Fluxo;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional;
import br.org.ecad.poc.replicacao.domain.replicacao.Esquema;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

/**
 * {@link FonteTransacional} over a single consumer with manual {@code assign()} of every table partition
 * (3 tables x 16) and of the transaction topic. The consumer's own committed offsets are never read: the
 * position of every stream comes from the caller and {@link #informar} only publishes it for monitoring.
 * Not thread-safe: one thread drives it.
 *
 * <p>Failure: a record that cannot be translated, or an offset out of range, makes {@link #poll} throw;
 * the caller closes the source and starts over from the persisted positions.
 */
public final class FonteTransacionalKafka implements FonteTransacional, AutoCloseable {

    /** Where a stream without a persisted position starts. */
    public enum Inicio {
        /** The current end: correct when the origin is quiet and the previous mode drained everything. */
        FIM,
        /** The beginning of the topic: replays history (idempotent, slow). */
        INICIO
    }

    private final Consumer<String, String> consumer;
    private final TradutorTransacional tradutor;
    private final Duration pollTimeout;
    private final Inicio inicio;
    private final String topicoTransacoes;
    private final Map<String, TableSpec> tabelasPorTopico = new HashMap<>();
    private final Set<TopicPartition> todas = new LinkedHashSet<>();

    public FonteTransacionalKafka(
            Consumer<String, String> consumer,
            MapaTopicos topicos,
            String topicoTransacoes,
            TradutorTransacional tradutor,
            Duration pollTimeout,
            Inicio inicio) {
        this.consumer = consumer;
        this.tradutor = tradutor;
        this.pollTimeout = pollTimeout;
        this.inicio = inicio;
        this.topicoTransacoes = topicoTransacoes;
        for (TableSpec spec : Esquema.TABELAS) {
            tabelasPorTopico.put(topicos.topico(spec.nome()), spec);
        }
        todas.addAll(topicos.todasAsParticoes());
        todas.add(new TopicPartition(topicoTransacoes, 0));
    }

    /** A source over a real {@link KafkaConsumer} built from the given properties. */
    public static FonteTransacionalKafka abrir(
            Map<String, Object> props,
            String clientId,
            MapaTopicos topicos,
            String topicoTransacoes,
            TradutorTransacional tradutor,
            Duration pollTimeout,
            Inicio inicio) {
        Map<String, Object> copia = new HashMap<>(props);
        copia.put(org.apache.kafka.clients.consumer.ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        return new FonteTransacionalKafka(
                new KafkaConsumer<>(copia), topicos, topicoTransacoes, tradutor, pollTimeout, inicio);
    }

    @Override
    public Map<Fluxo, Long> iniciar(Map<Fluxo, Long> salvas) {
        consumer.assign(todas);
        Set<TopicPartition> semPosicao = new LinkedHashSet<>();
        for (TopicPartition tp : todas) {
            Long salva = salvas.get(new Fluxo(tp.topic(), tp.partition()));
            if (salva == null) {
                semPosicao.add(tp);
            } else {
                consumer.seek(tp, salva);
            }
        }
        if (!semPosicao.isEmpty()) {
            Map<TopicPartition, Long> alvo =
                    inicio == Inicio.FIM ? consumer.endOffsets(semPosicao) : consumer.beginningOffsets(semPosicao);
            alvo.forEach(consumer::seek);
        }
        Map<Fluxo, Long> posicoes = new HashMap<>();
        for (TopicPartition tp : todas) {
            posicoes.put(new Fluxo(tp.topic(), tp.partition()), consumer.position(tp));
        }
        return posicoes;
    }

    @Override
    public List<Registro> poll() {
        List<Registro> lidos = new ArrayList<>();
        for (ConsumerRecord<String, String> r : consumer.poll(pollTimeout)) {
            MensagemKafka m = new MensagemKafka(r.topic(), r.partition(), r.offset(), r.timestamp(), r.value());
            if (r.topic().equals(topicoTransacoes)) {
                lidos.add(tradutor.marcador(m));
                continue;
            }
            TableSpec spec = tabelasPorTopico.get(r.topic());
            if (spec == null) {
                throw new ReplicacaoException("record of an unexpected topic " + r.topic(), null);
            }
            lidos.add(tradutor.evento(spec, m));
        }
        return lidos;
    }

    @Override
    public void informar(Map<Fluxo, Long> posicoes) {
        if (posicoes.isEmpty()) {
            return;
        }
        Map<TopicPartition, OffsetAndMetadata> mapa = new HashMap<>();
        posicoes.forEach((f, o) -> mapa.put(new TopicPartition(f.topico(), f.particao()), new OffsetAndMetadata(o)));
        consumer.commitSync(mapa);
    }

    @Override
    public void close() {
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
