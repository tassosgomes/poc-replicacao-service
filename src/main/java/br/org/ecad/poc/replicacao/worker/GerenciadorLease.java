package br.org.ecad.poc.replicacao.worker;

import br.org.ecad.poc.replicacao.Config;
import br.org.ecad.poc.replicacao.core.Esquema;
import br.org.ecad.poc.replicacao.core.ReplicacaoException;
import br.org.ecad.poc.replicacao.core.TableSpec;
import br.org.ecad.poc.replicacao.oracle.Conexoes;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/** Coordinates versioned leases using the destination database clock. */
final class GerenciadorLease {
    /**
     * The control columns are {@code TIMESTAMP} (no zone): they hold UTC from the database clock, so the
     * comparison does not depend on the time zone of either JDBC session or database host.
     */
    private static final String AGORA = "SYS_EXTRACT_UTC(SYSTIMESTAMP)";

    /**
     * No processor works with a lease that expires in less than this: it stops polling, and the
     * destination commit and origin ack of a batch in flight are refused ({@link #cerca}). Claims
     * never use it: only partitions without owner or already expired are taken.
     */
    static final int MARGEM_SEG = 2;

    /** Milliseconds of an {@code INTERVAL DAY TO SECOND} expression. */
    static String ms(String intervalo) {
        return "(EXTRACT(DAY FROM " + intervalo + ")*86400000 + EXTRACT(HOUR FROM " + intervalo
                + ")*3600000 + EXTRACT(MINUTE FROM " + intervalo + ")*60000 + EXTRACT(SECOND FROM " + intervalo
                + ")*1000)";
    }

    record Particao(TableSpec tabela, int numero) {}

    private final Config config;

    GerenciadorLease(Config config) {
        this.config = config;
    }

    Connection conectar() {
        return Conexoes.abrir(config.novoUrl(), config.novoUsuario(), config.novoSenha());
    }

    int heartbeat(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "MERGE INTO worker_heartbeat h USING (SELECT ? id FROM dual) x ON (h.worker_id=x.id) WHEN MATCHED THEN UPDATE SET h.visto_em="
                        + AGORA + " WHEN NOT MATCHED THEN INSERT (worker_id,visto_em) VALUES (x.id," + AGORA + ")")) {
            s.setString(1, config.workerId());
            s.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM worker_heartbeat WHERE visto_em > " + AGORA
                + " - NUMTODSINTERVAL(?, 'SECOND')")) {
            s.setInt(1, config.leaseSeg());
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }

    List<Particao> renovar(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE lease_particao SET expira_em=" + AGORA
                + " + NUMTODSINTERVAL(?, 'SECOND') WHERE dono=? AND expira_em > " + AGORA + "")) {
            s.setInt(1, config.leaseSeg());
            s.setString(2, config.workerId());
            s.executeUpdate();
        }
        List<Particao> owned = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(
                "SELECT tabela, particao FROM lease_particao WHERE dono=? AND expira_em > " + AGORA + "")) {
            s.setString(1, config.workerId());
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) owned.add(particao(r.getString(1), r.getInt(2)));
            }
        }
        return owned;
    }

    List<Particao> adquirir(Connection c, int quantidade) throws SQLException {
        List<Particao> claimed = new ArrayList<>();
        if (quantidade <= 0) return claimed;
        try (PreparedStatement s = c.prepareStatement(
                        "SELECT tabela, particao FROM lease_particao WHERE dono IS NULL OR expira_em <= " + AGORA
                                + " FOR UPDATE SKIP LOCKED");
                PreparedStatement u = c.prepareStatement("UPDATE lease_particao SET dono=?, expira_em=" + AGORA
                        + " + NUMTODSINTERVAL(?, 'SECOND'), versao=versao+1 WHERE tabela=? AND particao=?")) {
            // SKIP LOCKED locks rows as they are fetched: one fetch of exactly the quota locks only the rows
            // claimed below, in a single round trip; the updates go in one batch.
            s.setFetchSize(quantidade);
            try (ResultSet r = s.executeQuery()) {
                while (claimed.size() < quantidade && r.next()) {
                    Particao p = particao(r.getString(1), r.getInt(2));
                    u.setString(1, config.workerId());
                    u.setInt(2, config.leaseSeg());
                    u.setString(3, p.tabela().nome());
                    u.setInt(4, p.numero());
                    u.addBatch();
                    claimed.add(p);
                }
            }
            if (!claimed.isEmpty()) u.executeBatch();
        }
        return claimed;
    }

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
    Cerca cerca(Connection novo, Particao p, LongSupplier prazoNanos) {
        long versao = versaoAtual(novo, p);
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
            public boolean confirmarDestino(br.org.ecad.poc.replicacao.core.ReplicaSink sink) {
                if (System.nanoTime() >= prazoNanos.getAsLong()) {
                    rollbackQuietly();
                    return false;
                }
                try (CallableStatement s = novo.prepareCall(confirmar)) {
                    s.setString(1, p.tabela().nome());
                    s.setInt(2, p.numero());
                    s.setString(3, config.workerId());
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
                    s.setString(3, config.workerId());
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

    private long versaoAtual(Connection novo, Particao p) {
        try (PreparedStatement s =
                novo.prepareStatement("SELECT versao FROM lease_particao WHERE tabela=? AND particao=? AND dono=?")) {
            s.setString(1, p.tabela().nome());
            s.setInt(2, p.numero());
            s.setString(3, config.workerId());
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getLong(1) : -1;
            }
        } catch (SQLException e) {
            throw new ReplicacaoException("cannot capture the lease version of " + p, e);
        }
    }

    /**
     * Bounds every JDBC round trip of a processor connection (poll, state fetch, upsert, commit,
     * ack) to a third of the lease: an operation started before the deadline cannot run on for
     * long after it. On timeout the driver aborts the connection, the batch fails without commit
     * or ack and the processor reconnects; the fence above keeps any overrun harmless.
     */
    void limitarOperacoes(Connection c) throws SQLException {
        c.setNetworkTimeout(Runnable::run, (int) tempoLimiteOperacaoMs());
    }

    long tempoLimiteOperacaoMs() {
        return config.leaseSeg() * 1000L / 3;
    }

    void liberar(Connection c, Particao p) throws SQLException {
        try (PreparedStatement s =
                c.prepareStatement("UPDATE lease_particao SET dono=NULL, expira_em=NULL, versao=versao+1"
                        + " WHERE tabela=? AND particao=? AND dono=?")) {
            s.setString(1, p.tabela().nome());
            s.setInt(2, p.numero());
            s.setString(3, config.workerId());
            s.executeUpdate();
        }
    }

    /** Releases every lease still naming this worker, in one statement (orderly shutdown). */
    void liberarTodos(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "UPDATE lease_particao SET dono=NULL, expira_em=NULL, versao=versao+1 WHERE dono=?")) {
            s.setString(1, config.workerId());
            s.executeUpdate();
        }
    }

    void sair(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("DELETE FROM worker_heartbeat WHERE worker_id=?")) {
            s.setString(1, config.workerId());
            s.executeUpdate();
        }
    }

    private static Particao particao(String tabela, int numero) throws SQLException {
        for (TableSpec spec : Esquema.TABELAS) if (spec.nome().equals(tabela)) return new Particao(spec, numero);
        throw new SQLException("unknown table in lease: " + tabela);
    }
}
