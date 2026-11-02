package br.org.ecad.poc.replicacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MainPocTest {

    private static final Clock CLOCK = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);

    private static Function<String, String> lookupOf(Map<String, String> env) {
        return env::get;
    }

    @Test
    void dispatch_noArgs_returnsNonZeroWithUsage() {
        Main.Result result = MainPoc.dispatch(new String[0], lookupOf(Map.of()), CLOCK);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.message()).contains("usage").contains("worker").contains("carga-inicial");
        assertThat(result.message())
                .contains("reconciliacao")
                .contains("gerador")
                .contains("exportador");
    }

    @Test
    void dispatch_unknownSubcommand_returnsNonZeroOneLine() {
        Main.Result result = MainPoc.dispatch(new String[] {"rebobinar"}, lookupOf(Map.of()), CLOCK);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.message()).contains("rebobinar").contains("usage");
        assertThat(result.message()).doesNotContain("\n");
    }

    @Test
    void dispatch_cargaInicial_returnsConfig() {
        Main.Result result = MainPoc.dispatch(new String[] {"carga-inicial"}, lookupOf(Map.of()), CLOCK);
        assertThat(result.exitCode()).isZero();
        assertThat(result.config()).isNotNull();
    }

    @Test
    void dispatch_worker_delegatesToTheWorkerModule() {
        Main.Result result = MainPoc.dispatch(new String[] {"worker"}, lookupOf(Map.of()), CLOCK);
        Main.Result direct = Main.dispatch(new String[] {"worker"}, lookupOf(Map.of()), CLOCK);

        assertThat(result.exitCode()).isZero();
        assertThat(result.config()).isNotNull();
        assertThat(result.config().loteMax()).isEqualTo(500);
        assertThat(result.message()).isEqualTo(direct.message());
    }

    @Test
    void dispatch_exportadorWithExtraArgs_returnsUsage() {
        Main.Result result = MainPoc.dispatch(new String[] {"exportador", "x"}, lookupOf(Map.of()), CLOCK);

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.message()).contains("usage");
    }

    @Test
    void dispatch_invalidConfig_returnsNonZeroOneLine() {
        Map<String, String> env = new HashMap<>();
        env.put("LEGADO_URL", "   ");

        Main.Result result = MainPoc.dispatch(new String[] {"gerador"}, lookupOf(env), CLOCK);

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.message()).contains("LEGADO_URL");
        assertThat(result.message()).doesNotContain("\n");
    }
}
