package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.org.ecad.poc.replicacao.domain.replicacao.ChangeEvent;
import br.org.ecad.poc.replicacao.domain.replicacao.Esquema;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TradutorDebeziumTest {

    private final TradutorDebezium tradutor = new TradutorDebezium(FormatoDebezium.padrao());

    static String exemplo(String nome) {
        try (var in = TradutorDebeziumTest.class.getResourceAsStream("/exemplos/" + nome + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<ChangeEvent> traduzir(br.org.ecad.poc.replicacao.domain.replicacao.TableSpec spec, String json) {
        return tradutor.traduzir(spec, new MensagemKafka("kf.BDAD." + spec.nome(), 3, 17, 1_000L, json));
    }

    @Test
    void traduzir_insert_carregaEstadoEVersao() {
        ChangeEvent e = traduzir(Esquema.TITULAR, exemplo("titular-insert")).orElseThrow();

        assertThat(e.op()).isEqualTo('I');
        assertThat(e.tabela()).isEqualTo("TITULAR");
        assertThat(e.codigo()).isEqualTo(42L);
        assertThat(e.eventoId()).isEqualTo(17L);
        assertThat(e.versao()).isEqualTo(Instant.parse("2026-11-20T12:00:00.123456Z"));
        assertThat(e.estado()).containsExactly("Ana", "PF", "12345678901", "ATIVO", 0L);
        // DML time of the origin trigger, not the 1 s resolution ts_ms
        assertThat(e.criadoEm()).isEqualTo(e.versao());
        assertThat(e.temEstado()).isTrue();
    }

    @Test
    void traduzir_update_usaAfter() {
        ChangeEvent e = traduzir(Esquema.TITULAR, exemplo("titular-update")).orElseThrow();

        assertThat(e.op()).isEqualTo('U');
        assertThat(e.estado().get(0)).isEqualTo("Ana Maria");
        assertThat(e.versao()).isEqualTo(Instant.parse("2026-11-20T12:00:05.500000Z"));
    }

    @Test
    void traduzir_snapshotRead_viraInsert() {
        ChangeEvent e =
                traduzir(Esquema.TITULAR, exemplo("titular-snapshot-read")).orElseThrow();

        assertThat(e.op()).isEqualTo('I');
        assertThat(e.codigo()).isEqualTo(7L);
    }

    @Test
    void traduzir_deleteFisico_marcaExcluidoComVersaoDoCommit() {
        ChangeEvent e =
                traduzir(Esquema.TITULAR, exemplo("titular-delete-fisico")).orElseThrow();

        assertThat(e.op()).isEqualTo('D');
        assertThat(e.estado()).containsExactly("Ana Maria", "PF", "12345678901", "ATIVO", 1L);
        // source.ts_ms (12:00:10) is later than the last known updated_at (12:00:05.5)
        assertThat(e.versao()).isEqualTo(Instant.ofEpochMilli(1795176010000L));
        assertThat(e.versao()).isAfter(Instant.parse("2026-11-20T12:00:05.500000Z"));
    }

    @Test
    void traduzir_deleteComClockAtrasado_forcaVersaoEstritamenteMaior() {
        String json = exemplo("titular-delete-fisico")
                .replace("\"ts_ms\": 1795176010000", "\"ts_ms\": 1795176000000")
                .replace("\"ts_ms\": 1795176010040", "\"ts_ms\": 1795176000040");

        ChangeEvent e = traduzir(Esquema.TITULAR, json).orElseThrow();

        assertThat(e.versao()).isEqualTo(Instant.parse("2026-11-20T12:00:05.500001Z"));
    }

    @Test
    void traduzir_deleteSemSupplementalLogging_falhaAltoEmVezDeGravarNulos() {
        assertThatThrownBy(() -> traduzir(Esquema.TITULAR, exemplo("titular-delete-sem-before-completo")))
                .isInstanceOf(ReplicacaoException.class)
                .hasMessageContaining("supplemental");
    }

    @Test
    void traduzir_tombstone_ignorado() {
        assertThat(tradutor.traduzir(Esquema.TITULAR, new MensagemKafka("t", 0, 5, 0, null)))
                .isEmpty();
    }

    @Test
    void traduzir_decimalVariavelEmColunaNumerica_viraLong() {
        ChangeEvent e = traduzir(Esquema.FONOGRAMA, exemplo("fonograma-insert-decimal-struct"))
                .orElseThrow();

        assertThat(e.codigo()).isEqualTo(900L);
        assertThat(e.estado()).containsExactly(55L, "Faixa 1", "BRABC2600001", 180L, 0L);
        assertThat(e.versao()).isEqualTo(Instant.parse("2026-11-20T12:00:02.000001Z"));
    }

    @Test
    void traduzir_tabelaDiferenteNoTopico_falha() {
        assertThatThrownBy(() -> traduzir(Esquema.OBRA_MUSICAL, exemplo("titular-insert")))
                .isInstanceOf(ReplicacaoException.class)
                .hasMessageContaining("TITULAR");
    }

    @Test
    void traduzir_semOpOuJsonInvalido_falha() {
        assertThatThrownBy(() -> traduzir(Esquema.TITULAR, "{\"after\":{}}")).isInstanceOf(ReplicacaoException.class);
        assertThatThrownBy(() -> traduzir(Esquema.TITULAR, "nao eh json")).isInstanceOf(ReplicacaoException.class);
        assertThatThrownBy(() -> traduzir(Esquema.TITULAR, "{\"op\":\"t\"}"))
                .isInstanceOf(ReplicacaoException.class)
                .hasMessageContaining("unsupported op");
    }

    @Test
    void traduzir_envelopeComPayloadESchema_eAceito() {
        String json = "{\"schema\":{},\"payload\":" + exemplo("titular-insert") + "}";

        assertThat(traduzir(Esquema.TITULAR, json)).isPresent();
    }

    @Test
    void traduzir_temporaisEmTextoIsoENumericosEmTexto_saoAceitos() {
        String json = """
                {"op":"c","source":{"table":"titular","ts_ms":1795176001000},
                 "after":{"codigo":"5","nome":"X","tipo":"PJ","documento":null,"situacao":"A","excluido":"0",
                          "updated_at":"2026-11-20T12:00:00.5Z"}}""";

        ChangeEvent e = traduzir(Esquema.TITULAR, json).orElseThrow();

        assertThat(e.codigo()).isEqualTo(5L);
        assertThat(e.estado()).containsExactly("X", "PJ", null, "A", 0L);
        assertThat(e.versao()).isEqualTo(Instant.parse("2026-11-20T12:00:00.500Z"));
    }

    @Test
    void traduzir_unidadeTemporalExplicita_eRespeitada() {
        var ms = new TradutorDebezium(
                new FormatoDebezium(FormatoDebezium.UnidadeTemporal.MILLIS, Set.of("codigo", "excluido"), 0));
        String json =
                "{\"op\":\"u\",\"after\":{\"CODIGO\":1,\"NOME\":\"a\",\"TIPO\":\"b\",\"DOCUMENTO\":\"c\",\"SITUACAO\":\"d\",\"EXCLUIDO\":0,\"UPDATED_AT\":1795176000123}}";

        ChangeEvent e = ms.traduzir(Esquema.TITULAR, new MensagemKafka("t", 0, 1, 7_000L, json))
                .orElseThrow();

        assertThat(e.versao()).isEqualTo(Instant.ofEpochMilli(1795176000123L));
        assertThat(e.criadoEm()).isEqualTo(e.versao());
    }

    @Test
    void epoch_autoDetectaMilisMicrosENanos() {
        var valores = new ValoresDebezium(new FormatoDebezium(FormatoDebezium.UnidadeTemporal.AUTO, Set.of(), 0));
        Instant esperado = Instant.parse("2026-11-20T12:00:00Z");

        assertThat(valores.epoch(esperado.toEpochMilli())).isEqualTo(esperado);
        assertThat(valores.epoch(esperado.toEpochMilli() * 1_000)).isEqualTo(esperado);
        assertThat(valores.epoch(esperado.toEpochMilli() * 1_000_000)).isEqualTo(esperado);
    }
}
