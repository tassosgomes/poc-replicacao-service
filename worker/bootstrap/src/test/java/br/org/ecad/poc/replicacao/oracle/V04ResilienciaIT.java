package br.org.ecad.poc.replicacao.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.org.ecad.poc.replicacao.application.replicacao.ProcessadorParticao;
import br.org.ecad.poc.replicacao.application.replicacao.ResultadoLote;
import br.org.ecad.poc.replicacao.bootstrap.config.Config;
import br.org.ecad.poc.replicacao.bootstrap.execucao.Worker;
import br.org.ecad.poc.replicacao.domain.replicacao.Esquema;
import br.org.ecad.poc.replicacao.domain.replicacao.Estados;
import br.org.ecad.poc.replicacao.domain.replicacao.Linha;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import br.org.ecad.poc.replicacao.domain.replicacao.Uuid7;
import br.org.ecad.poc.replicacao.infra.oracle.replicacao.OutboxSource;
import br.org.ecad.poc.replicacao.infra.oracle.replicacao.ReplicaSinkAdapter;
import br.org.ecad.poc.replicacao.infra.oracle.replicacao.ReplicationSql;
import br.org.ecad.poc.replicacao.infra.oracle.replicacao.StateProviderAdapter;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Real PoC databases (containers {@code poc-legado} and {@code poc-novo}, already up), shared with
 * other runs. Why cleanup never deletes data this test did not create:
 *
 * <ul>
 *   <li>Each test instance draws its own random block of {@value #BLOCO} codes inside a range
 *       reserved for tests ({@value #FAIXA_MIN} onwards), so concurrent runs practically never even
 *       share a code.
 *   <li>Ownership is decided by the database, not by a prior lookup: a code is registered for cleanup
 *       only after this test's own {@code INSERT} into the legacy table (primary key {@code codigo})
 *       committed. If another run holds the code, the insert fails on the key, the transaction is
 *       rolled back, nothing is registered and the test fails without deleting anything. While the
 *       legacy row exists no one else can insert that code, so the replica row and the outbox events
 *       of that code can only come from this test.
 *   <li>A replica row or outbox event already present for a code before the insert (leftover of a
 *       crashed run) also fails the test before the insert, without registering the code.
 *   <li>Cleanup deletes the replica rows first and commits, while the legacy rows are still held;
 *       then it deletes the legacy rows and their outbox events (table and code filtered) in one
 *       legacy transaction, so another run can only insert those codes after everything is gone.
 * </ul>
 */
class V04ResilienciaIT {

    private static final long FAIXA_MIN = 9_000_000_000_000L;
    private static final long FAIXA_BLOCOS = 9_000_000L;
    private static final long BLOCO = 100_000L;
    private static final int IN_MAX = 1000;
    private static final int LOTE_CANDIDATOS = 20_000;
    private static final Clock CLOCK = Clock.systemUTC();
    private static final SecureRandom SORTEIO = new SecureRandom();

    /** First code of this test instance's own block; codes used are base + 1 .. base + BLOCO. */
    private final long base = FAIXA_MIN + SORTEIO.nextLong(FAIXA_BLOCOS) * BLOCO;

    private final List<AutoCloseable> abertos = new ArrayList<>();
    private Connection legado;
    private Connection novo;
    private Dono dono;

    @BeforeEach
    void abrir() {
        legado = aberta(BancosPoc.legado());
        novo = aberta(BancosPoc.novo());
        dono = new Dono(legado, novo);
    }

    @AfterEach
    void limparEFechar() throws Exception {
        try {
            if (dono != null) {
                dono.limpar();
            }
        } finally {
            for (AutoCloseable aberto : abertos) {
                aberto.close();
            }
        }
    }

    @Test
    void missingRow_isCountedAndRemainingBatchIsApplied() throws Exception {
        long missing = base + 1;
        int partition = particaoDe(missing);
        long other = missing + 1;
        while (particaoDe(other) != partition) other++;
        inserirObra(missing, "missing");
        inserirObra(other, "present");
        executar(legado, "DELETE FROM obra_musical WHERE codigo = " + missing);
        legado.commit();
        ResultadoLote result = processador(Esquema.OBRA_MUSICAL, partition).processarLote();
        assertThat(result.anomalias()).isEqualTo(1);
        assertThat(tituloReplica(other)).isEqualTo("present");
        assertThat(eventos(missing, missing)).isZero();
    }

    @Test
    void fonograma_beforeObra_isAcceptedAndReferenceMatches() throws Exception {
        long obra = base + 21;
        long fono = base + 22;
        inserirObra(obra, "obra");
        inserirProprios(
                Esquema.FONOGRAMA,
                List.of(fono),
                "INSERT INTO fonograma (codigo, obra_codigo, titulo, isrc, duracao_seg, excluido) VALUES (?, ?, 'f', 'x', 1, 0)",
                (ps, c) -> {
                    ps.setLong(1, c);
                    ps.setLong(2, obra);
                });
        drenar(Esquema.FONOGRAMA, particaoDe(fono));
        assertThat(escalar(novo, "SELECT obra_codigo FROM fonograma WHERE codigo = " + fono))
                .isEqualTo(obra);
        assertThat(contarReplica("obra_musical", obra, obra)).isZero();
        drenar(Esquema.OBRA_MUSICAL, particaoDe(obra));
        assertThat(escalar(
                        novo,
                        "SELECT COUNT(*) FROM fonograma f JOIN obra_musical o ON o.codigo=f.obra_codigo WHERE f.codigo="
                                + fono))
                .isEqualTo(1);
    }

    @Test
    void failureAfterCommit_reappliesWithoutChangingId() throws Exception {
        long codigo = base + 31;
        inserirObra(codigo, "replay");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var once = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable fault = () -> {
            if (once.compareAndSet(false, true)) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("injected after commit");
            }
        };
        try (Worker worker =
                Worker.iniciar(BancosPoc.config(), CLOCK, Esquema.OBRA_MUSICAL, particaoDe(codigo), fault)) {
            try {
                assertThat(entered.await(30, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
                byte[] id = idReplica(Esquema.OBRA_MUSICAL, codigo);
                assertThat(eventos(codigo, codigo)).isPositive();
                release.countDown();
                esperar(() -> eventos(codigo, codigo) == 0, Duration.ofSeconds(30));
                assertThat(idReplica(Esquema.OBRA_MUSICAL, codigo)).isEqualTo(id);
                assertThat(tituloReplica(codigo)).isEqualTo("replay");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void concurrentInsert_uniqueViolationIsReprocessable() throws Exception {
        long codigo = base + 41;
        inserirObra(codigo, "race");
        List<Linha> linhas = estadoDoLegado(Esquema.OBRA_MUSICAL, codigo);
        Connection rival = aberta(BancosPoc.novo());
        executar(
                rival,
                "INSERT INTO obra_musical (id,codigo,titulo) VALUES (hextoraw('01010101010101010101010101010101'), "
                        + codigo + ", 'rival')");
        ReplicaSinkAdapter sink = new ReplicaSinkAdapter(aberta(BancosPoc.novo()), new Uuid7(CLOCK));
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> sink.upsert(Esquema.OBRA_MUSICAL, linhas));
            Thread.sleep(1000);
            rival.commit();
            assertThatThrownBy(() -> future.get(20, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(br.org.ecad.poc.replicacao.domain.replicacao.ReprocessavelException.class);
        } finally {
            rival.rollback();
        }
        drenar(Esquema.OBRA_MUSICAL, particaoDe(codigo));
        assertThat(eventos(codigo, codigo)).isZero();
        assertThat(contarReplica("obra_musical", codigo, codigo)).isEqualTo(1);
    }

    @Test
    void destinationStopped_workerReconnectsAndDrainsPendingEvents() throws Exception {
        long codigo = base + 51;
        inserirObra(codigo, "before");
        try (Worker worker = Worker.iniciar(BancosPoc.config(), CLOCK)) {
            esperar(() -> eventos(codigo, codigo) == 0, Duration.ofSeconds(30));
            try {
                executar(novo, "UPDATE obra_musical SET titulo = titulo WHERE codigo = " + codigo);
                atualizarObra(codigo, "after");
                // The replica row lock holds the worker's MERGE inside the batch.
                Thread.sleep(1500);
                docker("stop");
                Thread.sleep(2500);
                assertThat(eventos(codigo, codigo)).isPositive();
            } finally {
                docker("start");
            }
            abertos.remove(novo);
            try {
                novo.close();
            } catch (SQLException expected) {
                // The server closed this connection when the container stopped.
            }
            long deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
            while (true) {
                try {
                    Connection replacement = BancosPoc.novo();
                    novo = aberta(replacement);
                    Dono replacementOwner = new Dono(legado, novo);
                    replacementOwner.criados.putAll(dono.criados);
                    dono = replacementOwner;
                    break;
                } catch (RuntimeException e) {
                    if (System.nanoTime() > deadline) throw e;
                    Thread.sleep(1000);
                }
            }
            esperar(() -> eventos(codigo, codigo) == 0 && "after".equals(tituloReplica(codigo)), Duration.ofMinutes(2));
            assertThat(contarReplica("obra_musical", codigo, codigo)).isEqualTo(1);
        }
    }

    private static void docker(String action) throws Exception {
        Process p = new ProcessBuilder("ssh", "-o", "BatchMode=yes", "infra-server", "docker", action, "poc-novo")
                .redirectErrorStream(true)
                .start();
        String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(p.waitFor()).as("docker " + action + ": " + output).isZero();
    }

    // --- helpers ---

    private Connection aberta(Connection conexao) {
        abertos.add(conexao);
        return conexao;
    }

    private ProcessadorParticao processador(TableSpec spec, int particao) {
        Connection origem = aberta(BancosPoc.legado());
        Connection destino = aberta(BancosPoc.novo());
        Config config = BancosPoc.config();
        return new ProcessadorParticao(
                spec,
                particao,
                config.loteMax(),
                new OutboxSource(origem),
                new StateProviderAdapter(origem),
                new ReplicaSinkAdapter(destino, new Uuid7(CLOCK)));
    }

    private void drenar(TableSpec spec, int particao) {
        ProcessadorParticao processador = processador(spec, particao);
        while (processador.processarLote().eventos() > 0) {
            // drain
        }
    }

    private List<Linha> estadoDoLegado(TableSpec spec, long codigo) {
        Estados estados = new StateProviderAdapter(aberta(BancosPoc.legado())).buscar(spec, List.of(codigo));
        return new ArrayList<>(estados.encontradas().values());
    }

    @FunctionalInterface
    private interface Parametros {
        void aplicar(PreparedStatement ps, long codigo) throws SQLException;
    }

    /**
     * One owner of test data: its own legacy and replica connections and the codes it inserted. Each
     * test instance is one owner; the dispute test creates a second one competing for the same code.
     */
    private static final class Dono {

        private final Connection legado;
        private final Connection novo;
        private final java.util.SequencedMap<TableSpec, Set<Long>> criados = new LinkedHashMap<>();

        Dono(Connection legado, Connection novo) {
            this.legado = legado;
            this.novo = novo;
        }

        /**
         * Inserts {@code codigos} into the legacy table of {@code spec} in one transaction and only
         * after the commit registers them for cleanup. A replica row or outbox event already present,
         * or an insert rejected by the primary key (code held by someone else), fails with nothing
         * registered, so cleanup never touches those codes.
         */
        void inserirProprios(TableSpec spec, List<Long> codigos, String sql, Parametros parametros)
                throws SQLException {
            verificarLivres(spec, codigos);
            inserirERegistrar(spec, codigos, sql, parametros);
        }

        /** Fails when a replica row or outbox event already exists for one of {@code codigos}. */
        void verificarLivres(TableSpec spec, List<Long> codigos) throws SQLException {
            long replicaExistentes = contar(novo, ReplicationSql.tabelaSql(spec), spec, codigos);
            long eventosExistentes = contar(legado, "evento_replicacao", spec, codigos);
            assertThat(new long[] {replicaExistentes, eventosExistentes})
                    .as(
                            "pre-existing rows for %s codes %s (replica, outbox): not created by this test,"
                                    + " refusing to run",
                            spec.nome(), resumo(codigos))
                    .containsOnly(0L);
        }

        /** Inserts and commits; registers the codes only after the commit succeeded. */
        void inserirERegistrar(TableSpec spec, List<Long> codigos, String sql, Parametros parametros)
                throws SQLException {
            try (PreparedStatement ps = legado.prepareStatement(sql)) {
                for (long codigo : codigos) {
                    parametros.aplicar(ps, codigo);
                    ps.addBatch();
                }
                ps.executeBatch();
                legado.commit();
            } catch (SQLException e) {
                legado.rollback();
                throw new AssertionError(
                        "insert of " + spec.nome() + " codes " + resumo(codigos)
                                + " rejected (held by another run?); nothing registered for cleanup",
                        e);
            }
            criados.computeIfAbsent(spec, k -> new LinkedHashSet<>()).addAll(codigos);
        }

        Set<Long> registrados(TableSpec spec) {
            return criados.getOrDefault(spec, Set.of());
        }

        /**
         * Deletes only the codes this owner inserted: replica first (committed while the legacy rows
         * still hold the codes), then legacy rows and their outbox events in one legacy transaction (the
         * capture trigger adds a D event on delete, removed by the same transaction).
         */
        void limpar() throws SQLException {
            for (Map.Entry<TableSpec, Set<Long>> entrada : criados.entrySet()) {
                TableSpec spec = entrada.getKey();
                for (List<Long> bloco : blocos(entrada.getValue())) {
                    executar(
                            novo,
                            "DELETE FROM " + ReplicationSql.tabelaSql(spec) + " WHERE codigo IN (" + em(bloco) + ")");
                }
            }
            novo.commit();
            for (Map.Entry<TableSpec, Set<Long>> entrada : criados.reversed().entrySet()) {
                TableSpec spec = entrada.getKey();
                for (List<Long> bloco : blocos(entrada.getValue())) {
                    String em = em(bloco);
                    executar(legado, "DELETE FROM " + ReplicationSql.tabelaSql(spec) + " WHERE codigo IN (" + em + ")");
                    executar(
                            legado,
                            "DELETE FROM evento_replicacao WHERE tabela = '" + spec.nome() + "' AND codigo IN (" + em
                                    + ")");
                }
            }
            legado.commit();
            criados.clear();
        }
    }

    private void inserirProprios(TableSpec spec, List<Long> codigos, String sql, Parametros parametros)
            throws SQLException {
        dono.inserirProprios(spec, codigos, sql, parametros);
    }

    private static List<List<Long>> blocos(Set<Long> codigos) {
        List<Long> todos = new ArrayList<>(codigos);
        List<List<Long>> blocos = new ArrayList<>();
        for (int i = 0; i < todos.size(); i += IN_MAX) {
            blocos.add(todos.subList(i, Math.min(i + IN_MAX, todos.size())));
        }
        return blocos;
    }

    /** Rows of {@code spec} with one of {@code codigos} in {@code tabela} (the outbox is filtered by table). */
    private static long contar(Connection conexao, String tabela, TableSpec spec, List<Long> codigos)
            throws SQLException {
        String filtroTabela = "evento_replicacao".equals(tabela) ? "tabela = '" + spec.nome() + "' AND " : "";
        long total = 0;
        for (int i = 0; i < codigos.size(); i += IN_MAX) {
            List<Long> bloco = codigos.subList(i, Math.min(i + IN_MAX, codigos.size()));
            total += escalar(
                    conexao,
                    "SELECT COUNT(*) FROM " + tabela + " WHERE " + filtroTabela + "codigo IN (" + em(bloco) + ")");
        }
        return total;
    }

    private static String em(List<Long> codigos) {
        return codigos.stream().map(String::valueOf).collect(Collectors.joining(", "));
    }

    private static String resumo(List<Long> codigos) {
        return codigos.size() <= 5
                ? codigos.toString()
                : codigos.size() + " codes in [" + Collections.min(codigos) + ", " + Collections.max(codigos) + "]";
    }

    private static final String INSERT_OBRA = "INSERT INTO obra_musical (codigo, titulo, iswc, genero, situacao,"
            + " excluido) VALUES (?, ?, 'T', 'OUTRO', 'ATIVO', 0)";

    private static Parametros obraComTitulo(String titulo) {
        return (ps, c) -> {
            ps.setLong(1, c);
            ps.setString(2, titulo);
        };
    }

    private void inserirObra(long codigo, String titulo) throws SQLException {
        inserirProprios(Esquema.OBRA_MUSICAL, List.of(codigo), INSERT_OBRA, obraComTitulo(titulo));
    }

    private String tituloLegado(long codigo) throws SQLException {
        try (PreparedStatement ps = legado.prepareStatement("SELECT titulo FROM obra_musical WHERE codigo = ?")) {
            ps.setLong(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void atualizarObra(long codigo, String titulo) throws SQLException {
        try (PreparedStatement ps = legado.prepareStatement("UPDATE obra_musical SET titulo = ? WHERE codigo = ?")) {
            ps.setString(1, titulo);
            ps.setLong(2, codigo);
            ps.executeUpdate();
        }
        legado.commit();
    }

    private void inserirTitular(long codigo) throws SQLException {
        inserirProprios(
                Esquema.TITULAR,
                List.of(codigo),
                "INSERT INTO titular (codigo, nome, tipo, documento, situacao, excluido)"
                        + " VALUES (?, 'nome', 'PF', '1', 'ATIVO', 0)",
                (ps, c) -> ps.setLong(1, c));
    }

    private int particaoDe(long codigo) throws SQLException {
        return (int) escalar(legado, "SELECT ORA_HASH(" + codigo + ", 15) FROM dual");
    }

    private long eventos(long min, long max) throws SQLException {
        return escalar(legado, "SELECT COUNT(*) FROM evento_replicacao WHERE codigo BETWEEN " + min + " AND " + max);
    }

    private long contarReplica(String tabela, long min, long max) throws SQLException {
        return escalar(novo, "SELECT COUNT(*) FROM " + tabela + " WHERE codigo BETWEEN " + min + " AND " + max);
    }

    private String tituloReplica(long codigo) throws SQLException {
        try (PreparedStatement ps = novo.prepareStatement("SELECT titulo FROM obra_musical WHERE codigo = ?")) {
            ps.setLong(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private long excluidoTitularReplica(long codigo) throws SQLException {
        return escalar(novo, "SELECT excluido FROM titular WHERE codigo = " + codigo);
    }

    private byte[] idReplica(TableSpec spec, long codigo) throws SQLException {
        try (PreparedStatement ps =
                novo.prepareStatement("SELECT id FROM " + ReplicationSql.tabelaSql(spec) + " WHERE codigo = ?")) {
            ps.setLong(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("replica row " + codigo).isTrue();
                return rs.getBytes(1);
            }
        }
    }

    private LocalDateTime versaoReplica(String tabela, long codigo) throws SQLException {
        try (PreparedStatement ps =
                novo.prepareStatement("SELECT origem_updated_at FROM " + tabela + " WHERE codigo = ?")) {
            ps.setLong(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, LocalDateTime.class);
            }
        }
    }

    /** Whether the cause chain holds the primary key violation (ORA-00001). */
    private static boolean violacaoDeChave(Throwable erro) {
        for (Throwable t = erro; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                for (SQLException s = sql; s != null; s = s.getNextException()) {
                    if (s.getErrorCode() == 1 || String.valueOf(s.getMessage()).contains("ORA-00001")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static long escalar(Connection conexao, String sql) throws SQLException {
        try (PreparedStatement ps = conexao.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void executar(Connection conexao, String sql) throws SQLException {
        try (PreparedStatement ps = conexao.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    @FunctionalInterface
    private interface Condicao {
        boolean ok() throws SQLException;
    }

    private void esperar(Condicao condicao, Duration limite) throws Exception {
        long fim = System.nanoTime() + limite.toNanos();
        while (!condicao.ok()) {
            if (System.nanoTime() > fim) {
                throw new AssertionError("condition not met within " + limite);
            }
            Thread.sleep(10);
        }
    }
}
