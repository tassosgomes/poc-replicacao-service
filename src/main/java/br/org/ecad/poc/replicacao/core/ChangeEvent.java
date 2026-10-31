package br.org.ecad.poc.replicacao.core;

import java.time.Instant;
import java.util.List;

/**
 * One captured change. {@code versao} and {@code estado} are both null when the source only
 * carries the key (the legacy outbox): the state must then be fetched from the
 * {@link StateProvider}. A source that already carries the state (a future CDC) fills both.
 */
public record ChangeEvent(
        long eventoId, String tabela, long codigo, char op, Instant criadoEm, Instant versao, List<Object> estado) {

    public static ChangeEvent soChave(long eventoId, String tabela, long codigo, char op, Instant criadoEm) {
        return new ChangeEvent(eventoId, tabela, codigo, op, criadoEm, null, null);
    }

    public boolean temEstado() {
        return estado != null && versao != null;
    }

    public Linha comoLinha() {
        return new Linha(codigo, versao, estado);
    }
}
