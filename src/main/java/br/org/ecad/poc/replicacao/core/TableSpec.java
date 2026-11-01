package br.org.ecad.poc.replicacao.core;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Declarative description of a replicated table: its name (as the capture trigger writes it in
 * {@code evento_replicacao.tabela}) and its business columns, key and version excluded. Reads and
 * writes are generated from it, so a new table is a new entry in {@link Esquema}, not new code.
 *
 * <p>Bind order of {@link #merge()}: {@code codigo}, the business columns in order,
 * {@code origem_updated_at}, then the candidate {@code id}, which only the insert branch uses.
 */
public record TableSpec(String nome, List<String> colunas) {

    public TableSpec {
        colunas = List.copyOf(colunas);
    }

    /** Lower-case SQL name; the event table stores the upper-case {@link #nome()}. */
    public String tabelaSql() {
        return nome.toLowerCase(Locale.ROOT);
    }

    /** Legacy read: key, version and business columns of up to {@code n} keys. */
    public String selectEstado(int n) {
        String marcas = IntStream.range(0, n).mapToObj(i -> "?").collect(Collectors.joining(", "));
        return "SELECT codigo, updated_at, " + String.join(", ", colunas) + " FROM " + tabelaSql()
                + " WHERE codigo IN (" + marcas + ")";
    }

    /**
     * Idempotent upsert guarded by version; {@code id} is written only when the row is new. A null
     * replica version counts as older than any origin version, so it is repaired, never kept.
     */
    public String merge() {
        String origem = "? AS codigo, "
                + colunas.stream().map(c -> "? AS " + c).collect(Collectors.joining(", "))
                + ", ? AS origem_updated_at";
        String atribuicoes = colunas.stream().map(c -> "t." + c + " = s." + c).collect(Collectors.joining(", "));
        String nomes = String.join(", ", colunas);
        String valores = colunas.stream().map(c -> "s." + c).collect(Collectors.joining(", "));
        return "MERGE INTO " + tabelaSql() + " t USING (SELECT " + origem + " FROM dual) s"
                + " ON (t.codigo = s.codigo)"
                + " WHEN MATCHED THEN UPDATE SET " + atribuicoes
                + ", t.origem_updated_at = s.origem_updated_at, t.replicado_em = SYSTIMESTAMP"
                + " WHERE t.origem_updated_at IS NULL OR s.origem_updated_at >= t.origem_updated_at"
                + " WHEN NOT MATCHED THEN INSERT (id, codigo, " + nomes + ", origem_updated_at, replicado_em)"
                + " VALUES (?, s.codigo, " + valores + ", s.origem_updated_at, SYSTIMESTAMP)";
    }
}
