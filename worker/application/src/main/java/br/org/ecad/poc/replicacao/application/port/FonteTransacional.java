package br.org.ecad.poc.replicacao.application.port;

import br.org.ecad.poc.replicacao.domain.replicacao.ChangeEvent;
import java.util.List;
import java.util.Map;

/**
 * Source of the transactional variant: one reader over every table partition and over the transaction
 * topic. Each {@link Fluxo} is delivered in offset order; there is no order between streams. Positions
 * are owned by the caller (kept in the destination, in the same transaction as the data): the source
 * never decides where to resume once a position is given.
 */
public interface FonteTransacional {

    /** A record read from a stream; {@code offset} is the position inside {@link #fluxo()}. */
    sealed interface Registro permits EventoTabela, FimTransacao, Ignorado {
        Fluxo fluxo();

        long offset();
    }

    /**
     * A change of a table. {@code txId} is null when the origin did not tag it with a transaction
     * (snapshot reads, for instance); {@code ordemNaTransacao} is Debezium's {@code total_order}.
     */
    record EventoTabela(Fluxo fluxo, long offset, String txId, long ordemNaTransacao, ChangeEvent evento)
            implements Registro {}

    /** The END marker of a transaction: how many events it has in total and per table. */
    record FimTransacao(Fluxo fluxo, long offset, String txId, int eventos, Map<String, Integer> porTabela)
            implements Registro {}

    /** A record with nothing to apply (BEGIN marker, tombstone, logical message). */
    record Ignorado(Fluxo fluxo, long offset) implements Registro {}

    /**
     * Opens every stream. A stream present in {@code salvas} resumes at that offset; the others start where the
     * source is configured to start. Returns the position of every stream.
     *
     * @param salvas next offset to read per stream, as persisted by the caller
     */
    Map<Fluxo, Long> iniciar(Map<Fluxo, Long> salvas);

    /** Reads what is available; empty when there is nothing within the poll timeout. */
    List<Registro> poll();

    /** Publishes positions to the broker for monitoring only (best effort); never used to resume. */
    void informar(Map<Fluxo, Long> posicoes);
}
