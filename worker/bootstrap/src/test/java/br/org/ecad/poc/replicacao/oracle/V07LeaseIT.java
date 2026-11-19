package br.org.ecad.poc.replicacao.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.ecad.poc.replicacao.bootstrap.config.Config;
import br.org.ecad.poc.replicacao.domain.replicacao.Linha;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * V-07 against the real PoC databases. Each worker is a separate JVM running {@code Main worker};
 * {@code kill -KILL}, {@code kill -TERM} and {@code kill -STOP/-CONT} play the role of
 * {@code docker kill -s KILL}, {@code docker stop} and {@code docker pause} (no worker container
 * exists in this environment, so no {@code --cpus} limit applies and the workers share the host CPU).
 *
 * <p>The control tables {@code lease_particao} and {@code worker_heartbeat} are owned by this test
 * while it runs: before and after each test they are reset. Replica test data lives in a random
 * block of codes reserved for tests and is removed with the same ownership rules as
 * {@link V04ResilienciaIT}.
 */
class V07LeaseIT {

    private static final System.Logger LOG = System.getLogger(V07LeaseIT.class.getName());
    private static final java.time.Clock CLOCK = java.time.Clock.systemUTC();
    /**
     * Lease of the workers under test (design parameter {@code LEASE_SEG}, default 15). After a kill the
     * survivor claims between {@code LEASE_SEG - LEASE_SEG/3} and {@code LEASE_SEG + LEASE_SEG/3} (fixed
     * cycle, never shortened), then needs 1-4 s to open the processor connections; with 15 s the claim
     * alone can take up to 20 s, so these scenarios run with 12 s (decision in
     * {@code docs/escopo-construcao.md}, Configuração).
     */
    private static final int LEASE_SEG = 12;

    private static final int PARTICOES = 48;
    private static final long FAIXA_MIN = 9_000_000_000_000L;
    private static final long FAIXA_BLOCOS = 9_000_000L;
    private static final long BLOCO = 100_000L;
    private static final SecureRandom SORTEIO = new SecureRandom();

    @TempDir
    Path dir;

    private final List<Process> processos = new ArrayList<>();
    private final Map<String, Path> logs = new HashMap<>();
    private final List<Long> codigosCriados = new ArrayList<>();
    private final long base = FAIXA_MIN + SORTEIO.nextLong(FAIXA_BLOCOS) * BLOCO;
    private Connection legado;
    private Connection novo;

    @BeforeEach
    void abrir() throws Exception {
        legado = BancosPoc.legado();
        novo = BancosPoc.novo();
        reiniciarControle();
    }

    @AfterEach
    void encerrar() throws Exception {
        try {
            for (Process p : processos) {
                if (p.isAlive()) {
                    sinal("CONT", p);
                    p.destroyForcibly();
                }
            }
            for (Process p : processos) p.waitFor(30, TimeUnit.SECONDS);
            limparDados();
            reiniciarControle();
        } finally {
            legado.close();
            novo.close();
        }
    }

    @Test
    void oneWorker_ownsAll48Partitions() throws Exception {
        Process a = iniciar("v07-a");
        esperar("A owns 48", Duration.ofSeconds(60), () -> donos().getOrDefault("v07-a", 0L) == PARTICOES);
        assertThat(donos()).containsOnlyKeys("v07-a");
        assertThat(escalar(novo, "SELECT COUNT(DISTINCT tabela || particao) FROM lease_particao WHERE dono='v07-a'"))
                .isEqualTo(PARTICOES);
        esperar("A reports 48 partitions", Duration.ofSeconds(60), () -> ultimaParticoes("v07-a") == PARTICOES);
        assertThat(a.isAlive()).isTrue();
    }

    @Test
    void secondWorker_convergesTo24And24_neverTwoOwners() throws Exception {
        iniciar("v07-a");
        esperar("A owns 48", Duration.ofSeconds(60), () -> donos().getOrDefault("v07-a", 0L) == PARTICOES);
        Instant inicioB = CLOCK.instant();
        iniciar("v07-b");
        esperar("24/24", Duration.ofSeconds(90), () -> {
            Map<String, Long> d = donos();
            return d.getOrDefault("v07-a", 0L) == 24 && d.getOrDefault("v07-b", 0L) == 24;
        });
        esperar("both CSVs report 24", Duration.ofSeconds(60), () -> {
            return ultimaParticoes("v07-a") == 24 && ultimaParticoes("v07-b") == 24;
        });
        Instant convergiu = CLOCK.instant();
        // Observe at least six 5 s metric windows (and two full leases) after convergence.
        Thread.sleep(Math.max(2 * LEASE_SEG * 1000L, 30_000L));
        Instant fim = CLOCK.instant();
        // The database allows one owner per partition (primary key); the CSV gauges prove the processors.
        // Both workers sample on the same host clock at the same 5 s boundaries, so rows pair by ts exactly.
        TreeMap<Instant, Integer> a = alinhadas(csv("v07-a"));
        TreeMap<Instant, Integer> b = alinhadas(csv("v07-b"));
        assertThat(b).as("B wrote windows").isNotEmpty();
        Instant primeiraB = b.firstKey();
        int naTransicao = 0;
        int depois = 0;
        StringBuilder trilha = new StringBuilder();
        for (Map.Entry<Instant, Integer> ra : a.subMap(inicioB, true, fim, true).entrySet()) {
            Instant t = ra.getKey();
            Integer pb = b.get(t);
            // Before B's first window B has no processor (its sampler starts before its coordinator).
            if (!t.isBefore(primeiraB)) assertThat(pb).as("B window at %s", t).isNotNull();
            int soma = ra.getValue() + (pb == null ? 0 : pb);
            trilha.append(t)
                    .append(" A=")
                    .append(ra.getValue())
                    .append(" B=")
                    .append(pb)
                    .append('\n');
            assertThat(soma).as("window %s: A=%d B=%s", t, ra.getValue(), pb).isLessThanOrEqualTo(PARTICOES);
            if (pb != null) {
                if (t.isAfter(convergiu)) depois++;
                else naTransicao++;
            }
        }
        for (Instant t : b.subMap(inicioB, true, fim, true).keySet()) {
            assertThat(a).as("A window at %s", t).containsKey(t);
        }
        LOG.log(System.Logger.Level.INFO, "V07 paired windows from B start to end:\n" + trilha);
        assertThat(naTransicao)
                .as("paired windows between B start and convergence")
                .isGreaterThanOrEqualTo(1);
        assertThat(depois).as("paired windows after convergence").isGreaterThanOrEqualTo(5);
        assertThat(donos()).containsEntry("v07-a", 24L).containsEntry("v07-b", 24L);
    }

    /**
     * Failover is the real takeover: from the KILL of A until B owns the 48 partitions in
     * {@code lease_particao} <em>and</em> runs a processor on each (B logs {@code all 48 owned partitions
     * active} once its 24 extra processors are running). Both instants are read from the database clock
     * (the one that rules the leases): the host wall clock steps backwards every ~32 s and its monotonic
     * clock runs ~10 % fast. The first instant is read before the signal and the last one after both
     * conditions are observed, so the measure only errs upwards (by one poll, at most 100 ms, plus round
     * trips). The 5 s metric window is not part of it: it quantizes the observation, not the takeover;
     * the CSV still has to reach 48 and is reported for comparison (decision in
     * {@code docs/escopo-construcao.md}, Configuração).
     */
    @Test
    void killedWorker_otherReachesAll48WithinTwentySeconds() throws Exception {
        Process a = iniciar("v07-a");
        esperar("A owns 48", Duration.ofSeconds(60), () -> donos().getOrDefault("v07-a", 0L) == PARTICOES);
        iniciar("v07-b");
        converge24();
        // Wait one full lease cycle so A is mid-lease, not just after a renewal.
        Thread.sleep(7_000);
        long ativasAntes = linhasTodasAtivas("v07-b", PARTICOES);
        Instant morteWall = CLOCK.instant();
        LocalDateTime morte = agoraBanco();
        sinal("KILL", a);
        assertThat(a.waitFor(10, TimeUnit.SECONDS)).isTrue();
        esperar(
                "B owns 48 in lease_particao with 48 processors active",
                Duration.ofSeconds(90),
                Duration.ofMillis(100),
                () -> linhasTodasAtivas("v07-b", PARTICOES) > ativasAntes
                        && donos().getOrDefault("v07-b", 0L) == PARTICOES);
        LocalDateTime tomada = agoraBanco();
        Duration failover = Duration.between(morte, tomada);
        assertThat(donos()).containsOnlyKeys("v07-b").containsEntry("v07-b", 48L);
        // The CSV gauge still has to report the 48 (not timed: it is sampled on 5 s boundaries).
        AtomicReference<Instant> janela = new AtomicReference<>();
        esperar("B CSV reports 48", Duration.ofSeconds(30), () -> {
            for (Linha l : csv("v07-b")) {
                if (l.ts().isAfter(morteWall) && l.particoes() == PARTICOES) {
                    janela.set(l.ts());
                    return true;
                }
            }
            return false;
        });
        LOG.log(
                System.Logger.Level.INFO,
                "V07 failover (KILL -> B owns 48 with 48 processors active, database clock): "
                        + failover.toMillis() + " ms; first CSV window with 48 at +"
                        + Duration.between(morteWall, janela.get()).toMillis() + " ms (host wall clock)");
        assertThat(failover).isLessThanOrEqualTo(Duration.ofSeconds(20));
    }

    /** Lines of the worker log announcing that all {@code n} owned partitions have a processor. */
    private long linhasTodasAtivas(String id, int n) throws IOException {
        String marca = "all " + n + " owned partitions active";
        try (var linhas = Files.lines(logs.get(id))) {
            return linhas.filter(l -> l.contains(marca)).count();
        }
    }

    private LocalDateTime agoraBanco() throws SQLException {
        try (Statement s = novo.createStatement();
                ResultSet rs = s.executeQuery("SELECT SYS_EXTRACT_UTC(SYSTIMESTAMP) FROM dual")) {
            rs.next();
            return rs.getObject(1, LocalDateTime.class);
        } finally {
            novo.rollback();
        }
    }

    @Test
    void sigterm_releasesLeasesAndOtherTakesOverBeforeExpiry() throws Exception {
        Process a = iniciar("v07-a");
        esperar("A owns 48", Duration.ofSeconds(60), () -> donos().getOrDefault("v07-a", 0L) == PARTICOES);
        iniciar("v07-b");
        converge24();
        // TERM right after A renewed: left alone, its leases would stay valid for almost LEASE_SEG.
        esperar(
                "A just renewed",
                Duration.ofSeconds(3L * LEASE_SEG),
                () -> msAteVencerA() > LEASE_SEG * 1000L - 1_000L);
        LocalDateTime vencimentoA = primeiroVencimentoA();
        long t0 = System.nanoTime();
        sinal("TERM", a);
        assertThat(a.waitFor(20, TimeUnit.SECONDS)).as("A exits after SIGTERM").isTrue();
        LOG.log(
                System.Logger.Level.INFO,
                "V07 SIGTERM -> A exited: "
                        + Duration.ofNanos(System.nanoTime() - t0).toMillis() + " ms");
        // Released explicitly: no row still names A, expired or not.
        assertThat(escalar(novo, "SELECT COUNT(*) FROM lease_particao WHERE dono='v07-a'"))
                .isZero();
        assertThat(escalar(novo, "SELECT COUNT(*) FROM worker_heartbeat WHERE worker_id='v07-a'"))
                .isZero();
        esperar("B owns 48", Duration.ofSeconds(30), () -> donos().getOrDefault("v07-b", 0L) == PARTICOES);
        Duration ate = Duration.ofNanos(System.nanoTime() - t0);
        LOG.log(System.Logger.Level.INFO, "V07 SIGTERM -> B owns 48 in DB: " + ate.toMillis() + " ms");
        // Taken over before A's leases, as they stood when TERM was sent, would have expired by themselves
        // (database clock, the one that rules the leases).
        assertThat(antesDe(vencimentoA))
                .as("B owns 48 before A's leases (earliest expiry %s UTC) would expire", vencimentoA)
                .isTrue();
    }

    private long msAteVencerA() throws SQLException {
        long ms = escalar(
                novo,
                "SELECT NVL(ROUND((CAST(MIN(expira_em) AS DATE) - CAST(SYS_EXTRACT_UTC(SYSTIMESTAMP) AS DATE))"
                        + " * 86400000), -1) FROM lease_particao WHERE dono='v07-a'");
        novo.rollback();
        return ms;
    }

    private LocalDateTime primeiroVencimentoA() throws SQLException {
        try (Statement s = novo.createStatement();
                ResultSet rs = s.executeQuery("SELECT MIN(expira_em) FROM lease_particao WHERE dono='v07-a'")) {
            rs.next();
            LocalDateTime v = rs.getObject(1, LocalDateTime.class);
            assertThat(v).as("A holds leases before TERM").isNotNull();
            return v;
        } finally {
            novo.rollback();
        }
    }

    private boolean antesDe(LocalDateTime instante) throws SQLException {
        try (PreparedStatement ps = novo.prepareStatement(
                "SELECT CASE WHEN SYS_EXTRACT_UTC(SYSTIMESTAMP) < ? THEN 1 ELSE 0 END FROM dual")) {
            ps.setObject(1, instante);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) == 1;
            }
        } finally {
            novo.rollback();
        }
    }

    @Test
    void pausedLongerThanLease_replicaVersionNeverRegresses() throws Exception {
        List<Long> codigos = new ArrayList<>();
        for (int i = 1; i <= 24; i++) codigos.add(base + i);
        inserirObras(codigos);
        Process a = iniciar("v07-a");
        esperar("A owns 48", Duration.ofSeconds(60), () -> donos().getOrDefault("v07-a", 0L) == PARTICOES);
        iniciar("v07-b");
        converge24();
        esperar("replica holds all codes", Duration.ofSeconds(60), () -> contarReplica(codigos) == codigos.size());

        AtomicBoolean continuar = new AtomicBoolean(true);
        AtomicReference<Throwable> falha = new AtomicReference<>();
        AtomicReference<String> regressao = new AtomicReference<>();
        Thread escritor = Thread.ofPlatform().name("v07-escritor").start(() -> {
            try (Connection c = BancosPoc.legado()) {
                int n = 0;
                while (continuar.get()) {
                    n++;
                    for (long codigo : codigos) {
                        try (PreparedStatement ps =
                                c.prepareStatement("UPDATE obra_musical SET titulo=? WHERE codigo=?")) {
                            ps.setString(1, "v" + n);
                            ps.setLong(2, codigo);
                            ps.executeUpdate();
                        }
                    }
                    c.commit();
                    Thread.sleep(500);
                }
            } catch (Throwable t) {
                falha.compareAndSet(null, t);
            }
        });
        Thread leitor = Thread.ofPlatform().name("v07-leitor").start(() -> {
            Map<Long, LocalDateTime> visto = new HashMap<>();
            try (Connection c = BancosPoc.novo();
                    PreparedStatement ps = c.prepareStatement(
                            "SELECT codigo, origem_updated_at FROM obra_musical WHERE codigo BETWEEN ? AND ?")) {
                ps.setLong(1, base + 1);
                ps.setLong(2, base + codigos.size());
                while (continuar.get()) {
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long codigo = rs.getLong(1);
                            LocalDateTime atual = rs.getObject(2, LocalDateTime.class);
                            LocalDateTime anterior = visto.put(codigo, atual);
                            if (anterior != null && atual != null && atual.isBefore(anterior)) {
                                regressao.compareAndSet(null, codigo + ": " + anterior + " -> " + atual);
                            }
                        }
                    }
                    c.rollback();
                    Thread.sleep(150);
                }
            } catch (Throwable t) {
                falha.compareAndSet(null, t);
            }
        });
        try {
            Thread.sleep(3_000);
            sinal("STOP", a);
            Thread.sleep((LEASE_SEG + 8) * 1000L);
            esperar(
                    "B took over all 48 while A is frozen",
                    Duration.ofSeconds(30),
                    () -> donos().getOrDefault("v07-b", 0L) == PARTICOES);
            sinal("CONT", a);
            Thread.sleep(10_000);
            converge24();
            Thread.sleep(5_000);
        } finally {
            continuar.set(false);
            escritor.join();
            leitor.join();
            sinal("CONT", a);
        }
        assertThat(falha.get()).as("helper thread failure").isNull();
        assertThat(regressao.get()).as("origem_updated_at regressed").isNull();
        // Everything written while A was frozen reached the replica.
        esperar("replica converged to legado", Duration.ofSeconds(60), () -> divergentes(codigos) == 0);
    }

    // --- processes ---

    private Process iniciar(String id) throws IOException {
        Config c = BancosPoc.config();
        Path resultados = dir.resolve("resultados");
        ProcessBuilder pb = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                "br.org.ecad.poc.replicacao.bootstrap.Main",
                "worker");
        Map<String, String> env = pb.environment();
        env.put("WORKER_ID", id);
        env.put("RESULTADOS_DIR", resultados.toString());
        env.put("LEASE_SEG", String.valueOf(LEASE_SEG));
        env.put("LEGADO_URL", c.legadoUrl());
        env.put("LEGADO_USUARIO", c.legadoUsuario());
        env.put("LEGADO_SENHA", c.legadoSenha());
        env.put("NOVO_URL", c.novoUrl());
        env.put("NOVO_USUARIO", c.novoUsuario());
        env.put("NOVO_SENHA", c.novoSenha());
        Files.createDirectories(Path.of("target", "v07-logs"));
        Path log = Path.of("target", "v07-logs", id + "-" + System.nanoTime() + ".log");
        logs.put(id, log);
        pb.redirectErrorStream(true).redirectOutput(log.toFile());
        Process p = pb.start();
        processos.add(p);
        return p;
    }

    private static void sinal(String nome, Process p) throws IOException, InterruptedException {
        Process kill = new ProcessBuilder("kill", "-s", nome, String.valueOf(p.pid())).start();
        kill.waitFor(10, TimeUnit.SECONDS);
    }

    // --- observation ---

    private record Linha(Instant ts, int particoes) {}

    private List<Linha> csv(String id) throws IOException {
        Path f = dir.resolve("resultados").resolve("worker-" + id + ".csv");
        List<Linha> linhas = new ArrayList<>();
        if (!Files.exists(f)) return linhas;
        List<String> todas = Files.readAllLines(f);
        for (String l : todas.stream().skip(1).toList()) {
            String[] c = l.split(",", -1);
            if (c.length < 2 || c[0].isBlank()) continue;
            linhas.add(new Linha(Instant.parse(c[0]), Integer.parseInt(c[1])));
        }
        return linhas;
    }

    private int ultimaParticoes(String id) throws IOException {
        List<Linha> l = csv(id);
        return l.isEmpty() ? -1 : l.get(l.size() - 1).particoes();
    }

    /** Windows on the 5 s boundaries (the row written at shutdown carries its own instant). */
    private static TreeMap<Instant, Integer> alinhadas(List<Linha> linhas) {
        TreeMap<Instant, Integer> m = new TreeMap<>();
        for (Linha l : linhas) {
            if (l.ts().toEpochMilli() % 5_000 == 0) m.put(l.ts(), l.particoes());
        }
        return m;
    }

    private Map<String, Long> donos() throws SQLException {
        Map<String, Long> donos = new HashMap<>();
        try (Statement s = novo.createStatement();
                ResultSet rs = s.executeQuery("SELECT dono, COUNT(*) FROM lease_particao WHERE dono IS NOT NULL"
                        + " AND expira_em > SYS_EXTRACT_UTC(SYSTIMESTAMP) GROUP BY dono")) {
            while (rs.next()) donos.put(rs.getString(1), rs.getLong(2));
        }
        novo.rollback();
        return donos;
    }

    private void converge24() throws Exception {
        esperar("24/24", Duration.ofSeconds(90), () -> {
            Map<String, Long> d = donos();
            return d.getOrDefault("v07-a", 0L) == 24 && d.getOrDefault("v07-b", 0L) == 24;
        });
    }

    private void esperar(String o, Duration limite, Check check) throws Exception {
        esperar(o, limite, Duration.ofMillis(250), check);
    }

    private void esperar(String o, Duration limite, Duration intervalo, Check check) throws Exception {
        long fim = System.nanoTime() + limite.toNanos();
        while (!check.ok()) {
            if (System.nanoTime() > fim) throw new AssertionError("timeout waiting for: " + o);
            Thread.sleep(intervalo.toMillis());
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean ok() throws Exception;
    }

    // --- data ---

    /**
     * A worker killed just before may leave its server session finishing an in-flight lease statement
     * (the database only notices the closed socket when it replies); this full-table reset can then be
     * picked as the victim of a deadlock with it (ORA-00060). Oracle rolls back only the victim statement,
     * so the reset is retried once that session is gone.
     */
    private void reiniciarControle() throws Exception {
        for (int tentativa = 1; ; tentativa++) {
            try (Statement s = novo.createStatement()) {
                s.executeUpdate("UPDATE lease_particao SET dono=NULL, expira_em=NULL");
                s.executeUpdate("DELETE FROM worker_heartbeat");
                novo.commit();
                return;
            } catch (SQLException e) {
                novo.rollback();
                if (e.getErrorCode() != 60 || tentativa == 5) throw e;
                LOG.log(System.Logger.Level.INFO, "control reset hit ORA-00060 (attempt " + tentativa + "); retrying");
                Thread.sleep(1_000);
            }
        }
    }

    private void inserirObras(List<Long> codigos) throws SQLException {
        try (PreparedStatement ps = legado.prepareStatement("INSERT INTO obra_musical (codigo, titulo, iswc, genero,"
                + " situacao, excluido) VALUES (?, 'v0', 'T', 'OUTRO', 'ATIVO', 0)")) {
            for (long c : codigos) {
                ps.setLong(1, c);
                ps.addBatch();
            }
            ps.executeBatch();
            legado.commit();
        } catch (SQLException e) {
            legado.rollback();
            throw new AssertionError("insert rejected (code block held by another run?); nothing registered", e);
        }
        codigosCriados.addAll(codigos);
    }

    private long contarReplica(List<Long> codigos) throws SQLException {
        long n = escalar(
                novo,
                "SELECT COUNT(*) FROM obra_musical WHERE codigo BETWEEN " + (base + 1) + " AND "
                        + (base + codigos.size()));
        novo.rollback();
        return n;
    }

    private long divergentes(List<Long> codigos) throws SQLException {
        Map<Long, String> origem = new HashMap<>();
        try (Statement s = legado.createStatement();
                ResultSet rs = s.executeQuery("SELECT codigo, titulo FROM obra_musical WHERE codigo BETWEEN "
                        + (base + 1) + " AND " + (base + codigos.size()))) {
            while (rs.next()) origem.put(rs.getLong(1), rs.getString(2));
        }
        legado.rollback();
        long diferentes = 0;
        try (Statement s = novo.createStatement();
                ResultSet rs = s.executeQuery("SELECT codigo, titulo FROM obra_musical WHERE codigo BETWEEN "
                        + (base + 1) + " AND " + (base + codigos.size()))) {
            Map<Long, String> destino = new HashMap<>();
            while (rs.next()) destino.put(rs.getLong(1), rs.getString(2));
            for (Map.Entry<Long, String> e : origem.entrySet()) {
                if (!e.getValue().equals(destino.get(e.getKey()))) diferentes++;
            }
        }
        novo.rollback();
        return diferentes;
    }

    /** Replica first (committed while legacy rows hold the codes), then legacy rows and their events. */
    private void limparDados() throws SQLException {
        if (codigosCriados.isEmpty()) return;
        String em = codigosCriados.stream()
                .map(String::valueOf)
                .reduce((x, y) -> x + "," + y)
                .orElseThrow();
        try (Statement s = novo.createStatement()) {
            s.executeUpdate("DELETE FROM obra_musical WHERE codigo IN (" + em + ")");
        }
        novo.commit();
        try (Statement s = legado.createStatement()) {
            s.executeUpdate("DELETE FROM obra_musical WHERE codigo IN (" + em + ")");
            s.executeUpdate("DELETE FROM evento_replicacao WHERE tabela='OBRA_MUSICAL' AND codigo IN (" + em + ")");
        }
        legado.commit();
        codigosCriados.clear();
    }

    private static long escalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
