package br.org.ecad.poc.replicacao.core;

import java.util.List;

/** The replicated tables. Adding a table to the replication is adding an entry here. */
public final class Esquema {

    public static final TableSpec TITULAR =
            new TableSpec("TITULAR", List.of("nome", "tipo", "documento", "situacao", "excluido"));
    public static final TableSpec OBRA_MUSICAL =
            new TableSpec("OBRA_MUSICAL", List.of("titulo", "iswc", "genero", "situacao", "excluido"));
    public static final TableSpec FONOGRAMA =
            new TableSpec("FONOGRAMA", List.of("obra_codigo", "titulo", "isrc", "duracao_seg", "excluido"));

    public static final List<TableSpec> TABELAS = List.of(TITULAR, OBRA_MUSICAL, FONOGRAMA);

    /** Logical partitions per table: {@code ORA_HASH(codigo, 15)} yields 0..15. */
    public static final int PARTICOES = 16;

    private Esquema() {}
}
