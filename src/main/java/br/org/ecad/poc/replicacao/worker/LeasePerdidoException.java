package br.org.ecad.poc.replicacao.worker;

import br.org.ecad.poc.replicacao.core.ReplicacaoException;

/** The batch was aborted because the lease of its partition is no longer safe to work with. */
public final class LeasePerdidoException extends ReplicacaoException {

    public LeasePerdidoException(String message) {
        super(message, null);
    }
}
