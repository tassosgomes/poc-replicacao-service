package br.org.ecad.poc.replicacao.bootstrap.observabilidade;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class V05MetricasTest {
    @Test
    void skew_midpoint_invalidatesOffset() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Metricas.Skew result = Metricas.Skew.calcular(now, now.minusMillis(490), now.plusMillis(20));
        assertThat(result.ms()).isEqualTo(500);
        assertThat(result.incertezaMs()).isEqualTo(10);
        assertThat(result.valido()).isFalse();
    }

    @Test
    void percentile_emptyAndNearestRank() {
        assertThat(Metricas.percentil(List.of(), .95)).isZero();
        assertThat(Metricas.percentil(List.of(1L, 2L, 3L, 4L), .50)).isEqualTo(2);
        assertThat(Metricas.percentil(List.of(1L, 2L, 3L, 4L), .95)).isEqualTo(4);
    }
}
