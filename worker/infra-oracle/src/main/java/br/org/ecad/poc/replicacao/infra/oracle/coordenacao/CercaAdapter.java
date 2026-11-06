package br.org.ecad.poc.replicacao.infra.oracle.coordenacao;

import static br.org.ecad.poc.replicacao.infra.oracle.coordenacao.LeaseRepositoryAdapter.ms;

import br.org.ecad.poc.replicacao.application.port.Cerca;
import br.org.ecad.poc.replicacao.application.port.LeaseRepository;
import br.org.ecad.poc.replicacao.domain.coordenacao.Particao;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import java.sql.*;
import java.util.function.LongSupplier;

/** Database-side validation and commit of one partition lease. */
final class CercaAdapter {
    private static final String AGORA = "SYS_EXTRACT_UTC(SYSTIMESTAMP)";
    private static final int MARGEM_SEG = LeaseRepository.MARGEM_SEG;

    private CercaAdapter() {}
    /**
     * Guard of one processor (fencing). It captures the claim version without locking the row. The
     * read-only pre-authorization can therefore be followed by an arbitrarily long JVM pause without
     * preventing another worker from claiming an expired lease and incrementing that version.
     * Destination confirmation executes the version/owner/deadline check and {@code COMMIT} in one
     * server-side PL/SQL call. A pause can happen before the call (the new owner invalidates the token)
     * or after it (the database already finished the commit), never between its check and commit.
     *
     * <p>The origin ack runs in another database and cannot join that transaction. It only follows
     * a fenced commit, deletes events by id (idempotent: an event already deleted by the next owner
     * deletes nothing) and is skipped once the local deadline passed; a late ack therefore only removes
     * events whose effect is already durable in the destination.
     */
    static Cerca criar(Connection novo, Particao p, LongSupplier prazoNanos, String workerId) {
        long versao = versaoAtual(novo, p, workerId);
        String preAutorizar = "SELECT " + ms("expira_em - " + AGORA)
                + " FROM lease_particao WHERE tabela=? AND particao=? AND dono=? AND versao=?";
        String confirmar = "DECLARE valido NUMBER; BEGIN UPDATE lease_particao SET versao=versao"
                + " WHERE tabela=? AND particao=? AND dono=? AND versao=? AND expira_em > " + AGORA
                + " + NUMTODSINTERVAL(?, 'SECOND'); valido := SQL%ROWCOUNT;"
                + " IF valido=1 THEN COMMIT; ELSE ROLLBACK; END IF; ? := valido; END;";
        return new Cerca() {
            @Override
            public boolean podeConfirmarDestino() {
                try {
                    if (System.nanoTime() < prazoNanos.getAsLong() && restanteMs() >= MARGEM_SEG * 1000L) return true;
                    novo.rollback();
                    return false;
                } catch (SQLException e) {
                    rollbackQuietly();
                    throw new ReplicacaoException("cannot check the lease of " + p, e);
                }
            }

            @Override
            public boolean confirmarDestino(br.org.ecad.poc.replicacao.application.port.ReplicaSink sink) {
                if (System.nanoTime() >= prazoNanos.getAsLong()) {
                    rollbackQuietly();
                    return false;
                }
                try (CallableStatement s = novo.prepareCall(confirmar)) {
                    s.setString(1, p.tabela().nome());
                    s.setInt(2, p.numero());
                    s.setString(3, workerId);
                    s.setLong(4, versao);
                    s.setInt(5, MARGEM_SEG);
                    s.registerOutParameter(6, java.sql.Types.INTEGER);
                    s.execute();
                    return s.getInt(6) == 1;
                } catch (SQLException e) {
                    rollbackQuietly();
                    throw new ReplicacaoException("cannot fence the commit of " + p, e);
                }
            }

            @Override
            public boolean podeConfirmarOrigem() {
                return System.nanoTime() < prazoNanos.getAsLong();
            }

            private long restanteMs() throws SQLException {
                try (PreparedStatement s = novo.prepareStatement(preAutorizar)) {
                    s.setString(1, p.tabela().nome());
                    s.setInt(2, p.numero());
                    s.setString(3, workerId);
                    s.setLong(4, versao);
                    try (ResultSet r = s.executeQuery()) {
                        return r.next() ? r.getLong(1) : -1;
                    }
                }
            }

            private void rollbackQuietly() {
                try {
                    novo.rollback();
                } catch (SQLException ignored) {
                    // the lease failure is the one worth reporting
                }
            }
        };
    }

    private static long versaoAtual(Connection novo, Particao p, String workerId) {
        try (PreparedStatement s =
                novo.prepareStatement("SELECT versao FROM lease_particao WHERE tabela=? AND particao=? AND dono=?")) {
            s.setString(1, p.tabela().nome());
            s.setInt(2, p.numero());
            s.setString(3, workerId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new ReplicacaoException("cannot capture the lease version of " + p, e);
        }
    }
}
