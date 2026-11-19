package br.org.ecad.poc.replicacao.domain.replicacao;

/** Failure of a port (connection, SQL). Unchecked: the partition loop decides what to do. */
public class ReplicacaoException extends RuntimeException {

    public ReplicacaoException(String message, Throwable cause) {
        super(message, cause);
    }
}
