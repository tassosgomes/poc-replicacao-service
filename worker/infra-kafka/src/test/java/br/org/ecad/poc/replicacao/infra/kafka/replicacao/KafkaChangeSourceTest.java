package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.org.ecad.poc.replicacao.domain.replicacao.ChangeEvent;
import br.org.ecad.poc.replicacao.domain.replicacao.Esquema;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class KafkaChangeSourceTest {

    private static final MapaTopicos TOPICOS = MapaTopicos.de("kf.BDAD", Map.of());
    private static final TopicPartition TP = TOPICOS.particao("TITULAR", 3);

    private final List<MockConsumer<String, String>> abertos = new ArrayList<>();

    /**
     * MockConsumer forgets the commits when {@code assign} is called again (a real broker does not), so the
     * commits are kept here.
     */
    private static final class ConsumidorFalso extends MockConsumer<String, String> {
        private final Map<TopicPartition, OffsetAndMetadata> commits = new java.util.HashMap<>();

        ConsumidorFalso() {
            super("earliest");
        }

        @Override
        public synchronized void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            super.commitSync(offsets);
            commits.putAll(offsets);
        }

        @Override
        public synchronized Map<TopicPartition, OffsetAndMetadata> committed(Set<TopicPartition> particoes) {
            Map<TopicPartition, OffsetAndMetadata> r = new java.util.HashMap<>();
            particoes.stream().filter(commits::containsKey).forEach(tp -> r.put(tp, commits.get(tp)));
            return r;
        }

        @Override
        public synchronized Map<TopicPartition, OffsetAndMetadata> committed(
                Set<TopicPartition> particoes, Duration timeout) {
            return committed(particoes);
        }
    }

    private static String envelope(String op, long codigo, String nome, long updatedAtMicros) {
        String linha = "{\"CODIGO\":" + codigo + ",\"NOME\":\"" + nome
                + "\",\"TIPO\":\"PF\",\"DOCUMENTO\":\"1\",\"SITUACAO\":\"A\",\"EXCLUIDO\":0,\"UPDATED_AT\":"
                + updatedAtMicros + "}";
        return "{\"before\":null,\"after\":" + linha + ",\"source\":{\"table\":\"TITULAR\",\"ts_ms\":1795176001000},"
                + "\"op\":\"" + op + "\"}";
    }

    private static ConsumerRecord<String, String> registro(long offset, String valor) {
        return new ConsumerRecord<>(TP.topic(), TP.partition(), offset, "{\"CODIGO\":" + offset + "}", valor);
    }

    /** A source whose consumers are mocks pre-loaded with {@code n} events and, optionally, a committed offset. */
    private KafkaChangeSource fonte(int n, Long commitado) {
        return new KafkaChangeSource(
                tp -> {
                    ConsumidorFalso mock = new ConsumidorFalso();
                    mock.assign(Set.of(tp));
                    mock.updateBeginningOffsets(Map.of(tp, 0L));
                    mock.updateEndOffsets(Map.of(tp, (long) n));
                    if (commitado != null) {
                        mock.commitSync(Map.of(tp, new OffsetAndMetadata(commitado)));
                    }
                    for (int i = 0; i < n; i++) {
                        mock.addRecord(registro(i, envelope("u", 100 + i, "n" + i, 1_795_176_000_000_000L + i)));
                    }
                    abertos.add(mock);
                    return mock;
                },
                TOPICOS,
                new TradutorDebezium(FormatoDebezium.padrao()),
                Duration.ofMillis(10));
    }

    private static List<Long> offsets(List<ChangeEvent> eventos) {
        return eventos.stream().map(ChangeEvent::eventoId).toList();
    }

    private long commitado(MockConsumer<String, String> mock) {
        OffsetAndMetadata o = mock.committed(Set.of(TP)).get(TP);
        return o == null ? -1 : o.offset();
    }

    @Test
    void poll_devolveNoMaximoMaxEmOrdem_eGuardaOExcedente() {
        try (KafkaChangeSource fonte = fonte(5, null)) {
            List<ChangeEvent> primeiro = fonte.poll("TITULAR", 3, 2);
            assertThat(offsets(primeiro)).containsExactly(0L, 1L);
            fonte.ack(primeiro);

            // the consumer already returned all 5 records; the surplus comes from the buffer
            List<ChangeEvent> segundo = fonte.poll("TITULAR", 3, 2);
            assertThat(offsets(segundo)).containsExactly(2L, 3L);
            fonte.ack(segundo);
            assertThat(offsets(fonte.poll("TITULAR", 3, 2))).containsExactly(4L);
        }
    }

    @Test
    void poll_semAck_redeliveraOsMesmosEventos() {
        try (KafkaChangeSource fonte = fonte(5, null)) {
            assertThat(offsets(fonte.poll("TITULAR", 3, 3))).containsExactly(0L, 1L, 2L);
            assertThat(offsets(fonte.poll("TITULAR", 3, 3))).containsExactly(0L, 1L, 2L);
            // a smaller max after a redelivery keeps the order and the rest
            assertThat(offsets(fonte.poll("TITULAR", 3, 2))).containsExactly(0L, 1L);
            assertThat(offsets(fonte.poll("TITULAR", 3, 5))).containsExactly(0L, 1L, 2L, 3L, 4L);
        }
    }

    @Test
    void ack_commitaMaiorOffsetMaisUm_eSoDepoisDoAck() {
        try (KafkaChangeSource fonte = fonte(5, null)) {
            List<ChangeEvent> lote = fonte.poll("TITULAR", 3, 3);
            assertThat(commitado(abertos.get(0))).isEqualTo(-1);

            fonte.ack(lote);

            assertThat(commitado(abertos.get(0))).isEqualTo(3);
        }
    }

    @Test
    void ack_parcial_naoCommitaAlemDeEventoEntregueENaoConfirmado() {
        try (KafkaChangeSource fonte = fonte(5, null)) {
            List<ChangeEvent> lote = fonte.poll("TITULAR", 3, 3);

            fonte.ack(List.of(lote.get(0), lote.get(2)));
            assertThat(commitado(abertos.get(0))).isEqualTo(1);

            fonte.ack(List.of(lote.get(1)));
            assertThat(commitado(abertos.get(0))).isEqualTo(3);
        }
    }

    @Test
    void reabrir_posicionaNoOffsetCommitado() {
        try (KafkaChangeSource fonte = fonte(5, 2L)) {
            assertThat(offsets(fonte.poll("TITULAR", 3, 10))).containsExactly(2L, 3L, 4L);
        }
    }

    @Test
    void reabrirSemCommit_comecaNoInicio() {
        try (KafkaChangeSource fonte = fonte(2, null)) {
            assertThat(offsets(fonte.poll("TITULAR", 3, 10))).containsExactly(0L, 1L);
        }
    }

    @Test
    void ack_deEventoNaoEntregue_falha() {
        try (KafkaChangeSource fonte = fonte(1, null)) {
            ChangeEvent estranho = ChangeEvent.soChave(9, "TITULAR", 1, 'I', java.time.Instant.EPOCH);

            assertThatThrownBy(() -> fonte.ack(List.of(estranho))).isInstanceOf(ReplicacaoException.class);
        }
    }

    @Test
    void poll_comMensagemIntraduzivel_descartaOConsumidorEReabreNoCommitado() {
        int[] aberturas = {0};
        try (KafkaChangeSource fonte = new KafkaChangeSource(
                tp -> {
                    aberturas[0]++;
                    ConsumidorFalso mock = new ConsumidorFalso();
                    mock.assign(Set.of(tp));
                    mock.updateBeginningOffsets(Map.of(tp, 0L));
                    mock.addRecord(registro(0, envelope("c", 1, "a", 1_795_176_000_000_000L)));
                    mock.addRecord(registro(1, "{\"op\":\"t\"}"));
                    abertos.add(mock);
                    return mock;
                },
                TOPICOS,
                new TradutorDebezium(FormatoDebezium.padrao()),
                Duration.ofMillis(10))) {

            assertThatThrownBy(() -> fonte.poll("TITULAR", 3, 10)).isInstanceOf(ReplicacaoException.class);
            assertThat(abertos.get(0).closed()).isTrue();
            // the first record was fetched but not delivered: it is read again, never skipped
            assertThatThrownBy(() -> fonte.poll("TITULAR", 3, 10)).isInstanceOf(ReplicacaoException.class);
            assertThat(aberturas[0]).isEqualTo(2);
        }
    }

    @Test
    void poll_tombstonesAoFinal_saoPuladosECommitados() {
        try (KafkaChangeSource fonte = new KafkaChangeSource(
                tp -> {
                    ConsumidorFalso mock = new ConsumidorFalso();
                    mock.assign(Set.of(tp));
                    mock.updateBeginningOffsets(Map.of(tp, 0L));
                    mock.addRecord(registro(0, envelope("c", 1, "a", 1_795_176_000_000_000L)));
                    mock.addRecord(registro(1, null));
                    abertos.add(mock);
                    return mock;
                },
                TOPICOS,
                new TradutorDebezium(FormatoDebezium.padrao()),
                Duration.ofMillis(10))) {
            List<ChangeEvent> lote = fonte.poll("TITULAR", 3, 10);
            assertThat(offsets(lote)).containsExactly(0L);
            fonte.ack(lote);
            assertThat(commitado(abertos.get(0))).isEqualTo(1);

            // nothing pending and the tail is a tombstone: skipped and committed, so the lag reaches zero
            assertThat(fonte.poll("TITULAR", 3, 10)).isEmpty();
            assertThat(commitado(abertos.get(0))).isEqualTo(2);
        }
    }

    @Test
    void lag_somaFimMenosCommitadoDeTodasAsParticoes() {
        ConsumidorFalso mock = new ConsumidorFalso();
        List<TopicPartition> todas = TOPICOS.todasAsParticoes();
        assertThat(todas).hasSize(Esquema.TABELAS.size() * Esquema.PARTICOES);
        mock.assign(todas);
        Map<TopicPartition, Long> inicio = new java.util.HashMap<>();
        Map<TopicPartition, Long> fim = new java.util.HashMap<>();
        todas.forEach(tp -> {
            inicio.put(tp, 0L);
            fim.put(tp, 0L);
        });
        fim.put(TP, 10L);
        fim.put(TOPICOS.particao("FONOGRAMA", 0), 4L);
        mock.updateBeginningOffsets(inicio);
        mock.updateEndOffsets(fim);
        mock.commitSync(Map.of(TP, new OffsetAndMetadata(7)));

        LagConsumidor lag = new LagConsumidor(mock, TOPICOS);

        assertThat(lag.porParticao().get(TP)).isEqualTo(3L);
        // no commit yet on FONOGRAMA/0: the lag runs from the beginning offset
        assertThat(lag.total()).isEqualTo(3L + 4L);
    }

    @Test
    void mapaTopicos_prefixoEExcecoes() {
        MapaTopicos mapa = MapaTopicos.de("kf.BDAD", Map.of("titular", "outro.topico"));

        assertThat(mapa.topico("TITULAR")).isEqualTo("outro.topico");
        assertThat(mapa.topico("FONOGRAMA")).isEqualTo("kf.BDAD.FONOGRAMA");
        assertThatThrownBy(() -> MapaTopicos.de("p", Map.of("NADA", "x"))).isInstanceOf(IllegalArgumentException.class);
    }
}
