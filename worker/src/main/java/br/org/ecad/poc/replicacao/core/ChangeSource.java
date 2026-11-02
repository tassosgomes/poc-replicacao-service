package br.org.ecad.poc.replicacao.core;

import java.util.List;

/**
 * Delivers the events of one (table, partition) in a stable order. Delivering does not remove:
 * only {@link #ack} of events already applied removes them, and unacknowledged events are
 * delivered again.
 */
public interface ChangeSource {

    List<ChangeEvent> poll(String tabela, int particao, int max);

    void ack(List<ChangeEvent> eventos);
}
