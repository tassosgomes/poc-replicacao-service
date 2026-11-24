package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.ecad.poc.replicacao.domain.replicacao.ChangeEvent;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Translator over the real messages captured from the lab connector (Debezium 3.7.1, Oracle Free 23ai,
 * {@code decimal.handling.mode=string}, {@code time.precision.mode=adaptive}), saved in
 * {@code src/test/resources/debezium/} as {@code {topic, partition, offset, timestamp, key, value}} (and
 * {@code multi-tabela.json} with a whole transaction). Default {@link FormatoDebezium} must handle them as is.
 */
class FixturesReaisDebeziumTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final TradutorDebezium tradutor = new TradutorDebezium(FormatoDebezium.padrao());

    private static JsonNode fixture(String nome) {
        try (var in = FixturesReaisDebeziumTest.class.getResourceAsStream("/debezium/" + nome + ".json")) {
            return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ChangeEvent traduzir(JsonNode registro) {
        String topico = registro.get("topic").asString();
        TableSpec spec = TradutorDebezium.tabela(topico.substring(topico.lastIndexOf('.') + 1));
        MensagemKafka m = new MensagemKafka(
                topico,
                registro.get("partition").intValue(),
                registro.get("offset").longValue(),
                registro.get("timestamp").longValue(),
                JSON.writeValueAsString(registro.get("value")));
        return tradutor.traduzir(spec, m).orElseThrow();
    }

    private ChangeEvent traduzir(String nome) {
        return traduzir(fixture(nome));
    }

    @Test
    void insertTitular_estadoVersaoEChaveStringParaLong() {
        ChangeEvent e = traduzir("insert-titular");

        assertThat(e.op()).isEqualTo('I');
        assertThat(e.codigo()).isEqualTo(900001L);
        assertThat(e.versao()).isEqualTo(Instant.parse("2026-11-20T21:06:24.613938Z"));
        assertThat(e.criadoEm()).isEqualTo(e.versao());
        assertThat(e.estado()).containsExactly("Titular KF", "PF", "123", "ATIVO", 0L);
    }

    @Test
    void updateTitular_usaAfterEmitaVersaoMaisNova() {
        ChangeEvent insert = traduzir("insert-titular");
        ChangeEvent update = traduzir("update-titular");

        assertThat(update.op()).isEqualTo('U');
        assertThat(update.versao()).isAfter(insert.versao());
        assertThat(update.estado().get(0)).isEqualTo("Titular KF 2");
    }

    @Test
    void updateExcluido_trazExcluidoUmEVersaoPropria() {
        ChangeEvent e = traduzir("update-excluido-titular");

        assertThat(e.estado().get(4)).isEqualTo(1L);
        assertThat(traduzir("update-excluido-fonograma").estado())
                .containsExactly(900001L, "Fono KF", "BR-AAA-26-00001", 215L, 1L);
    }

    @Test
    void deleteFisico_viraExcluidoUmComVersaoDepoisDaUltimaConhecida() {
        JsonNode registro = fixture("delete-titular");
        ChangeEvent e = traduzir(registro);

        assertThat(e.op()).isEqualTo('D');
        assertThat(e.codigo()).isEqualTo(900001L);
        assertThat(e.estado()).containsExactly("Titular KF 2", "PF", "123", "SUSPENSO", 1L);
        // ts_ms/commit_ts_ms have 1 s resolution and are truncated: here they are BEFORE the last updated_at
        // (…784.620694 vs …784.000), so the version is that updated_at + 1 us, strictly greater.
        Instant ultima = Instant.parse("2026-11-20T21:06:24.620694Z");
        assertThat(Instant.ofEpochMilli(
                        registro.get("value").get("source").get("commit_ts_ms").longValue()))
                .isBefore(ultima);
        assertThat(e.versao()).isEqualTo(ultima.plusNanos(1_000));
    }

    @Test
    void deleteObraEFonograma_traduzem() {
        assertThat(traduzir("delete-obra-musical").op()).isEqualTo('D');
        ChangeEvent f = traduzir("delete-fonograma");
        assertThat(f.estado().get(4)).isEqualTo(1L);
        assertThat(f.estado().get(0)).isInstanceOf(Long.class);
    }

    @Test
    void insertsEUpdateDeObraEFonograma_traduzemComEstadoCompleto() {
        for (String nome : List.of("insert-obra-musical", "update-obra-musical", "insert-fonograma")) {
            ChangeEvent e = traduzir(nome);
            TableSpec spec = TradutorDebezium.tabela(e.tabela());
            assertThat(e.temEstado()).as(nome).isTrue();
            assertThat(e.estado()).as(nome).hasSize(spec.colunas().size());
            assertThat(e.codigo()).as(nome).isPositive();
        }
    }

    @Test
    void multiTabela_todosOsEventosDaTransacaoTraduzem() {
        JsonNode multi = fixture("multi-tabela");
        List<ChangeEvent> eventos =
                multi.get("eventos").valueStream().map(this::traduzir).toList();

        assertThat(eventos).hasSize(3);
        assertThat(eventos)
                .extracting(ChangeEvent::tabela)
                .containsExactlyInAnyOrder("OBRA_MUSICAL", "FONOGRAMA", "FONOGRAMA");
        assertThat(eventos).allMatch(ChangeEvent::temEstado);
        // the fonogramas reference the obra of the same transaction
        long obra = eventos.stream()
                .filter(e -> e.tabela().equals("OBRA_MUSICAL"))
                .findFirst()
                .orElseThrow()
                .codigo();
        assertThat(eventos.stream().filter(e -> e.tabela().equals("FONOGRAMA")))
                .allMatch(e -> ((Long) e.estado().get(0)) == obra);
    }

    @Test
    void topicoDeTransacoes_naoEhDeTabela() {
        assertThat(fixture("tx-begin").get("topic").asString()).isEqualTo("kf.transaction");
        assertThat(fixture("tx-end").get("value").get("status").asString()).isEqualTo("END");
    }
}
