package br.org.ecad.poc.replicacao.application.port;

/**
 * Lease guard consulted inside a batch, right before each side effect. The destination confirmation
 * owns the commit boundary: a fenced implementation validates its token and commits in one database
 * call, while the free implementation delegates to the ordinary sink commit. A refusal aborts the
 * batch and leaves the origin events for the next owner.
 */
public interface Cerca {

    /** No lease: tests and tools that drive one partition alone. */
    Cerca LIVRE = new Cerca() {
        @Override
        public boolean podeConfirmarDestino() {
            return true;
        }

        @Override
        public boolean confirmarDestino(ReplicaSink sink) {
            sink.commit();
            return true;
        }

        @Override
        public boolean podeConfirmarOrigem() {
            return true;
        }
    };

    /**
     * Read-only pre-authorization before the destination commit. It must not retain a lease-row lock.
     */
    boolean podeConfirmarDestino();

    /** Atomically revalidates the lease and commits, or rolls the destination transaction back. */
    boolean confirmarDestino(ReplicaSink sink);

    /** Called before the origin ack. */
    boolean podeConfirmarOrigem();
}
