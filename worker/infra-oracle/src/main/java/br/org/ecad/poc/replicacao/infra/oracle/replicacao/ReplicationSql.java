package br.org.ecad.poc.replicacao.infra.oracle.replicacao;

import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** SQL generation for replicated tables. Bind order: key, business columns, version, candidate id. */
public final class ReplicationSql {
    private ReplicationSql() {}
    /** Lower-case SQL name; the event table stores the upper-case {@link TableSpec#nome()}. */
    public static String tabelaSql(TableSpec spec) {
        return spec.nome().toLowerCase(Locale.ROOT);
    }

    /** Legacy read: key, version and business columns of up to {@code n} keys. */
    public static String selectEstado(TableSpec spec, int n) {
        String marcas = IntStream.range(0, n).mapToObj(i -> "?").collect(Collectors.joining(", "));
        return "SELECT codigo, updated_at, " + String.join(", ", spec.colunas()) + " FROM " + tabelaSql(spec)
                + " WHERE codigo IN (" + marcas + ")";
    }

    /**
     * Idempotent upsert guarded by version; {@code id} is written only when the row is new. A null
     * replica version counts as older than any origin version, so it is repaired, never kept.
     */
    public static String merge(TableSpec spec) {
        String origem = "? AS codigo, "
                + spec.colunas().stream().map(c -> "? AS " + c).collect(Collectors.joining(", "))
                + ", ? AS origem_updated_at";
        String atribuicoes =
                spec.colunas().stream().map(c -> "t." + c + " = s." + c).collect(Collectors.joining(", "));
        String nomes = String.join(", ", spec.colunas());
        String valores = spec.colunas().stream().map(c -> "s." + c).collect(Collectors.joining(", "));
        return "MERGE INTO " + tabelaSql(spec) + " t USING (SELECT " + origem + " FROM dual) s"
                + " ON (t.codigo = s.codigo)"
                + " WHEN MATCHED THEN UPDATE SET " + atribuicoes
                + ", t.origem_updated_at = s.origem_updated_at, t.replicado_em = SYSTIMESTAMP"
                + " WHERE t.origem_updated_at IS NULL OR s.origem_updated_at >= t.origem_updated_at"
                + " WHEN NOT MATCHED THEN INSERT (id, codigo, " + nomes + ", origem_updated_at, replicado_em)"
                + " VALUES (?, s.codigo, " + valores + ", s.origem_updated_at, SYSTIMESTAMP)";
    }
}
