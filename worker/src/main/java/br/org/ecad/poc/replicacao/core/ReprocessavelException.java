package br.org.ecad.poc.replicacao.core;

/** The batch lost a race with another writer of the same row; reprocessing it is safe. */
public final class ReprocessavelException extends ReplicacaoException {

    public ReprocessavelException(String message, Throwable cause) {
        super(message, cause);
    }
}
