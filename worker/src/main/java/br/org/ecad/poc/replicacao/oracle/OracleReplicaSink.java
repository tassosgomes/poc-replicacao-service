package br.org.ecad.poc.replicacao.oracle;

import br.org.ecad.poc.replicacao.core.Linha;
import br.org.ecad.poc.replicacao.core.ReplicaSink;
import br.org.ecad.poc.replicacao.core.ReplicacaoException;
import br.org.ecad.poc.replicacao.core.ReprocessavelException;
import br.org.ecad.poc.replicacao.core.TableSpec;
import br.org.ecad.poc.replicacao.core.Uuid7;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a batch with one {@code MERGE} per row in a JDBC batch. The {@code id} is generated only
 * for rows the replica does not hold yet (looked up by {@code codigo} before the merge); for an
 * existing row the generator is not called and the {@code MERGE} never writes the {@code id}, so
 * it keeps the one it had. ORA-00001 (a concurrent writer inserted the same
 * {@code codigo}) is reported as reprocessable.
 */
public final class OracleReplicaSink implements ReplicaSink, AutoCloseable {

    private static final int ORA_UNIQUE_VIOLATION = 1;
    private static final int EXISTENCE_BLOCK = 1_000;

    private final Connection novo;
    private final Uuid7 ids;
    private final Map<String, PreparedStatement> merges = new HashMap<>();

    public OracleReplicaSink(Connection novo, Uuid7 ids) {
        this.novo = novo;
        this.ids = ids;
    }

    @Override
    public void upsert(TableSpec spec, List<Linha> linhas) {
        try {
            PreparedStatement ps = merges.get(spec.nome());
            if (ps == null) {
                ps = novo.prepareStatement(spec.merge());
                merges.put(spec.nome(), ps);
            }
            Set<Long> existentes = existentes(spec, linhas);
            for (Linha linha : linhas) {
                int i = 1;
                ps.setLong(i++, linha.codigo());
                for (Object valor : linha.valores()) {
                    ps.setObject(i++, valor);
                }
                ps.setObject(i++, LocalDateTime.ofInstant(linha.versao(), ZoneOffset.UTC));
                if (existentes.contains(linha.codigo())) {
                    ps.setNull(i, Types.BINARY);
                } else {
                    ps.setBytes(i, Uuid7.bytes(ids.next()));
                }
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            throw traduzir(spec, e);
        }
    }

    private Set<Long> existentes(TableSpec spec, List<Linha> linhas) throws SQLException {
        Set<Long> existentes = new HashSet<>();
        for (int de = 0; de < linhas.size(); de += EXISTENCE_BLOCK) {
            List<Linha> bloco = linhas.subList(de, Math.min(de + EXISTENCE_BLOCK, linhas.size()));
            String marcas = String.join(", ", Collections.nCopies(bloco.size(), "?"));
            try (PreparedStatement ps = novo.prepareStatement(
                    "SELECT codigo FROM " + spec.tabelaSql() + " WHERE codigo IN (" + marcas + ")")) {
                for (int i = 0; i < bloco.size(); i++) {
                    ps.setLong(i + 1, bloco.get(i).codigo());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        existentes.add(rs.getLong(1));
                    }
                }
            }
        }
        return existentes;
    }

    @Override
    public void commit() {
        try {
            novo.commit();
        } catch (SQLException e) {
            throw new ReplicacaoException("cannot commit the batch in the new bank", e);
        }
    }

    private ReplicacaoException traduzir(TableSpec spec, SQLException e) {
        rollbackQuietly();
        SQLException causa = e instanceof BatchUpdateException && e.getCause() instanceof SQLException c ? c : e;
        if (e.getErrorCode() == ORA_UNIQUE_VIOLATION || causa.getErrorCode() == ORA_UNIQUE_VIOLATION) {
            return new ReprocessavelException("concurrent writer on " + spec.nome() + ": reprocess the batch", e);
        }
        return new ReplicacaoException("cannot apply the batch to " + spec.nome(), e);
    }

    private void rollbackQuietly() {
        try {
            novo.rollback();
        } catch (SQLException ignored) {
            // the original failure is the one worth reporting
        }
    }

    @Override
    public void close() throws SQLException {
        for (PreparedStatement ps : merges.values()) {
            ps.close();
        }
        merges.clear();
    }
}
