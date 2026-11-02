package br.org.ecad.poc.replicacao.worker;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.ecad.poc.replicacao.Config;
import br.org.ecad.poc.replicacao.core.ChangeEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V11EvidenciasTest {
    @TempDir
    Path directory;

    @Test
    void registrar_preservesSlowEventsAndMembershipForScenarioPercentiles() throws Exception {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Metricas metrics = new Metricas(directory, "v11", Clock.fixed(created, ZoneOffset.UTC), true);
        metrics.registrar(
                List.of(ChangeEvent.soChave(42, "TITULAR", 1, 'U', created)),
                created.plusSeconds(9),
                1,
                0,
                new long[4]);
        assertThat(Files.readString(directory.resolve("eventos-v11.csv")))
                .contains("evento_id,tabela,codigo,criado_em,confirmado_em,latencia_ms")
                .contains("42,TITULAR,1,2026-01-01T00:00:00Z,2026-01-01T00:00:09Z,9000");
    }

    @Test
    void config_eventEvidenceIsOptInAndUsesInjectedLookup() {
        Clock clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        assertThat(Config.load(key -> null, clock).eventosCsv()).isFalse();
        assertThat(Config.load(key -> key.equals("EVENTOS_CSV") ? "true" : null, clock)
                        .eventosCsv())
                .isTrue();
    }
}
