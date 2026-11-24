package br.org.ecad.poc.replicacao.infra.oracle.replicacao;

import br.org.ecad.poc.replicacao.application.port.Fluxo;
import br.org.ecad.poc.replicacao.application.port.RepositorioOffsets;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Stream positions in {@code kafka_offset} of the destination. {@link #gravar} writes on the connection of
 * the data and leaves the commit to the lease fence, so positions and data are durable together.
 */
public final class OffsetsAdapter implements RepositorioOffsets {

    private static final String MERGE = "MERGE INTO kafka_offset k USING (SELECT ? topico, ? particao FROM dual) x"
            + " ON (k.topico = x.topico AND k.particao = x.particao)"
            + " WHEN MATCHED THEN UPDATE SET k.proximo = ?, k.gravado_em = SYS_EXTRACT_UTC(SYSTIMESTAMP)"
            + " WHEN NOT MATCHED THEN INSERT (topico, particao, proximo) VALUES (x.topico, x.particao, ?)";

    private final Connection novo;

    public OffsetsAdapter(Connection novo) {
        this.novo = novo;
    }

    @Override
    public Map<Fluxo, Long> carregar() {
        Map<Fluxo, Long> posicoes = new HashMap<>();
        try (PreparedStatement s = novo.prepareStatement("SELECT topico, particao, proximo FROM kafka_offset");
                ResultSet r = s.executeQuery()) {
            while (r.next()) {
                posicoes.put(new Fluxo(r.getString(1), r.getInt(2)), r.getLong(3));
            }
            novo.commit();
            return posicoes;
        } catch (SQLException e) {
            throw new ReplicacaoException("cannot read the stored positions", e);
        }
    }

    @Override
    public void gravar(Map<Fluxo, Long> posicoes) {
        try (PreparedStatement s = novo.prepareStatement(MERGE)) {
            for (Map.Entry<Fluxo, Long> p : posicoes.entrySet()) {
                s.setString(1, p.getKey().topico());
                s.setInt(2, p.getKey().particao());
                s.setLong(3, p.getValue());
                s.setLong(4, p.getValue());
                s.addBatch();
            }
            s.executeBatch();
        } catch (SQLException e) {
            try {
                novo.rollback();
            } catch (SQLException ignored) {
                // the write failure is the one worth reporting
            }
            throw new ReplicacaoException("cannot write the positions", e);
        }
    }
}
