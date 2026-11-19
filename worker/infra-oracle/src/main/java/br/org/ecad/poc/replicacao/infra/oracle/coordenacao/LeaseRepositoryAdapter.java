package br.org.ecad.poc.replicacao.infra.oracle.coordenacao;

import br.org.ecad.poc.replicacao.application.port.Cerca;
import br.org.ecad.poc.replicacao.application.port.LeaseRepository;
import br.org.ecad.poc.replicacao.domain.coordenacao.Particao;
import br.org.ecad.poc.replicacao.domain.replicacao.Esquema;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Coordinates versioned leases using the destination database clock. */
public final class LeaseRepositoryAdapter implements LeaseRepository {
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

    private final String workerId;
    private final int leaseSeg;
    private final Supplier<Connection> conexao;

    public LeaseRepositoryAdapter(String workerId, int leaseSeg, Supplier<Connection> conexao) {
        this.workerId = workerId;
        this.leaseSeg = leaseSeg;
        this.conexao = conexao;
    }

    Connection conectar() {
        return conexao.get();
    }

    int heartbeat(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "MERGE INTO worker_heartbeat h USING (SELECT ? id FROM dual) x ON (h.worker_id=x.id) WHEN MATCHED THEN UPDATE SET h.visto_em="
                        + AGORA + " WHEN NOT MATCHED THEN INSERT (worker_id,visto_em) VALUES (x.id," + AGORA + ")")) {
            s.setString(1, workerId);
            s.executeUpdate();
        }
        try (PreparedStatement s = c.prepareStatement("SELECT COUNT(*) FROM worker_heartbeat WHERE visto_em > " + AGORA
                + " - NUMTODSINTERVAL(?, 'SECOND')")) {
            s.setInt(1, leaseSeg);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }
    }

    List<Particao> renovar(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("UPDATE lease_particao SET expira_em=" + AGORA
                + " + NUMTODSINTERVAL(?, 'SECOND') WHERE dono=? AND expira_em > " + AGORA + "")) {
            s.setInt(1, leaseSeg);
            s.setString(2, workerId);
            s.executeUpdate();
        }
        List<Particao> owned = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(
                "SELECT tabela, particao FROM lease_particao WHERE dono=? AND expira_em > " + AGORA + "")) {
            s.setString(1, workerId);
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
                    u.setString(1, workerId);
                    u.setInt(2, leaseSeg);
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

    public Cerca cerca(Connection novo, Particao p, LongSupplier prazoNanos) {
        return CercaAdapter.criar(novo, p, prazoNanos, workerId);
    }

    /**
     * Bounds every JDBC round trip of a processor connection (poll, state fetch, upsert, commit,
     * ack) to a third of the lease: an operation started before the deadline cannot run on for
     * long after it. On timeout the driver aborts the connection, the batch fails without commit
     * or ack and the processor reconnects; the fence above keeps any overrun harmless.
     */
    public void limitarOperacoes(Connection c) throws SQLException {
        c.setNetworkTimeout(Runnable::run, (int) tempoLimiteOperacaoMs());
    }

    long tempoLimiteOperacaoMs() {
        return leaseSeg * 1000L / 3;
    }

    void liberar(Connection c, Particao p) throws SQLException {
        try (PreparedStatement s =
                c.prepareStatement("UPDATE lease_particao SET dono=NULL, expira_em=NULL, versao=versao+1"
                        + " WHERE tabela=? AND particao=? AND dono=?")) {
            s.setString(1, p.tabela().nome());
            s.setInt(2, p.numero());
            s.setString(3, workerId);
            s.executeUpdate();
        }
    }

    /** Releases every lease still naming this worker, in one statement (orderly shutdown). */
    void liberarTodos(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(
                "UPDATE lease_particao SET dono=NULL, expira_em=NULL, versao=versao+1 WHERE dono=?")) {
            s.setString(1, workerId);
            s.executeUpdate();
        }
    }

    void sair(Connection c) throws SQLException {
        try (PreparedStatement s = c.prepareStatement("DELETE FROM worker_heartbeat WHERE worker_id=?")) {
            s.setString(1, workerId);
            s.executeUpdate();
        }
    }

    private static Particao particao(String tabela, int numero) throws SQLException {
        for (TableSpec spec : Esquema.TABELAS) if (spec.nome().equals(tabela)) return new Particao(spec, numero);
        throw new SQLException("unknown table in lease: " + tabela);
    }

    @Override
    public Sessao abrir() {
        return new SessaoAdapter(conectar());
    }

    private final class SessaoAdapter implements Sessao {
        private final Connection c;

        private SessaoAdapter(Connection c) {
            this.c = c;
        }

        private <T> T executar(SqlCall<T> call) {
            try {
                return call.executar();
            } catch (SQLException e) {
                throw new ReplicacaoException("lease operation failed", e);
            }
        }

        public int heartbeat() {
            return executar(() -> LeaseRepositoryAdapter.this.heartbeat(c));
        }

        public List<Particao> renovar() {
            return executar(() -> LeaseRepositoryAdapter.this.renovar(c));
        }

        public List<Particao> adquirir(int quantidade) {
            return executar(() -> LeaseRepositoryAdapter.this.adquirir(c, quantidade));
        }

        public void liberar(Particao p) {
            executar(() -> {
                LeaseRepositoryAdapter.this.liberar(c, p);
                return null;
            });
        }

        public void liberarTodos() {
            executar(() -> {
                LeaseRepositoryAdapter.this.liberarTodos(c);
                return null;
            });
        }

        public void sair() {
            executar(() -> {
                LeaseRepositoryAdapter.this.sair(c);
                return null;
            });
        }

        public void confirmar() {
            executar(() -> {
                c.commit();
                return null;
            });
        }

        public void close() {
            executar(() -> {
                c.close();
                return null;
            });
        }
    }

    @FunctionalInterface
    private interface SqlCall<T> {
        T executar() throws SQLException;
    }
}
