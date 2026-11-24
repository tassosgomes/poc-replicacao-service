package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.ecad.poc.replicacao.application.port.FonteTransacional.EventoTabela;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.FimTransacao;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.Ignorado;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.Registro;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Transaction identity over the real messages of one origin transaction (1 obra + 2 fonogramas). */
class TradutorTransacionalTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final TradutorTransacional tradutor =
            new TradutorTransacional(new TradutorDebezium(FormatoDebezium.padrao()));
    private final JsonNode tx = fixture();

    private static JsonNode fixture() {
        try (var in = TradutorTransacionalTest.class.getResourceAsStream("/debezium/multi-tabela.json")) {
            return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static MensagemKafka mensagem(JsonNode registro) {
        return new MensagemKafka(
                registro.get("topic").asString(),
                registro.get("partition").intValue(),
                registro.get("offset").longValue(),
                registro.get("timestamp").longValue(),
                JSON.writeValueAsString(registro.get("value")));
    }

    @Test
    void evento_trazOIdEAOrdemDaTransacao() {
        JsonNode primeiro = tx.get("eventos").get(0);
        String topico = primeiro.get("topic").asString();
        var spec = TradutorDebezium.tabela(topico.substring(topico.lastIndexOf('.') + 1));

        Registro r = tradutor.evento(spec, mensagem(primeiro));

        assertThat(r).isInstanceOfSatisfying(EventoTabela.class, e -> {
            assertThat(e.txId()).isEqualTo("080020005e020000");
            assertThat(e.ordemNaTransacao()).isEqualTo(1);
            assertThat(e.fluxo().topico()).isEqualTo(topico);
            assertThat(e.evento().codigo()).isEqualTo(900002L);
        });
    }

    @Test
    void marcador_endTrazTotalEContagemPorTabela() {
        Registro r = tradutor.marcador(mensagem(tx.get("end")));

        assertThat(r).isInstanceOfSatisfying(FimTransacao.class, f -> {
            assertThat(f.txId()).isEqualTo("080020005e020000");
            assertThat(f.eventos()).isEqualTo(3);
            assertThat(f.porTabela()).containsEntry("OBRA_MUSICAL", 1).containsEntry("FONOGRAMA", 2);
            assertThat(f.offset()).isEqualTo(23);
        });
    }

    @Test
    void marcador_beginEIgnorado() {
        assertThat(tradutor.marcador(mensagem(tx.get("begin")))).isInstanceOf(Ignorado.class);
    }

    @Test
    void marcador_tombstoneEIgnorado() {
        MensagemKafka m = new MensagemKafka("kf.transaction", 0, 5, 0, null);
        assertThat(tradutor.marcador(m)).isInstanceOf(Ignorado.class);
    }

    @Test
    void evento_semBlocoTransaction_vemSemTxId() {
        JsonNode primeiro = tx.get("eventos").get(0);
        ((tools.jackson.databind.node.ObjectNode) primeiro.get("value")).remove("transaction");
        var spec = TradutorDebezium.tabela("OBRA_MUSICAL");

        Registro r = tradutor.evento(spec, mensagem(primeiro));

        assertThat(r)
                .isInstanceOfSatisfying(
                        EventoTabela.class, e -> assertThat(e.txId()).isNull());
    }
}
