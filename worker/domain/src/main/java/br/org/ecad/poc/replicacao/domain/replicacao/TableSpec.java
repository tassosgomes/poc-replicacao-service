package br.org.ecad.poc.replicacao.domain.replicacao;

import java.util.List;

/** Replicated table name and ordered business columns, excluding key and version. */
public record TableSpec(String nome, List<String> colunas) {
    public TableSpec {
        colunas = List.copyOf(colunas);
    }
}
