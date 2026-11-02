package br.org.ecad.poc.replicacao.worker;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.ecad.poc.replicacao.Config;
import br.org.ecad.poc.replicacao.core.ChangeEvent;
import br.org.ecad.poc.replicacao.core.ChangeSource;
import br.org.ecad.poc.replicacao.core.Esquema;
import br.org.ecad.poc.replicacao.core.Linha;
import br.org.ecad.poc.replicacao.core.ReplicaSink;
import br.org.ecad.poc.replicacao.core.TableSpec;
import br.org.ecad.poc.replicacao.core.Uuid7;
import br.org.ecad.poc.replicacao.oracle.BancosPoc;
import br.org.ecad.poc.replicacao.oracle.OracleOutboxSource;
import br.org.ecad.poc.replicacao.oracle.OracleReplicaSink;
import br.org.ecad.poc.replicacao.oracle.OracleStateProvider;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V-07, lease fence of a batch in flight, against the real PoC databases. Worker A starts a batch
 * while it owns the partition and is blocked right before the commit (or the ack) until its lease
 * has crossed the margin and expired; worker B must not claim the partition while A's lease is
 * live, even inside the margin, claims it once it expired and processes it concurrently with A's
 * blocked batch. A must then apply nothing after its deadline: the guard used is the one the
 * {@link Worker} installs ({@link GerenciadorLease#cerca}). Additional cases block A <em>after</em>
 * the guard authorized it and independently exercise the owner, deadline and version predicates of
 * the atomic confirmation, plus a late ack after takeover.
 *
 * <p>Owns {@code lease_particao} and {@code worker_heartbeat} while it runs, like
 * {@code V07LeaseIT}; test rows live in a random block of codes reserved for tests.
 */
class V07CercaIT {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final TableSpec OBRA = Esquema.OBRA_MUSICAL;
    /**
     * Lease given to A before its batch reaches the blocking point: long enough that opening the
     * connections, polling and upserting never consume it on a slow shared database. Once A is blocked,
     * the test expires the lease with the database clock ({@link #ajustarLeaseA}).
     */
    private static final int LEASE_LONGO_SEG = 60;

    private static final long FAIXA_MIN = 9_000_000_000_000L;
    private static final long FAIXA_BLOCOS = 9_000_000L;
    private static final long BLOCO = 100_000L;
    private static final SecureRandom SORTEIO = new SecureRandom();

    private final long codigo = FAIXA_MIN + SORTEIO.nextLong(FAIXA_BLOCOS) * BLOCO + 1;
    private Connection legado;
    private Connection novo;
    private GerenciadorLease.Particao particao;
    private boolean criado;

    @BeforeEach
    void abrir() throws Exception {
        legado = BancosPoc.legado();
        novo = BancosPoc.novo();
        reiniciarControle();
        try (PreparedStatement ps = legado.prepareStatement("INSERT INTO obra_musical (codigo, titulo, iswc, genero,"
                + " situacao, excluido) VALUES (?, 'cerca', 'T', 'OUTRO', 'ATIVO', 0)")) {
            ps.setLong(1, codigo);
            ps.executeUpdate();
            legado.commit();
        }
        criado = true;
        particao = new GerenciadorLease.Particao(OBRA, (int) escalar(
                legado,
                "SELECT MIN(particao) FROM evento_replicacao WHERE tabela='OBRA_MUSICAL' AND codigo=" + codigo));
        legado.rollback();
    }

    @AfterEach
    void encerrar() throws Exception {
        try {
            if (criado) {
                try (Statement s = novo.createStatement()) {
                    s.executeUpdate("DELETE FROM obra_musical WHERE codigo=" + codigo);
                }
                novo.commit();
                try (Statement s = legado.createStatement()) {
                    s.executeUpdate("DELETE FROM obra_musical WHERE codigo=" + codigo);
                    s.executeUpdate("DELETE FROM evento_replicacao WHERE tabela='OBRA_MUSICAL' AND codigo=" + codigo);
                }
                legado.commit();
            }
            reiniciarControle();
        } finally {
            legado.close();
            novo.close();
        }
    }

    @Test
    void batchBlockedBeforeCommit_acrossMarginAndExpiry_neverCommitsWhileNextOwnerResumes() throws Exception {
        // The test drives A's lease through each phase with the database clock instead of sampling a
        // narrow window of a short lease: live, inside the margin, expired. A's local deadline stays in
        // the future, so only the database lease (owner/version/deadline) can refuse A's commit.
        darLeaseParaA(LEASE_LONGO_SEG);
        long prazoLocalA = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        CountDownLatch bloqueado = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        Lado a = new Lado("v07c-a");
        CompletableFuture<Throwable> loteA = a.processar(() -> prazoLocalA, cerca -> new Cerca() {
            @Override
            public boolean podeConfirmarDestino() {
                bloqueado.countDown();
                aguardar(liberar);
                return cerca.podeConfirmarDestino();
            }

            @Override
            public boolean confirmarDestino(ReplicaSink sink) {
                return cerca.confirmarDestino(sink);
            }

            @Override
            public boolean podeConfirmarOrigem() {
                return cerca.podeConfirmarOrigem();
            }
        });
        assertThat(bloqueado.await(30, TimeUnit.SECONDS))
                .as("A reached the commit")
                .isTrue();

        GerenciadorLease leasesB = new GerenciadorLease(config("v07c-b"));
        try (Connection conexaoB = leasesB.conectar()) {
            assertThat(reivindicar(leasesB, conexaoB))
                    .as("B claims a live lease")
                    .doesNotContain(particao);

            long margemMs = GerenciadorLease.MARGEM_SEG * 1000L;
            ajustarLeaseA(margemMs - 100);
            assertThat(reivindicar(leasesB, conexaoB))
                    .as("B claims a lease inside the margin")
                    .doesNotContain(particao);
            assertThat(msRestantes())
                    .as("A's lease was still live, inside the margin, when B's claim ran")
                    .isPositive()
                    .isLessThan(margemMs);

            ajustarLeaseA(-100);
            assertThat(msRestantes()).as("A's lease expired").isNegative();
            long inicioB = System.nanoTime();
            assertThat(reivindicar(leasesB, conexaoB))
                    .as("B claims the expired lease")
                    .contains(particao);

            Lado b = new Lado("v07c-b");
            long prazoB = inicioB + TimeUnit.SECONDS.toNanos(15 - GerenciadorLease.MARGEM_SEG);
            CompletableFuture<Throwable> loteB = b.processar(() -> prazoB, cerca -> cerca);
            // B's batch is in flight (waiting on the replica row A wrote but did not commit) when A resumes.
            Thread.sleep(1_000);
            liberar.countDown();

            assertThat(prazoLocalA)
                    .as("A's local deadline is still in the future")
                    .isGreaterThan(System.nanoTime());
            assertThat(loteA.get(60, TimeUnit.SECONDS)).isInstanceOf(LeasePerdidoException.class);
            assertThat(loteB.get(60, TimeUnit.SECONDS)).isNull();
            assertThat(a.commits).as("A commits after its deadline").hasValue(0);
            assertThat(a.acks).as("A acks after its deadline").hasValue(0);
            assertThat(b.commits).hasValue(1);
            assertThat(b.ackados).as("B acked the event").isGreaterThanOrEqualTo(1);
            assertThat(escalar(novo, "SELECT COUNT(*) FROM obra_musical WHERE titulo='cerca' AND codigo=" + codigo))
                    .isEqualTo(1);
            assertThat(eventosPendentes()).isZero();
        }
    }

    @Test
    void batchBlockedBeforeAck_pastDeadline_leavesEventsToNextOwner() throws Exception {
        AtomicLong prazoA = new AtomicLong(darLeaseParaA(LEASE_LONGO_SEG));
        CountDownLatch bloqueado = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        Lado a = new Lado("v07c-a");
        CompletableFuture<Throwable> loteA = a.processar(prazoA::get, cerca -> new Cerca() {
            @Override
            public boolean podeConfirmarDestino() {
                return cerca.podeConfirmarDestino();
            }

            @Override
            public boolean confirmarDestino(ReplicaSink sink) {
                return cerca.confirmarDestino(sink);
            }

            @Override
            public boolean podeConfirmarOrigem() {
                bloqueado.countDown();
                aguardar(liberar);
                return cerca.podeConfirmarOrigem();
            }
        });
        assertThat(bloqueado.await(30, TimeUnit.SECONDS))
                .as("A committed and reached the ack")
                .isTrue();
        assertThat(a.commits).as("A committed inside its lease").hasValue(1);

        expirarLeaseA(prazoA);
        GerenciadorLease leasesB = new GerenciadorLease(config("v07c-b"));
        esperarAte(() -> msRestantes() <= 0);
        long inicioB = System.nanoTime();
        assertThat(reivindicar(leasesB)).contains(particao);
        Lado b = new Lado("v07c-b");
        long prazoB = inicioB + TimeUnit.SECONDS.toNanos(15 - GerenciadorLease.MARGEM_SEG);
        CompletableFuture<Throwable> loteB = b.processar(() -> prazoB, cerca -> cerca);
        liberar.countDown();

        assertThat(loteA.get(60, TimeUnit.SECONDS)).isInstanceOf(LeasePerdidoException.class);
        assertThat(loteB.get(60, TimeUnit.SECONDS)).isNull();
        assertThat(a.acks).as("A acks after its deadline").hasValue(0);
        assertThat(b.commits).hasValue(1);
        assertThat(b.ackados).as("B acked the event left by A").isGreaterThanOrEqualTo(1);
        assertThat(eventosPendentes()).isZero();
    }

    @Test
    void batchBlockedAfterAuthorization_expiredOwnerRollsBackWhileNextOwnerResumes() throws Exception {
        darLeaseParaA(LEASE_LONGO_SEG);
        long prazoLocalA = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        CountDownLatch bloqueado = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        Lado a = new Lado("v07c-a");
        a.dentroDoCommit = () -> {
            bloqueado.countDown();
            aguardar(liberar);
        };
        CompletableFuture<Throwable> loteA = a.processar(() -> prazoLocalA, cerca -> cerca);
        assertThat(bloqueado.await(30, TimeUnit.SECONDS))
                .as("the fence authorized A's commit")
                .isTrue();

        ajustarLeaseA(-100);
        // A is paused after the read-only authorization. It holds replica-row changes, but no lease-row lock.
        GerenciadorLease leasesB = new GerenciadorLease(config("v07c-b"));
        long inicioFailover = System.nanoTime();
        esperarAte(() -> msRestantes() <= 0);
        assertThat(reivindicar(leasesB))
                .as("B claims the expired lease while A is paused after authorization")
                .contains(particao);
        assertThat(System.nanoTime() - inicioFailover)
                .as("failover while A is paused")
                .isLessThanOrEqualTo(TimeUnit.SECONDS.toNanos(20));
        assertThat(a.commits).hasValue(0);

        long inicioB = System.nanoTime();
        Lado b = new Lado("v07c-b");
        long prazoB = inicioB + TimeUnit.SECONDS.toNanos(15 - GerenciadorLease.MARGEM_SEG);
        CompletableFuture<Throwable> loteB = b.processar(() -> prazoB, cerca -> cerca);
        assertThat(prazoLocalA).as("A's local deadline is still in the future").isGreaterThan(System.nanoTime());
        liberar.countDown();
        assertThat(loteA.get(60, TimeUnit.SECONDS))
                .as("A's expired token cannot confirm the destination")
                .isInstanceOf(LeasePerdidoException.class);
        assertThat(loteB.get(60, TimeUnit.SECONDS)).isNull();
        assertThat(a.commits).as("A commits after its token was superseded").hasValue(0);
        assertThat(a.acks).as("A acks after its token was superseded").hasValue(0);
        assertThat(b.commits).hasValue(1);
        assertThat(b.ackados).as("B acked the event A left").isGreaterThanOrEqualTo(1);
        assertThat(escalar(novo, "SELECT COUNT(*) FROM obra_musical WHERE titulo='cerca' AND codigo=" + codigo))
                .isEqualTo(1);
        novo.rollback();
        assertThat(eventosPendentes()).isZero();
    }

    @Test
    void batchBlockedAfterAuthorization_withoutReclaim_expiredDatabaseDeadlineRejectsCommit() throws Exception {
        darLeaseParaA(LEASE_LONGO_SEG);
        long prazoLocalA = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        CountDownLatch bloqueado = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        Lado a = new Lado("v07c-a");
        a.dentroDoCommit = () -> {
            bloqueado.countDown();
            aguardar(liberar);
        };
        CompletableFuture<Throwable> loteA = a.processar(() -> prazoLocalA, cerca -> cerca);
        assertThat(bloqueado.await(30, TimeUnit.SECONDS))
                .as("the fence authorized A's commit")
                .isTrue();

        ajustarLeaseA(-100);
        esperarAte(() -> msRestantes() <= 0);
        assertThat(prazoLocalA).as("A's local deadline is still in the future").isGreaterThan(System.nanoTime());
        liberar.countDown();

        assertThat(loteA.get(60, TimeUnit.SECONDS))
                .as("the expired database deadline rejects A without a takeover")
                .isInstanceOf(LeasePerdidoException.class);
        assertThat(a.commits).as("A commits after the database deadline").hasValue(0);
        assertThat(a.acks).as("A acks after the database deadline").hasValue(0);
        assertThat(escalar(novo, "SELECT COUNT(*) FROM obra_musical WHERE codigo=" + codigo))
                .isZero();
        novo.rollback();
        assertThat(eventosPendentes()).isEqualTo(1);
    }

    @Test
    void batchBlockedAfterAuthorization_sameOwnerReclaims_oldVersionRejectsCommit() throws Exception {
        darLeaseParaA(LEASE_LONGO_SEG);
        long versaoAntiga = versaoLease();
        long prazoLocalA = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        CountDownLatch bloqueado = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        Lado a = new Lado("v07c-a");
        a.dentroDoCommit = () -> {
            bloqueado.countDown();
            aguardar(liberar);
        };
        CompletableFuture<Throwable> loteA = a.processar(() -> prazoLocalA, cerca -> cerca);
        assertThat(bloqueado.await(30, TimeUnit.SECONDS))
                .as("the fence authorized A's old token")
                .isTrue();

        ajustarLeaseA(-100);
        esperarAte(() -> msRestantes() <= 0);
        GerenciadorLease mesmoWorker = new GerenciadorLease(config("v07c-a"));
        assertThat(reivindicar(mesmoWorker))
                .as("the same worker id reclaims its expired lease")
                .contains(particao);
        assertThat(versaoLease()).as("reclaim increments the fencing version").isGreaterThan(versaoAntiga);
        assertThat(msRestantes())
                .as("the reclaimed lease still satisfies the database deadline")
                .isGreaterThan(GerenciadorLease.MARGEM_SEG * 1000L);
        assertThat(prazoLocalA)
                .as("A's old local deadline is still in the future")
                .isGreaterThan(System.nanoTime());
        liberar.countDown();

        assertThat(loteA.get(60, TimeUnit.SECONDS))
                .as("the old version cannot confirm under the same owner id")
                .isInstanceOf(LeasePerdidoException.class);
        assertThat(a.commits).as("A commits with its old fencing version").hasValue(0);
        assertThat(a.acks).as("A acks with its old fencing version").hasValue(0);
        assertThat(escalar(novo, "SELECT COUNT(*) FROM obra_musical WHERE codigo=" + codigo))
                .isZero();
        novo.rollback();
        assertThat(eventosPendentes()).isEqualTo(1);
    }

    @Test
    void batchBlockedInsideAck_afterAuthorization_lateAckRemovesNothingTheNextOwnerStillNeeds() throws Exception {
        AtomicLong prazoA = new AtomicLong(darLeaseParaA(LEASE_LONGO_SEG));
        CountDownLatch bloqueado = new CountDownLatch(1);
        CountDownLatch liberar = new CountDownLatch(1);
        Lado a = new Lado("v07c-a");
        a.dentroDoAck = () -> {
            bloqueado.countDown();
            aguardar(liberar);
        };
        CompletableFuture<Throwable> loteA = a.processar(prazoA::get, cerca -> cerca);
        assertThat(bloqueado.await(30, TimeUnit.SECONDS))
                .as("the fence authorized A's ack")
                .isTrue();
        assertThat(a.commits).as("A committed inside its lease").hasValue(1);

        // A is stuck inside the ack across the expiry; B takes over and processes the same events.
        expirarLeaseA(prazoA);
        GerenciadorLease leasesB = new GerenciadorLease(config("v07c-b"));
        esperarAte(() -> msRestantes() <= 0);
        long inicioB = System.nanoTime();
        assertThat(reivindicar(leasesB)).contains(particao);
        Lado b = new Lado("v07c-b");
        long prazoB = inicioB + TimeUnit.SECONDS.toNanos(15 - GerenciadorLease.MARGEM_SEG);
        assertThat(b.processar(() -> prazoB, cerca -> cerca).get(60, TimeUnit.SECONDS))
                .isNull();
        assertThat(b.commits).hasValue(1);
        assertThat(b.ackados).as("B acked the events A had not acked").isGreaterThanOrEqualTo(1);
        assertThat(eventosPendentes()).isZero();

        // A newer change arrives for the same key before A's ack resumes.
        try (PreparedStatement ps =
                legado.prepareStatement("UPDATE obra_musical SET titulo='cerca-2' WHERE codigo=?")) {
            ps.setLong(1, codigo);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        legado.commit();
        assertThat(eventosPendentes()).as("the newer change").isEqualTo(1);
        Instant versaoAntes = versaoReplica();

        liberar.countDown();
        assertThat(loteA.get(60, TimeUnit.SECONDS)).isNull();
        assertThat(a.commits).as("A commits nothing more").hasValue(1);
        assertThat(eventosPendentes())
                .as("A's late ack deletes only its own events (already gone), never the newer one")
                .isEqualTo(1);
        assertThat(versaoReplica()).as("replica untouched by A's late ack").isEqualTo(versaoAntes);

        Lado b2 = new Lado("v07c-b");
        assertThat(b2.processar(() -> prazoB, cerca -> cerca).get(60, TimeUnit.SECONDS))
                .isNull();
        assertThat(eventosPendentes()).isZero();
        assertThat(escalar(novo, "SELECT COUNT(*) FROM obra_musical WHERE titulo='cerca-2' AND codigo=" + codigo))
                .isEqualTo(1);
        novo.rollback();
        assertThat(versaoReplica()).as("replica version never regresses").isAfter(versaoAntes);
    }

    // --- one side: a worker id processing the partition with its own connections ---

    @FunctionalInterface
    private interface Envoltorio {
        Cerca envolver(Cerca real);
    }

    private final class Lado {
        final String id;
        final AtomicInteger commits = new AtomicInteger();
        final AtomicInteger acks = new AtomicInteger();
        volatile int ackados;
        /** Runs after read-only authorization and before the atomic database confirmation. */
        Runnable dentroDoCommit = () -> {};
        /** Runs inside the ack, after the fence authorized it and before the origin delete. */
        Runnable dentroDoAck = () -> {};

        Lado(String id) {
            this.id = id;
        }

        CompletableFuture<Throwable> processar(LongSupplier prazo, Envoltorio envoltorio) {
            CompletableFuture<Throwable> fim = new CompletableFuture<>();
            Thread.ofPlatform().name("lote-" + id).start(() -> {
                Config config = config(id);
                try (Connection l = BancosPoc.legado();
                        Connection n = BancosPoc.novo();
                        OracleOutboxSource source = new OracleOutboxSource(l);
                        OracleStateProvider estado = new OracleStateProvider(l);
                        OracleReplicaSink sink = new OracleReplicaSink(n, new Uuid7(CLOCK))) {
                    ReplicaSink contaCommits = new ReplicaSink() {
                        @Override
                        public void upsert(TableSpec spec, List<Linha> linhas) {
                            sink.upsert(spec, linhas);
                        }

                        @Override
                        public void commit() {
                            sink.commit();
                        }
                    };
                    ChangeSource contaAcks = new ChangeSource() {
                        @Override
                        public List<ChangeEvent> poll(String tabela, int p, int max) {
                            return source.poll(tabela, p, max);
                        }

                        @Override
                        public void ack(List<ChangeEvent> eventos) {
                            dentroDoAck.run();
                            source.ack(eventos);
                            acks.incrementAndGet();
                            ackados += (int) eventos.stream()
                                    .filter(e -> e.codigo() == codigo)
                                    .count();
                        }
                    };
                    ProcessadorParticao processador = new ProcessadorParticao(
                            OBRA, particao.numero(), config.loteMax(), contaAcks, estado, contaCommits);
                    Cerca cerca = envoltorio.envolver(new GerenciadorLease(config).cerca(n, particao, prazo));
                    processador.cercar(new Cerca() {
                        @Override
                        public boolean podeConfirmarDestino() {
                            return cerca.podeConfirmarDestino();
                        }

                        @Override
                        public boolean confirmarDestino(ReplicaSink destino) {
                            dentroDoCommit.run();
                            boolean confirmou = cerca.confirmarDestino(destino);
                            if (confirmou) commits.incrementAndGet();
                            return confirmou;
                        }

                        @Override
                        public boolean podeConfirmarOrigem() {
                            return cerca.podeConfirmarOrigem();
                        }
                    });
                    processador.processarLote();
                    fim.complete(null);
                } catch (Throwable t) {
                    fim.complete(t);
                }
            });
            return fim;
        }
    }

    // --- lease control ---

    /** Gives A the partition for {@code segundos}; returns A's local deadline (margin before expiry). */
    private long darLeaseParaA(int segundos) throws SQLException {
        long antes = System.nanoTime();
        try (PreparedStatement ps = novo.prepareStatement("UPDATE lease_particao SET dono='v07c-a', expira_em="
                + "SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'SECOND'), versao=versao+1"
                + " WHERE tabela=? AND particao=?")) {
            ps.setInt(1, segundos);
            ps.setString(2, OBRA.nome());
            ps.setInt(3, particao.numero());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        novo.commit();
        return antes + TimeUnit.SECONDS.toNanos(segundos - GerenciadorLease.MARGEM_SEG);
    }

    /**
     * Expires A's lease (same owner and version) with the database clock and moves A's local deadline,
     * which always precedes the database expiry, to now.
     */
    private void expirarLeaseA(AtomicLong prazoLocalA) throws SQLException {
        ajustarLeaseA(-100);
        prazoLocalA.set(System.nanoTime());
    }

    /**
     * Moves A's current lease (same owner and version) to expire {@code ms} after the database clock
     * now; a negative value expires it.
     */
    private void ajustarLeaseA(long ms) throws SQLException {
        try (PreparedStatement ps = novo.prepareStatement("UPDATE lease_particao SET expira_em="
                + "SYS_EXTRACT_UTC(SYSTIMESTAMP) + NUMTODSINTERVAL(?, 'SECOND')"
                + " WHERE tabela=? AND particao=? AND dono='v07c-a'")) {
            ps.setDouble(1, ms / 1000.0);
            ps.setString(2, OBRA.nome());
            ps.setInt(3, particao.numero());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        novo.commit();
    }

    private List<GerenciadorLease.Particao> reivindicar(GerenciadorLease leases) throws SQLException {
        try (Connection c = leases.conectar()) {
            return reivindicar(leases, c);
        }
    }

    private static List<GerenciadorLease.Particao> reivindicar(GerenciadorLease leases, Connection c)
            throws SQLException {
        List<GerenciadorLease.Particao> claimed = leases.adquirir(c, 48);
        c.commit();
        return claimed;
    }

    private long msRestantes() throws SQLException {
        try (PreparedStatement ps =
                novo.prepareStatement("SELECT " + GerenciadorLease.ms("expira_em - SYS_EXTRACT_UTC(SYSTIMESTAMP)")
                        + " FROM lease_particao WHERE tabela=? AND particao=? AND dono='v07c-a'")) {
            ps.setString(1, OBRA.nome());
            ps.setInt(2, particao.numero());
            try (ResultSet rs = ps.executeQuery()) {
                long ms = rs.next() ? rs.getLong(1) : 0;
                novo.rollback();
                return ms;
            }
        }
    }

    private long versaoLease() throws SQLException {
        try (PreparedStatement ps =
                novo.prepareStatement("SELECT versao FROM lease_particao WHERE tabela=? AND particao=?")) {
            ps.setString(1, OBRA.nome());
            ps.setInt(2, particao.numero());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                long versao = rs.getLong(1);
                novo.rollback();
                return versao;
            }
        }
    }

    private Instant versaoReplica() throws SQLException {
        try (PreparedStatement ps =
                novo.prepareStatement("SELECT origem_updated_at FROM obra_musical WHERE codigo=?")) {
            ps.setLong(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                Instant v = rs.getTimestamp(1).toLocalDateTime().toInstant(ZoneOffset.UTC);
                novo.rollback();
                return v;
            }
        }
    }

    private void reiniciarControle() throws SQLException {
        try (Statement s = novo.createStatement()) {
            s.executeUpdate("UPDATE lease_particao SET dono=NULL, expira_em=NULL");
            s.executeUpdate("DELETE FROM worker_heartbeat");
        }
        novo.commit();
    }

    private long eventosPendentes() throws SQLException {
        long n = escalar(
                legado, "SELECT COUNT(*) FROM evento_replicacao WHERE tabela='OBRA_MUSICAL' AND codigo=" + codigo);
        legado.rollback();
        return n;
    }

    private static Config config(String id) {
        Config original = BancosPoc.config();
        return Config.load(
                key -> switch (key) {
                    case "LEGADO_URL" -> original.legadoUrl();
                    case "LEGADO_USUARIO" -> original.legadoUsuario();
                    case "LEGADO_SENHA" -> original.legadoSenha();
                    case "NOVO_URL" -> original.novoUrl();
                    case "NOVO_USUARIO" -> original.novoUsuario();
                    case "NOVO_SENHA" -> original.novoSenha();
                    case "WORKER_ID" -> id;
                    case "LEASE_SEG" -> "15";
                    default -> null;
                },
                CLOCK);
    }

    private static void aguardar(CountDownLatch latch) {
        try {
            if (!latch.await(60, TimeUnit.SECONDS)) throw new AssertionError("test never released the batch");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean ok() throws Exception;
    }

    private static void esperarAte(Check check) throws Exception {
        long fim = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!check.ok()) {
            if (System.nanoTime() > fim) throw new AssertionError("timeout");
            Thread.sleep(50);
        }
    }

    private static long escalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
