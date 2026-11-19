package br.org.ecad.poc.replicacao.domain.replicacao;

import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic UUIDv7 (RFC 9562): 48 bits of Unix milliseconds, version 7, a 12-bit counter in
 * {@code rand_a}, variant {@code 10} and 62 random bits. The (millisecond, counter) pair is a
 * single 60-bit state that only moves forward, so values are strictly increasing across threads
 * even inside one millisecond; when the counter is exhausted the logical millisecond advances.
 */
public final class Uuid7 {

    private static final int COUNTER_BITS = 12;
    private static final long VERSION_7 = 0x7000L;
    private static final long VARIANT_RFC_9562 = 0x8000000000000000L;
    private static final long RANDOM_62_BITS = 0x3FFFFFFFFFFFFFFFL;

    private final Clock clock;
    private final AtomicLong state = new AtomicLong();

    public Uuid7(Clock clock) {
        this.clock = clock;
    }

    public UUID next() {
        long fromClock = clock.millis() << COUNTER_BITS;
        long current = state.updateAndGet(previous -> Math.max(previous + 1, fromClock));
        long millis = current >>> COUNTER_BITS;
        long counter = current & ((1L << COUNTER_BITS) - 1);
        long mostSignificant = (millis << 16) | VERSION_7 | counter;
        long leastSignificant = (ThreadLocalRandom.current().nextLong() & RANDOM_62_BITS) | VARIANT_RFC_9562;
        return new UUID(mostSignificant, leastSignificant);
    }

    /** Big-endian bytes, the layout of a {@code RAW(16)} column that sorts like the UUID. */
    public static byte[] bytes(UUID uuid) {
        byte[] out = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (msb >>> (56 - 8 * i));
            out[8 + i] = (byte) (lsb >>> (56 - 8 * i));
        }
        return out;
    }
}
