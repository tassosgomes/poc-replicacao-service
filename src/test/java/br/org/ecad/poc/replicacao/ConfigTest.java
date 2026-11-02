package br.org.ecad.poc.replicacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ConfigTest {

    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private static Function<String, String> lookupOf(Map<String, String> values) {
        return values::get;
    }

    @Test
    void load_absentKeys_usesDocumentedDefaults() {
        Config config = Config.load(lookupOf(Map.of()), CLOCK);

        assertThat(config.legadoUrl()).isEqualTo("jdbc:oracle:thin:@//localhost:1521/FREEPDB1");
        assertThat(config.novoUrl()).isEqualTo("jdbc:oracle:thin:@//localhost:1522/FREEPDB1");
        assertThat(config.loteMax()).isEqualTo(500);
        assertThat(config.pollMs()).isEqualTo(200);
        assertThat(config.leaseSeg()).isEqualTo(15);
        assertThat(config.virtualThreads()).isTrue();
        assertThat(config.resultadosDir()).isEqualTo("resultados");
        assertThat(config.workerId()).isNotBlank();
    }

    @Test
    void load_providedValues_overrideDefaults() {
        Config config = Config.load(
                lookupOf(Map.of(
                        "LEGADO_URL",
                        "jdbc:oracle:thin:@//db:1521/XEPDB1",
                        "LOTE_MAX",
                        "100",
                        "VIRTUAL_THREADS",
                        "false",
                        "WORKER_ID",
                        "w1")),
                CLOCK);

        assertThat(config.legadoUrl()).isEqualTo("jdbc:oracle:thin:@//db:1521/XEPDB1");
        assertThat(config.loteMax()).isEqualTo(100);
        assertThat(config.virtualThreads()).isFalse();
        assertThat(config.workerId()).isEqualTo("w1");
    }

    @Test
    void load_blankValue_isInvalidWithOneLineMessage() {
        assertThatThrownBy(() -> Config.load(lookupOf(Map.of("NOVO_URL", "")), CLOCK))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("NOVO_URL")
                .hasMessageMatching("[^\\n\\r]+");
    }

    @Test
    void load_malformedNumber_isInvalid() {
        assertThatThrownBy(() -> Config.load(lookupOf(Map.of("POLL_MS", "soon")), CLOCK))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("POLL_MS");
    }

    @Test
    void load_outOfRangeNumber_isInvalid() {
        assertThatThrownBy(() -> Config.load(lookupOf(Map.of("LOTE_MAX", "0")), CLOCK))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("LOTE_MAX");
    }

    @Test
    void load_malformedBoolean_isInvalid() {
        assertThatThrownBy(() -> Config.load(lookupOf(Map.of("VIRTUAL_THREADS", "yes")), CLOCK))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("VIRTUAL_THREADS");
    }
}
