package br.org.ecad.poc.replicacao.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.ecad.poc.replicacao.core.Esquema;
import br.org.ecad.poc.replicacao.core.Uuid7;
import br.org.ecad.poc.replicacao.reconciliacao.ConfigReconciliacao;
import br.org.ecad.poc.replicacao.reconciliacao.Reconciliacao;
import br.org.ecad.poc.replicacao.worker.ProcessadorParticao;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reconciliation against the real PoC databases ({@code poc-legado} and {@code poc-novo}), shared with
 * other runs. Isolation:
 *
 * <ul>
 *   <li>Each test instance draws its own random block of {@value #BLOCO} codes in a range reserved for
 *       tests; the block is one hash range ({@code codigo / 100000}).
 *   <li>Every reconciliation run is restricted to that block ({@code CODIGO_MIN}/{@code CODIGO_MAX}), so
 *       it never reads, counts or re-enqueues rows of anyone else, even without {@code DRY_RUN}.
 *   <li>A code is registered for cleanup only after this test's own {@code INSERT} committed; the
 *       insert fails on the primary key if another run holds the code, and a replica row or outbox
 *       event already present for the code fails the test before the insert. Cleanup deletes only the
 *       registered codes (replica rows, then legacy rows and their outbox events).
 *   <li>Assertions on {@code evento_replicacao} count only this test's codes: the outbox is shared.
 * </ul>
 */
class V09ReconciliacaoIT {

    private static final long FAIXA_MIN = 9_000_000_000_000L;
    private static final long FAIXA_BLOCOS = 9_000_000L;
    private static final long BLOCO = 100_000L;
    private static final Clock CLOCK = Clock.systemUTC();
    private static final SecureRandom SORTEIO = new SecureRandom();
    private static final String INSERT_OBRA = "INSERT INTO obra_musical (codigo, titulo, iswc, genero, situacao,"
            + " excluido) VALUES (?, ?, 'T', 'OUTRO', 'ATIVO', 0)";

    /** First code of this test's block; the codes used are base + 1 .. base + 3. */
    private final long base = FAIXA_MIN + SORTEIO.nextLong(FAIXA_BLOCOS) * BLOCO;

    private final long faixa = base / BLOCO;
    private final List<Long> criados = new ArrayList<>();
    private final List<Long> eventosDeProva = new ArrayList<>();
    private final List<AutoCloseable> abertos = new ArrayList<>();
    private Connection legado;
    private Connection novo;

    @BeforeEach
    void abrir() {
        legado = aberta(BancosPoc.legado());
        novo = aberta(BancosPoc.novo());
    }

    @AfterEach
    void limparEFechar() throws Exception {
        try {
            for (long id : eventosDeProva) {
                executar(legado, "DELETE FROM evento_replicacao WHERE id = " + id);
            }
            legado.commit();
            if (!criados.isEmpty()) {
                String em =
                        String.join(", ", criados.stream().map(String::valueOf).toList());
                executar(novo, "DELETE FROM obra_musical WHERE codigo IN (" + em + ")");
                novo.commit();
                executar(legado, "DELETE FROM obra_musical WHERE codigo IN (" + em + ")");
                executar(
                        legado,
                        "DELETE FROM evento_replicacao WHERE tabela = 'OBRA_MUSICAL' AND codigo IN (" + em + ")");
                legado.commit();
            }
        } finally {
            for (AutoCloseable aberto : abertos) {
                aberto.close();
            }
        }
    }

    @Test
    void worker_eventoAlheioNaMesmaParticao_naoConsomeNemApaga() throws Exception {
        long proprio = base + 1;
        long alheio = base + 4;
        inserirEReplicar(proprio);
        int particao = (int) escalar(legado, "SELECT ORA_HASH(" + proprio + ", 15) FROM dual");
        long idAlheio = escalar(legado, "SELECT seq_evento.NEXTVAL FROM dual");
        try (PreparedStatement inserir = legado.prepareStatement(
                "INSERT INTO evento_replicacao (id, tabela, codigo, op, particao) VALUES (?, 'OBRA_MUSICAL', ?, 'U', ?)")) {
            inserir.setLong(1, idAlheio);
            inserir.setLong(2, alheio);
            inserir.setInt(3, particao);
            inserir.executeUpdate();
            legado.commit();
        }
        eventosDeProva.add(idAlheio);
        executar(
                legado,
                "INSERT INTO evento_replicacao (tabela, codigo, op, particao) VALUES ('OBRA_MUSICAL', " + proprio
                        + ", 'U', " + particao + ")");
        legado.commit();

        try (Connection origem = BancosPoc.legado();
                OracleOutboxSource source = new OracleOutboxSource(origem, criados)) {
            assertThat(source.poll("OBRA_MUSICAL", particao, 100))
                    .extracting(evento -> evento.eventoId())
                    .doesNotContain(idAlheio);
        }
        drenar(proprio);

        assertThat(escalar(
                        legado,
                        "SELECT COUNT(*) FROM evento_replicacao WHERE tabela = 'OBRA_MUSICAL' AND codigo = " + proprio))
                .isZero();
        assertThat(escalar(legado, "SELECT COUNT(*) FROM evento_replicacao WHERE id = " + idAlheio))
                .isEqualTo(1);
    }

    @Test
    void completoDryRun_identicalBases_reportsZeroAndRecordsTheTime() throws Exception {
        inserirEReplicar(base + 1, base + 2, base + 3);

        String saida = reconciliar("completo", true);

        assertThat(saida).contains("total=0").containsPattern("tempo_ms=\\d+");
        assertThat(saida).doesNotContain("codigo=");
    }

    @Test
    void completoDryRun_directUpdateOnTheReplica_pointsTheRangeAndTheCode() throws Exception {
        inserirEReplicar(base + 1, base + 2, base + 3);
        executar(novo, "UPDATE obra_musical SET titulo = 'corrompido' WHERE codigo = " + (base + 2));
        novo.commit();

        String saida = reconciliar("completo", true);

        assertThat(saida)
                .contains("tabela=OBRA_MUSICAL faixa=" + faixa + " codigo=" + (base + 2))
                .contains("tabela=OBRA_MUSICAL faixa=" + faixa + " divergencias=1")
                .contains("tabela=OBRA_MUSICAL total=1")
                .contains("total=1 ");
        assertThat(eventosPropios()).as("DRY_RUN creates no event").isZero();
    }

    @Test
    void reconciliacao_rowDeletedFromTheReplica_isFoundEnqueuedAndReappliedByTheWorker() throws Exception {
        inserirEReplicar(base + 1, base + 2, base + 3);
        long alvo = base + 2;
        executar(novo, "DELETE FROM obra_musical WHERE codigo = " + alvo);
        novo.commit();

        assertThat(reconciliar("delta", true)).contains("codigo=" + alvo).contains("total=1 ");
        assertThat(reconciliar("completo", true)).contains("codigo=" + alvo).contains("total=1 ");
        assertThat(eventosPropios()).as("DRY_RUN creates no event").isZero();

        String saida = reconciliar("completo", false);

        assertThat(saida).contains("codigo=" + alvo).contains("total=1 ");
        assertThat(eventosPropios()).as("one R event for the missing row").isEqualTo(1);
        assertThat(escalar(
                        legado,
                        "SELECT COUNT(*) FROM evento_replicacao WHERE tabela = 'OBRA_MUSICAL' AND codigo = " + alvo
                                + " AND op = 'R'"))
                .isEqualTo(1);

        drenar(alvo);

        assertThat(titulo(novo, alvo)).isEqualTo("obra-" + alvo);
        assertThat(reconciliar("completo", true)).contains("total=0 ");
        assertThat(reconciliar("delta", true)).contains("total=0 ");
        assertThat(eventosPropios()).as("worker acknowledged the R event").isZero();
    }

    @Test
    void reconciliacao_nullVersionOnTheReplica_isRepairedByTheWorker() throws Exception {
        inserirEReplicar(base + 1, base + 2, base + 3);
        long alvo = base + 2;
        executar(
                novo,
                "UPDATE obra_musical SET titulo = 'corrompido', origem_updated_at = NULL" + " WHERE codigo = " + alvo);
        novo.commit();

        assertThat(reconciliar("delta", true)).contains("codigo=" + alvo).contains("total=1 ");
        assertThat(reconciliar("completo", true)).contains("codigo=" + alvo).contains("total=1 ");

        reconciliar("completo", false);
        assertThat(eventosPropios()).as("one R event for the null-version row").isEqualTo(1);
        drenar(alvo);

        assertThat(titulo(novo, alvo)).isEqualTo("obra-" + alvo);
        assertThat(escalar(
                        novo,
                        "SELECT COUNT(*) FROM obra_musical WHERE codigo = " + alvo
                                + " AND origem_updated_at IS NOT NULL"))
                .isEqualTo(1);
        assertThat(reconciliar("completo", true)).contains("total=0 ");
        assertThat(reconciliar("delta", true)).contains("total=0 ");
        assertThat(eventosPropios()).isZero();
    }

    @Test
    void reconciliacao_replicaNewerThanTheOrigin_isNotOverwrittenByAStaleEvent() throws Exception {
        inserirEReplicar(base + 1, base + 2, base + 3);
        long alvo = base + 2;
        executar(
                novo,
                "UPDATE obra_musical SET titulo = 'mais-novo',"
                        + " origem_updated_at = origem_updated_at + INTERVAL '1' DAY WHERE codigo = " + alvo);
        novo.commit();
        executar(
                legado,
                "INSERT INTO evento_replicacao (tabela, codigo, op, particao) VALUES ('OBRA_MUSICAL', " + alvo
                        + ", 'U', ORA_HASH(" + alvo + ", 15))");
        legado.commit();

        drenar(alvo);

        assertThat(titulo(novo, alvo))
                .as("version guard keeps the newer replica row")
                .isEqualTo("mais-novo");
        assertThat(eventosPropios()).isZero();
    }

    @Test
    void dryRun_divergentBases_countsAndLeavesTheOutboxUntouched() throws Exception {
        inserirEReplicar(base + 1, base + 2, base + 3);
        executar(novo, "DELETE FROM obra_musical WHERE codigo = " + (base + 1));
        executar(novo, "UPDATE obra_musical SET titulo = 'corrompido' WHERE codigo = " + (base + 3));
        novo.commit();
        long antes = eventosPropios();

        String completo = reconciliar("completo", true);
        String delta = reconciliar("delta", true);

        assertThat(completo)
                .contains("tabela=OBRA_MUSICAL faixa=" + faixa + " divergencias=2")
                .contains("codigo=" + (base + 1))
                .contains("codigo=" + (base + 3))
                .contains("total=2 ");
        assertThat(delta).contains("codigo=" + (base + 1)).contains("total=1 ");
        assertThat(eventosPropios()).isEqualTo(antes).isZero();
    }

    // --- helpers ---

    private Connection aberta(Connection conexao) {
        abertos.add(conexao);
        return conexao;
    }

    /** Inserts the rows in the legacy (registering them for cleanup) and lets the worker replicate them. */
    private void inserirEReplicar(long... codigos) throws SQLException {
        List<Long> lista = new ArrayList<>();
        for (long c : codigos) lista.add(c);
        for (long c : lista) {
            assertThat(escalar(novo, "SELECT COUNT(*) FROM obra_musical WHERE codigo = " + c))
                    .as("replica row pre-existing for " + c + ": not created by this test")
                    .isZero();
            assertThat(escalar(
                            legado,
                            "SELECT COUNT(*) FROM evento_replicacao WHERE tabela = 'OBRA_MUSICAL'" + " AND codigo = "
                                    + c))
                    .as("outbox event pre-existing for " + c + ": not created by this test")
                    .isZero();
        }
        try (PreparedStatement ps = legado.prepareStatement(INSERT_OBRA)) {
            for (long c : lista) {
                ps.setLong(1, c);
                ps.setString(2, "obra-" + c);
                ps.addBatch();
            }
            ps.executeBatch();
            legado.commit();
        } catch (SQLException e) {
            legado.rollback();
            throw new AssertionError("insert rejected (codes held by another run?); nothing registered", e);
        }
        criados.addAll(lista);
        for (long c : lista) drenar(c);
        for (long c : lista) assertThat(titulo(novo, c)).isEqualTo("obra-" + c);
    }

    /**
     * Runs the real worker processor over the partition of {@code codigo}, but its outbox source only
     * sees (and acknowledges) events of this test's created codes only.
     */
    private void drenar(long codigo) throws SQLException {
        int particao = (int) escalar(legado, "SELECT ORA_HASH(" + codigo + ", 15) FROM dual");
        Connection origem = aberta(BancosPoc.legado());
        ProcessadorParticao processador = new ProcessadorParticao(
                Esquema.OBRA_MUSICAL,
                particao,
                BancosPoc.config().loteMax(),
                new OracleOutboxSource(origem, criados),
                new OracleStateProvider(origem),
                new OracleReplicaSink(aberta(BancosPoc.novo()), new Uuid7(CLOCK)));
        while (processador.processarLote().eventos() > 0) {
            // drain
        }
    }

    /** Reconciles only this test's block of codes and returns what the subcommand printed. */
    private String reconciliar(String modo, boolean dryRun) throws SQLException {
        ConfigReconciliacao opcoes = new ConfigReconciliacao(modo, dryRun, 60, 5, base, base + BLOCO);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream saida = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            new Reconciliacao(aberta(BancosPoc.legado()), aberta(BancosPoc.novo()), opcoes, saida).executar();
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private long eventosPropios() throws SQLException {
        return escalar(
                legado,
                "SELECT COUNT(*) FROM evento_replicacao WHERE tabela = 'OBRA_MUSICAL'" + " AND codigo >= " + base
                        + " AND codigo < " + (base + BLOCO));
    }

    private static String titulo(Connection conexao, long codigo) throws SQLException {
        try (PreparedStatement ps = conexao.prepareStatement("SELECT titulo FROM obra_musical WHERE codigo = ?")) {
            ps.setLong(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
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
}
