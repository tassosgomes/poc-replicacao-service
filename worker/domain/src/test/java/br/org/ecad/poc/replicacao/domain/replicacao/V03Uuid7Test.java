package br.org.ecad.poc.replicacao.domain.replicacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class V03Uuid7Test {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void next_returnsVersion7WithRfc9562VariantAndClockMillis() {
        UUID uuid = new Uuid7(CLOCK).next();

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);
        assertThat(uuid.getMostSignificantBits() >>> 16).isEqualTo(CLOCK.millis());
    }

    @Test
    void next_oneMillionValues_areStrictlyIncreasingEvenWithFrozenClock() {
        Uuid7 gerador = new Uuid7(CLOCK);
        UUID anterior = gerador.next();

        for (int i = 1; i < 1_000_000; i++) {
            UUID atual = gerador.next();
            if (compare(anterior, atual) >= 0) {
                throw new AssertionError("not increasing at " + i + ": " + anterior + " >= " + atual);
            }
            anterior = atual;
        }
    }

    @Test
    void next_manyThreads_neverRepeatsAndEachThreadSeesIncreasingValues() throws Exception {
        Uuid7 gerador = new Uuid7(CLOCK);
        int threads = 8;
        int porThread = 125_000;
        List<UUID> todos = new ArrayList<>(threads * porThread);
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<List<UUID>>> futuros = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Callable<List<UUID>> tarefa = () -> {
                    List<UUID> gerados = new ArrayList<>(porThread);
                    for (int i = 0; i < porThread; i++) {
                        gerados.add(gerador.next());
                    }
                    return gerados;
                };
                futuros.add(pool.submit(tarefa));
            }
            for (Future<List<UUID>> futuro : futuros) {
                List<UUID> gerados = futuro.get();
                for (int i = 1; i < gerados.size(); i++) {
                    assertThat(compare(gerados.get(i - 1), gerados.get(i))).isNegative();
                }
                todos.addAll(gerados);
            }
        }

        assertThat(todos).doesNotHaveDuplicates().hasSize(threads * porThread);
    }

    private static int compare(UUID a, UUID b) {
        int byMostSignificant = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return byMostSignificant != 0
                ? byMostSignificant
                : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
