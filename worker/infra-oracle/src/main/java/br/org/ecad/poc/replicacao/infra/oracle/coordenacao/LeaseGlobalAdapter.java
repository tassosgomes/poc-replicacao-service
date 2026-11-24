package br.org.ecad.poc.replicacao.infra.oracle.coordenacao;

import static br.org.ecad.poc.replicacao.infra.oracle.coordenacao.LeaseRepositoryAdapter.ms;

import br.org.ecad.poc.replicacao.application.port.Cerca;
import br.org.ecad.poc.replicacao.application.port.LeaseRepository;
import br.org.ecad.poc.replicacao.application.port.ReplicaSink;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.concurrent.TimeUnit;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * The single lease of the transactional mode (row {@code GLOBAL} of {@code lease_global}), with the same
 * rules as the partition leases: database clock, ownership version bumped at every change of owner, and a
 * fence that validates owner, version and expiry and commits in one server-side call. Unlike the partition
 * fence, a successful commit also extends the expiry, so a busy processor never needs a separate renewal.
 */
public final class LeaseGlobalAdapter {

    private static final String AGORA = "SYS_EXTRACT_UTC(SYSTIMESTAMP)";
    private static final int MARGEM_SEG = LeaseRepository.MARGEM_SEG;

    private final String workerId;
    private final int leaseSeg;

    public LeaseGlobalAdapter(String workerId, int leaseSeg) {
        this.workerId = workerId;
        this.leaseSeg = leaseSeg;
    }

    /** Local deadline (System.nanoTime scale) of a lease confirmed by the database at {@code instanteNanos}. */
    public long prazo(long instanteNanos) {
        return instanteNanos + TimeUnit.SECONDS.toNanos(leaseSeg - (long) MARGEM_SEG);
    }

    /** Bounds every round trip of the connection to a third of the lease, as the partition processors do. */
    public void limitarOperacoes(Connection c) throws SQLException {
        c.setNetworkTimeout(Runnable::run, (int) (leaseSeg * 1000L / 3));
    }

    /** Tries to become the owner (free or expired lease): the new ownership version, or -1. Commits. */
    public long assumir(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE lease_global SET dono=?, expira_em=" + AGORA
                + " + NUMTODSINTERVAL(?, 'SECOND'), versao=versao+1"
                + " WHERE nome='GLOBAL' AND (dono IS NULL OR expira_em <= " + AGORA + ")")) {
            s.setString(1, workerId);
            s.setInt(2, leaseSeg);
            if (s.executeUpdate() != 1) {
                c.rollback();
                return -1;
            }
        }
        long versao = versao(c);
        c.commit();
        return versao;
    }

    /** Extends the expiry of the lease this worker still holds; false when it was lost. Commits. */
    public boolean renovar(Connection c, long versao) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE lease_global SET expira_em=" + AGORA
                + " + NUMTODSINTERVAL(?, 'SECOND') WHERE nome='GLOBAL' AND dono=? AND versao=? AND expira_em > "
                + AGORA)) {
            s.setInt(1, leaseSeg);
            s.setString(2, workerId);
            s.setLong(3, versao);
            boolean renovado = s.executeUpdate() == 1;
            c.commit();
            return renovado;
        }
    }

    /** Orderly shutdown: frees the lease (new version) so the standby takes over without waiting for expiry. */
    public void liberar(Connection c, long versao) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE lease_global SET dono=NULL, expira_em=NULL,"
                + " versao=versao+1 WHERE nome='GLOBAL' AND dono=? AND versao=?")) {
            s.setString(1, workerId);
            s.setLong(2, versao);
            s.executeUpdate();
            c.commit();
        }
    }

    private long versao(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("SELECT versao FROM lease_global WHERE nome='GLOBAL'");
                ResultSet r = s.executeQuery()) {
            r.next();
            return r.getLong(1);
        }
    }

    /**
     * Fence of the processor: {@code novoPrazo} receives the extended local deadline after every confirmed commit.
     * The captured version is the ownership version returned by {@link #assumir}.
     */
    public Cerca cerca(Connection novo, long versao, LongSupplier prazoNanos, LongConsumer novoPrazo) {
        String preAutorizar = "SELECT " + ms("expira_em - " + AGORA)
                + " FROM lease_global WHERE nome='GLOBAL' AND dono=? AND versao=?";
        String confirmar = "DECLARE valido NUMBER; BEGIN UPDATE lease_global SET expira_em=" + AGORA
                + " + NUMTODSINTERVAL(?, 'SECOND') WHERE nome='GLOBAL' AND dono=? AND versao=? AND expira_em > "
                + AGORA + " + NUMTODSINTERVAL(?, 'SECOND'); valido := SQL%ROWCOUNT;"
                + " IF valido=1 THEN COMMIT; ELSE ROLLBACK; END IF; ? := valido; END;";
        return new Cerca() {
            @Override
            public boolean podeConfirmarDestino() {
                try {
                    if (System.nanoTime() < prazoNanos.getAsLong() && restanteMs() >= MARGEM_SEG * 1000L) {
                        return true;
                    }
                    novo.rollback();
                    return false;
                } catch (SQLException e) {
                    rollbackQuietly();
                    throw new ReplicacaoException("cannot check the global lease", e);
                }
            }

            @Override
            public boolean confirmarDestino(ReplicaSink sink) {
                if (System.nanoTime() >= prazoNanos.getAsLong()) {
                    rollbackQuietly();
                    return false;
                }
                long antes = System.nanoTime();
                try (CallableStatement s = novo.prepareCall(confirmar)) {
                    s.setInt(1, leaseSeg);
                    s.setString(2, workerId);
                    s.setLong(3, versao);
                    s.setInt(4, MARGEM_SEG);
                    s.registerOutParameter(5, Types.INTEGER);
                    s.execute();
                    boolean valido = s.getInt(5) == 1;
                    if (valido) {
                        novoPrazo.accept(prazo(antes));
                    }
                    return valido;
                } catch (SQLException e) {
                    rollbackQuietly();
                    throw new ReplicacaoException("cannot fence the commit of the global lease", e);
                }
            }

            @Override
            public boolean podeConfirmarOrigem() {
                return System.nanoTime() < prazoNanos.getAsLong();
            }

            private long restanteMs() throws SQLException {
                try (PreparedStatement s = novo.prepareStatement(preAutorizar)) {
                    s.setString(1, workerId);
                    s.setLong(2, versao);
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
}
