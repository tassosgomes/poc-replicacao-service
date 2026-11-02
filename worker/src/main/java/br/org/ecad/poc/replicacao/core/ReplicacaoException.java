package br.org.ecad.poc.replicacao.core;

/** Failure of a port (connection, SQL). Unchecked: the partition loop decides what to do. */
public class ReplicacaoException extends RuntimeException {

    public ReplicacaoException(String message, Throwable cause) {
        super(message, cause);
    }
}
