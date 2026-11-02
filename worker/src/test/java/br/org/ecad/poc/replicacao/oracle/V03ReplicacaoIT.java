package br.org.ecad.poc.replicacao.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.org.ecad.poc.replicacao.Config;
import br.org.ecad.poc.replicacao.core.Esquema;
import br.org.ecad.poc.replicacao.core.Estados;
import br.org.ecad.poc.replicacao.core.Linha;
import br.org.ecad.poc.replicacao.core.StateProvider;
import br.org.ecad.poc.replicacao.core.TableSpec;
import br.org.ecad.poc.replicacao.core.Uuid7;
import br.org.ecad.poc.replicacao.worker.ProcessadorParticao;
import br.org.ecad.poc.replicacao.worker.ResultadoLote;
import br.org.ecad.poc.replicacao.worker.Worker;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
class V03ReplicacaoIT {

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
    void worker_updateInLegado_appearsInReplicaInUnderOneSecondAndEventIsRemoved() throws Exception {
        long codigo = base + 1;
        inserirObra(codigo, "inicial");
        try (Worker worker = Worker.iniciar(BancosPoc.config(), CLOCK)) {
            esperar(() -> tituloReplica(codigo) != null, Duration.ofSeconds(10));

            long inicio = System.nanoTime();
            atualizarObra(codigo, "alterado");
            esperar(() -> "alterado".equals(tituloReplica(codigo)), Duration.ofSeconds(5));
            long ms = Duration.ofNanos(System.nanoTime() - inicio).toMillis();

            assertThat(ms).as("latency in ms").isLessThan(1000);
            esperar(() -> eventos(codigo, codigo) == 0, Duration.ofSeconds(2));
        }
    }

    @Test
    void processarLote_twoConsecutiveUpdates_replicaHasTheSecondAndTheIdDoesNotChange() throws Exception {
        long codigo = base + 2;
        inserirObra(codigo, "primeiro");
        drenar(Esquema.OBRA_MUSICAL, particaoDe(codigo));
        byte[] idInicial = idReplica(Esquema.OBRA_MUSICAL, codigo);

        atualizarObra(codigo, "segundo");
        atualizarObra(codigo, "terceiro");
        drenar(Esquema.OBRA_MUSICAL, particaoDe(codigo));

        assertThat(tituloReplica(codigo)).isEqualTo("terceiro");
        assertThat(idReplica(Esquema.OBRA_MUSICAL, codigo)).isEqualTo(idInicial).hasSize(16);
        assertThat(eventos(codigo, codigo)).isZero();
    }

    @Test
    void upsert_sameBatchAppliedAgain_keepsTheIdentifierIdentical() throws Exception {
        long codigo = base + 3;
        inserirObra(codigo, "lote");
        List<Linha> lote = new ArrayList<>(estadoDoLegado(Esquema.OBRA_MUSICAL, codigo));
        AtomicInteger geracoes = new AtomicInteger();
        Clock contador = new Clock() {
            @Override
            public ZoneId getZone() {
                return CLOCK.getZone();
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                geracoes.incrementAndGet();
                return CLOCK.instant();
            }
        };
        OracleReplicaSink sink = new OracleReplicaSink(aberta(BancosPoc.novo()), new Uuid7(contador));

        sink.upsert(Esquema.OBRA_MUSICAL, lote);
        sink.commit();
        byte[] antes = idReplica(Esquema.OBRA_MUSICAL, codigo);
        assertThat(geracoes).as("ids generated for the insert").hasValue(1);
        sink.upsert(Esquema.OBRA_MUSICAL, lote);
        sink.commit();

        assertThat(geracoes).as("ids generated after the reapplied batch").hasValue(1);
        assertThat(idReplica(Esquema.OBRA_MUSICAL, codigo)).isEqualTo(antes);
        assertThat(contarReplica("obra_musical", codigo, codigo)).isEqualTo(1);
    }

    @Test
    void processarLote_softDelete_keepsTheRowInTheReplicaWithExcluidoOne() throws Exception {
        long codigo = base + 4;
        inserirTitular(codigo);
        drenar(Esquema.TITULAR, particaoDe(codigo));
        assertThat(excluidoTitularReplica(codigo)).isZero();

        executar(legado, "UPDATE titular SET excluido = 1 WHERE codigo = " + codigo);
        legado.commit();
        drenar(Esquema.TITULAR, particaoDe(codigo));

        assertThat(contarReplica("titular", codigo, codigo)).isEqualTo(1);
        assertThat(excluidoTitularReplica(codigo)).isEqualTo(1);
    }

    @Test
    void processarLote_olderStateArrivingLater_neverOverwritesTheNewerOne() throws Exception {
        long codigo = base + 5;
        inserirObra(codigo, "inicial");
        drenar(Esquema.OBRA_MUSICAL, particaoDe(codigo));
        atualizarObra(codigo, "recente");
        drenar(Esquema.OBRA_MUSICAL, particaoDe(codigo));
        LocalDateTime versaoRecente = versaoReplica("obra_musical", codigo);

        atualizarObra(codigo, "descartado");
        OracleStateProvider real = new OracleStateProvider(aberta(BancosPoc.legado()));
        StateProvider envelhecido = (spec, codigos) -> {
            Estados estados = real.buscar(spec, codigos);
            List<Linha> velhas = estados.encontradas().values().stream()
                    .map(l -> new Linha(
                            l.codigo(),
                            versaoRecente.minusHours(1).toInstant(ZoneOffset.UTC),
                            List.of("velho", "x", "x", "ATIVO", 0)))
                    .toList();
            return new Estados(Map.of(velhas.get(0).codigo(), velhas.get(0)), estados.ausentes());
        };
        ProcessadorParticao processador = new ProcessadorParticao(
                Esquema.OBRA_MUSICAL,
                particaoDe(codigo),
                500,
                new OracleOutboxSource(aberta(BancosPoc.legado())),
                envelhecido,
                new OracleReplicaSink(aberta(BancosPoc.novo()), new Uuid7(CLOCK)));
        while (processador.processarLote().eventos() > 0) {
            // drain
        }

        assertThat(tituloReplica(codigo)).isEqualTo("recente");
        assertThat(versaoReplica("obra_musical", codigo)).isEqualTo(versaoRecente);
    }

    @Test
    void processarLote_batchOf500Events_removesAll500FromTheOutbox() throws Exception {
        int particao = 5;
        List<Long> codigos = new ArrayList<>();
        try (PreparedStatement ps =
                legado.prepareStatement("SELECT n FROM (SELECT " + base + " + LEVEL n FROM dual CONNECT BY LEVEL <= "
                        + LOTE_CANDIDATOS + ") WHERE ORA_HASH(n, 15) = ? FETCH FIRST 500 ROWS ONLY")) {
            ps.setInt(1, particao);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    codigos.add(rs.getLong(1));
                }
            }
        }
        assertThat(codigos).hasSize(500);
        inserirProprios(
                Esquema.OBRA_MUSICAL,
                codigos,
                "INSERT INTO obra_musical (codigo, titulo, iswc, genero, situacao, excluido)"
                        + " VALUES (?, 'lote', 'T', 'OUTRO', 'ATIVO', 0)",
                (ps, codigo) -> ps.setLong(1, codigo));
        assertThat(contar(legado, "evento_replicacao", Esquema.OBRA_MUSICAL, codigos))
                .isEqualTo(500);

        ResultadoLote resultado = processador(Esquema.OBRA_MUSICAL, particao).processarLote();

        assertThat(resultado.eventos()).isEqualTo(500);
        assertThat(contar(legado, "evento_replicacao", Esquema.OBRA_MUSICAL, codigos))
                .isZero();
        assertThat(contar(novo, "obra_musical", Esquema.OBRA_MUSICAL, codigos)).isEqualTo(500);
    }

    @Test
    void cleanup_twoOwnersDisputingTheSameCode_loserCleansUpWithoutTouchingTheWinnersData() throws Exception {
        long codigo = base + 6;
        List<Long> disputado = List.of(codigo);
        Dono rival = new Dono(aberta(BancosPoc.legado()), aberta(BancosPoc.novo()));

        // Both owners check the code is free before either inserts (the race window).
        rival.verificarLivres(Esquema.OBRA_MUSICAL, disputado);
        dono.verificarLivres(Esquema.OBRA_MUSICAL, disputado);

        // The winner inserts first, gets a replica row and leaves one pending outbox event.
        dono.inserirERegistrar(Esquema.OBRA_MUSICAL, disputado, INSERT_OBRA, obraComTitulo("vencedor"));
        drenar(Esquema.OBRA_MUSICAL, particaoDe(codigo));
        atualizarObra(codigo, "vencedor-pendente");
        byte[] idVencedor = idReplica(Esquema.OBRA_MUSICAL, codigo);
        long eventosVencedor = eventos(codigo, codigo);
        assertThat(eventosVencedor).as("winner's pending outbox events").isPositive();

        // The loser inserts the same code: rejected by the primary key, nothing registered.
        assertThatThrownBy(() ->
                        rival.inserirERegistrar(Esquema.OBRA_MUSICAL, disputado, INSERT_OBRA, obraComTitulo("rival")))
                .isInstanceOf(AssertionError.class)
                .satisfies(e -> assertThat(violacaoDeChave(e))
                        .as("ORA-00001 in cause chain")
                        .isTrue());
        assertThat(rival.registrados(Esquema.OBRA_MUSICAL)).isEmpty();
        // A later full attempt by the loser is refused by the pre-check, also registering nothing.
        assertThatThrownBy(() ->
                        rival.inserirProprios(Esquema.OBRA_MUSICAL, disputado, INSERT_OBRA, obraComTitulo("rival")))
                .isInstanceOf(AssertionError.class);
        assertThat(rival.registrados(Esquema.OBRA_MUSICAL)).isEmpty();

        rival.limpar();

        assertThat(tituloLegado(codigo)).as("winner's legacy row").isEqualTo("vencedor-pendente");
        assertThat(tituloReplica(codigo)).as("winner's replica row").isEqualTo("vencedor");
        assertThat(idReplica(Esquema.OBRA_MUSICAL, codigo)).isEqualTo(idVencedor);
        assertThat(eventos(codigo, codigo)).as("winner's outbox events").isEqualTo(eventosVencedor);
        assertThat(dono.registrados(Esquema.OBRA_MUSICAL)).containsExactly(codigo);
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
                new OracleOutboxSource(origem),
                new OracleStateProvider(origem),
                new OracleReplicaSink(destino, new Uuid7(CLOCK)));
    }

    private void drenar(TableSpec spec, int particao) {
        ProcessadorParticao processador = processador(spec, particao);
        while (processador.processarLote().eventos() > 0) {
            // drain
        }
    }

    private List<Linha> estadoDoLegado(TableSpec spec, long codigo) {
        Estados estados = new OracleStateProvider(aberta(BancosPoc.legado())).buscar(spec, List.of(codigo));
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
        private final Map<TableSpec, Set<Long>> criados = new LinkedHashMap<>();

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
            long replicaExistentes = contar(novo, spec.tabelaSql(), spec, codigos);
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
                    executar(novo, "DELETE FROM " + spec.tabelaSql() + " WHERE codigo IN (" + em(bloco) + ")");
                }
            }
            novo.commit();
            for (Map.Entry<TableSpec, Set<Long>> entrada : criados.entrySet()) {
                TableSpec spec = entrada.getKey();
                for (List<Long> bloco : blocos(entrada.getValue())) {
                    String em = em(bloco);
                    executar(legado, "DELETE FROM " + spec.tabelaSql() + " WHERE codigo IN (" + em + ")");
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
        try (PreparedStatement ps = novo.prepareStatement("SELECT id FROM " + spec.tabelaSql() + " WHERE codigo = ?")) {
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
