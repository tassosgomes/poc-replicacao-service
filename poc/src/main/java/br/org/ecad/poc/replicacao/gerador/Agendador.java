package br.org.ecad.poc.replicacao.gerador;

/**
 * Rate scheduler of one generator thread: row N of the thread is due at {@code inicio + N / taxa}.
 * Each thread owns one instance (target rate divided by {@code THREADS}), so the schedule and the
 * rows each thread picks only depend on the seed, never on thread interleaving.
 */
public final class Agendador {
    private final long inicioNanos;
    private final double nanosPorLinha;
    private long linhas;

    public Agendador(long inicioNanos, double linhasPorSegundo) {
        if (linhasPorSegundo <= 0) throw new IllegalArgumentException("taxa deve ser positiva");
        this.inicioNanos = inicioNanos;
        this.nanosPorLinha = 1_000_000_000.0 / linhasPorSegundo;
    }

    /** Reserves {@code tamanho} rows and returns the instant (nanoTime) when the transaction is due. */
    public long proximo(int tamanho) {
        long devido = inicioNanos + (long) (linhas * nanosPorLinha);
        linhas += tamanho;
        return devido;
    }
}
