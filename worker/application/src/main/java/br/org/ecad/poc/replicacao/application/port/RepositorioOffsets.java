package br.org.ecad.poc.replicacao.application.port;

import java.util.Map;

/**
 * Positions of the source streams, stored in the destination. {@link #gravar} joins the open destination
 * transaction (the one of the data) and does not commit: data and positions become durable together.
 */
public interface RepositorioOffsets {

    /** Next offset to read per stream, as last committed. */
    Map<Fluxo, Long> carregar();

    void gravar(Map<Fluxo, Long> posicoes);
}
