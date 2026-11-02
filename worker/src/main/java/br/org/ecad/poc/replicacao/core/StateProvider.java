package br.org.ecad.poc.replicacao.core;

import java.util.Collection;

/**
 * Current state of the rows of a table. A key that no longer exists is reported explicitly in
 * {@link Estados#ausentes()}, never as an error.
 */
public interface StateProvider {

    Estados buscar(TableSpec spec, Collection<Long> codigos);
}
