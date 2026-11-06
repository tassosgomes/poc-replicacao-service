package br.org.ecad.poc.replicacao.infra.oracle.replicacao;

import br.org.ecad.poc.replicacao.application.port.ChangeSource;
import br.org.ecad.poc.replicacao.domain.replicacao.ChangeEvent;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code evento_replicacao} of one partition ordered by {@code id}; the ack deletes the
 * applied events in one JDBC batch and commits, on the legacy connection (never the new bank's).
 */
public final class OutboxSource implements ChangeSource, AutoCloseable {

    private static final String POLL = "SELECT id, codigo, op, criado_em FROM evento_replicacao"
            + " WHERE tabela = ? AND particao = ? ORDER BY id FETCH FIRST ? ROWS ONLY";
    private static final String ACK = "DELETE FROM evento_replicacao WHERE id = ?";

    private final Connection legado;
    private final PreparedStatement poll;
    private final PreparedStatement ack;
    private final List<Long> codigos;

    public OutboxSource(Connection legado) {
        this(legado, null);
    }

    /**
     * Restricts polling to the explicitly listed codes. For tests on a shared bank.
     */
    public OutboxSource(Connection legado, List<Long> codigos) {
        this.legado = legado;
        this.codigos = codigos == null ? null : List.copyOf(codigos);
        if (this.codigos != null && this.codigos.isEmpty()) {
            throw new IllegalArgumentException("codes must not be empty");
        }
        try {
            String sql = this.codigos == null
                    ? POLL
                    : "SELECT id, codigo, op, criado_em FROM evento_replicacao"
                            + " WHERE tabela = ? AND particao = ? AND codigo IN ("
                            + String.join(", ", java.util.Collections.nCopies(this.codigos.size(), "?"))
                            + ") ORDER BY id FETCH FIRST ? ROWS ONLY";
            this.poll = legado.prepareStatement(sql);
            this.ack = legado.prepareStatement(ACK);
        } catch (SQLException e) {
            throw new ReplicacaoException("cannot prepare the outbox statements", e);
        }
    }

    @Override
    public List<ChangeEvent> poll(String tabela, int particao, int max) {
        try {
            poll.setString(1, tabela);
            poll.setInt(2, particao);
            int parametro = 3;
            if (codigos != null) {
                for (long codigo : codigos) {
                    poll.setLong(parametro++, codigo);
                }
            }
            poll.setInt(parametro, max);
            List<ChangeEvent> eventos = new ArrayList<>();
            try (ResultSet rs = poll.executeQuery()) {
                while (rs.next()) {
                    eventos.add(ChangeEvent.soChave(
                            rs.getLong(1),
                            tabela,
                            rs.getLong(2),
                            rs.getString(3).charAt(0),
                            rs.getObject(4, OffsetDateTime.class).toInstant()));
                }
            }
            legado.rollback();
            return eventos;
        } catch (SQLException e) {
            throw new ReplicacaoException("cannot read the outbox of " + tabela + "/" + particao, e);
        }
    }

    @Override
    public void ack(List<ChangeEvent> eventos) {
        try {
            for (ChangeEvent evento : eventos) {
                ack.setLong(1, evento.eventoId());
                ack.addBatch();
            }
            ack.executeBatch();
            legado.commit();
        } catch (SQLException e) {
            throw new ReplicacaoException("cannot acknowledge " + eventos.size() + " event(s)", e);
        }
    }

    @Override
    public void close() throws SQLException {
        poll.close();
        ack.close();
    }
}
