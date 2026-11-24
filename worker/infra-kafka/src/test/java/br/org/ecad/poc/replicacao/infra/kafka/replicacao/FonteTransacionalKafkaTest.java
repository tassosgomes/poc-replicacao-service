package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.ecad.poc.replicacao.application.port.Fluxo;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.EventoTabela;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.FimTransacao;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.Ignorado;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.Registro;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class FonteTransacionalKafkaTest {

    private static final MapaTopicos TOPICOS = MapaTopicos.de("kf.BDAD", Map.of());
    private static final String TX = "kf.transaction";
    private static final TopicPartition OBRA = TOPICOS.particao("OBRA_MUSICAL", 2);
    private static final TopicPartition TX_TP = new TopicPartition(TX, 0);

    private static MockConsumer<String, String> consumidor() {
        MockConsumer<String, String> c = new MockConsumer<>("earliest");
        Map<TopicPartition, Long> inicio = new java.util.HashMap<>();
        Map<TopicPartition, Long> fim = new java.util.HashMap<>();
        for (TopicPartition tp : TOPICOS.todasAsParticoes()) {
            inicio.put(tp, 0L);
            fim.put(tp, 10L);
        }
        inicio.put(TX_TP, 0L);
        fim.put(TX_TP, 10L);
        c.updateBeginningOffsets(inicio);
        c.updateEndOffsets(fim);
        return c;
    }

    private static FonteTransacionalKafka fonte(MockConsumer<String, String> c, FonteTransacionalKafka.Inicio inicio) {
        return new FonteTransacionalKafka(
                c,
                TOPICOS,
                TX,
                new TradutorTransacional(new TradutorDebezium(FormatoDebezium.padrao())),
                Duration.ofMillis(10),
                inicio);
    }

    @Test
    void iniciar_semPosicaoSalva_comecaNoFim_eComPosicaoSalvaRetomaDela() {
        MockConsumer<String, String> c = consumidor();
        Fluxo salvo = new Fluxo(OBRA.topic(), OBRA.partition());

        Map<Fluxo, Long> posicoes = fonte(c, FonteTransacionalKafka.Inicio.FIM).iniciar(Map.of(salvo, 4L));

        assertThat(posicoes).hasSize(49).containsEntry(salvo, 4L).containsEntry(new Fluxo(TX, 0), 10L);
    }

    @Test
    void iniciar_inicioNoInicio_posicionaNoComecoDosTopicos() {
        MockConsumer<String, String> c = consumidor();

        Map<Fluxo, Long> posicoes =
                fonte(c, FonteTransacionalKafka.Inicio.INICIO).iniciar(Map.of());

        assertThat(posicoes.values()).containsOnly(0L);
    }

    @Test
    void poll_traduzEventoMarcadorEBegin() {
        MockConsumer<String, String> c = consumidor();
        FonteTransacionalKafka f = fonte(c, FonteTransacionalKafka.Inicio.INICIO);
        f.iniciar(Map.of());
        String evento =
                "{\"op\":\"c\",\"before\":null,\"after\":{\"CODIGO\":\"7\",\"TITULO\":\"t\",\"ISWC\":\"i\",\"GENERO\":\"g\","
                        + "\"SITUACAO\":\"A\",\"EXCLUIDO\":\"0\",\"UPDATED_AT\":1795208784651943},"
                        + "\"source\":{\"table\":\"OBRA_MUSICAL\",\"ts_ms\":1795208784000},\"transaction\":{\"id\":\"tx1\",\"total_order\":1}}";
        c.addRecord(new ConsumerRecord<>(OBRA.topic(), OBRA.partition(), 0L, "7", evento));
        c.addRecord(
                new ConsumerRecord<>(TX, 0, 0L, "tx1", "{\"status\":\"BEGIN\",\"id\":\"tx1\",\"event_count\":null}"));
        c.addRecord(
                new ConsumerRecord<>(
                        TX,
                        0,
                        1L,
                        "tx1",
                        "{\"status\":\"END\",\"id\":\"tx1\",\"event_count\":1,\"data_collections\":[{\"data_collection\":\"FREEPDB1.BDAD.OBRA_MUSICAL\",\"event_count\":1}]}"));

        List<Registro> lidos = f.poll();

        assertThat(lidos).hasSize(3);
        assertThat(lidos)
                .filteredOn(r -> r instanceof EventoTabela)
                .singleElement()
                .satisfies(r -> {
                    assertThat(((EventoTabela) r).txId()).isEqualTo("tx1");
                    assertThat(r.offset()).isZero();
                });
        assertThat(lidos).filteredOn(r -> r instanceof Ignorado).hasSize(1);
        assertThat(lidos)
                .filteredOn(r -> r instanceof FimTransacao)
                .singleElement()
                .satisfies(r -> assertThat(((FimTransacao) r).porTabela()).containsEntry("OBRA_MUSICAL", 1));
    }
}
