package br.org.ecad.poc.replicacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MainTest {

    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private static Function<String, String> lookupOf(Map<String, String> env) {
        return env::get;
    }

    @Test
    void dispatch_noArgs_returnsNonZeroWithUsage() {
        Main.Result result = Main.dispatch(new String[0], lookupOf(Map.of()), CLOCK);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.message()).contains("usage").contains("worker").contains("carga-inicial");
        assertThat(result.message()).contains("reconciliacao").contains("gerador");
    }

    @Test
    void dispatch_unknownSubcommand_returnsNonZeroOneLine() {
        Main.Result result = Main.dispatch(new String[] {"rebobinar"}, lookupOf(Map.of()), CLOCK);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.message()).contains("rebobinar").contains("usage");
        assertThat(result.message()).doesNotContain("\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"worker", "carga-inicial", "reconciliacao", "gerador"})
    void dispatch_knownStub_returnsNonZeroWithUsage(String command) {
        Main.Result result = Main.dispatch(new String[] {command}, lookupOf(Map.of()), CLOCK);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.message()).contains(command).contains("usage");
    }

    @Test
    void dispatch_invalidConfig_returnsNonZeroOneLine() {
        Map<String, String> env = new HashMap<>();
        env.put("LEGADO_URL", "   ");

        Main.Result result = Main.dispatch(new String[] {"worker"}, lookupOf(env), CLOCK);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.message()).contains("LEGADO_URL");
        assertThat(result.message()).doesNotContain("\n");
    }

    @Test
    void dispatch_lookupPrefersProvidedValue_overAmbient() {
        Map<String, String> env = new HashMap<>();
        env.put("LOTE_MAX", "not-a-number");

        assertThatThrownBy(() -> Config.load(lookupOf(env), CLOCK))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("LOTE_MAX");
    }
}
