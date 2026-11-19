package br.org.ecad.poc.replicacao.domain.coordenacao;

import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;

/** Identity of one independently leased replication partition. */
public record Particao(TableSpec tabela, int numero) {}
