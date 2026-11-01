package br.org.ecad.poc.replicacao.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.ecad.poc.replicacao.Config;
import br.org.ecad.poc.replicacao.Main;
import br.org.ecad.poc.replicacao.carga.CargaInicial;
import br.org.ecad.poc.replicacao.carga.ConfigCarga;
import br.org.ecad.poc.replicacao.core.Esquema;
import br.org.ecad.poc.replicacao.core.TableSpec;
import br.org.ecad.poc.replicacao.reconciliacao.ConfigReconciliacao;
import br.org.ecad.poc.replicacao.reconciliacao.Reconciliacao;
import br.org.ecad.poc.replicacao.worker.Worker;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V-10 against the whole PoC mass (the real {@code poc-legado} and {@code poc-novo}). The replica
 * belongs to this PoC and nothing else reads it: every test starts from an <b>empty replica</b>
 * ({@code TRUNCATE} of the three replica tables) because "réplica vazia" is the precondition of the
 * initial load. The legacy database is never emptied or cleaned: the load only reads it, and the
 * generator (test 3) writes to it the same way the PoC load scenarios do. The test refuses to run when
 * another worker beat its heartbeat in the last minute, so it never competes with a live worker.
 *
 * <p>Timing uses {@link System#nanoTime()} only (the WSL2 wall clock jumps).
 */
class V10CargaInicialIT {
    private static final System.Logger LOG = System.getLogger(V10CargaInicialIT.class.getName());
    private static final Clock CLOCK = Clock.systemUTC();
    private static final String WORKER_ID = "v10-it";
    private static final long LIMITE_PARCIAL = 100_000;
    private static final long PRAZO_NS = TimeUnit.MINUTES.toNanos(15);

    private final Path evidencias = Path.of("target", "v10-evidencias", CLOCK.millis() + "-" + System.nanoTime());
    private Connection origem;
    private Connection destino;

    @BeforeEach
    void preparar() throws Exception {
        Files.createDirectories(evidencias);
        origem = BancosPoc.legado();
        destino = BancosPoc.novo();
        assertThat(escalar(
                        destino,
                        "SELECT COUNT(*) FROM worker_heartbeat WHERE worker_id <> '" + WORKER_ID
                                + "' AND visto_em > SYS_EXTRACT_UTC(SYSTIMESTAMP) - INTERVAL '60' SECOND"))
                .as("another worker is alive: refusing to empty the replica under it")
                .isZero();
        for (TableSpec tabela : Esquema.TABELAS) {
            executar(destino, "TRUNCATE TABLE " + tabela.tabelaSql());
        }
        assertThat(contarReplica()).isZero();
    }

    @AfterEach
    void fechar() throws Exception {
        if (destino != null) destino.close();
        if (origem != null) origem.close();
    }

    /** (1) Empty replica, capture on, THREADS=4: summary, worker drains the outbox, equal counts, 0 divergences. */
    @Test
    void carga_replicaVazia_resumoContagensIguaisEReconciliacaoZero() throws Exception {
        String resumo = carregar(Map.of("THREADS", "4"));
        for (TableSpec tabela : Esquema.TABELAS) {
            assertThat(resumo).contains("tabela=" + tabela.nome() + " linhas=" + contar(origem, tabela) + " linhas_s=");
        }
        assertThat(resumo).contains("tempo_total_s=");

        drenarComWorker();

        for (TableSpec tabela : Esquema.TABELAS) {
            assertThat(contar(destino, tabela)).as(tabela.nome()).isEqualTo(contar(origem, tabela));
        }
        assertThat(reconciliar()).as("reconciliacao completo DRY_RUN=true").isZero();
    }

    /** (2) A real process killed in the middle and re-run: no id of the partial copy changes. */
    @Test
    void carga_mortaNoMeioEReexecutada_naoAlteraNenhumId() throws Exception {
        Path log = evidencias.resolve("carga-morta.log");
        Process processo = iniciarProcessoDeCarga(log);
        long limite = System.nanoTime() + PRAZO_NS;
        while (contarReplica() < LIMITE_PARCIAL) {
            assertThat(processo.isAlive())
                    .as("the load ended before the kill point:\n" + ler(log))
                    .isTrue();
            assertThat(System.nanoTime()).as("kill point not reached").isLessThan(limite);
            Thread.sleep(100);
        }
        processo.destroyForcibly();
        assertThat(processo.waitFor(1, TimeUnit.MINUTES)).isTrue();
        assertThat(processo.exitValue()).as("killed, not finished").isNotZero();

        Path antes = evidencias.resolve("antes");
        long parciais = snapshot(antes);
        long total = 0;
        for (TableSpec tabela : Esquema.TABELAS) total += contar(origem, tabela);
        assertThat(parciais).as("partial copy").isPositive().isLessThan(total);
        LOG.log(System.Logger.Level.INFO, "carga morta: linhas_parciais=" + parciais + " de " + total);

        String resumo = carregar(Map.of("THREADS", "4"));
        assertThat(resumo).contains("tempo_total_s=");
        Path depois = evidencias.resolve("depois");
        assertThat(snapshot(depois)).as("complete copy").isEqualTo(total);

        assertThat(compararSnapshots(antes, depois))
                .as("ids changed by the re-run")
                .isEqualTo(List.of());
    }

    /** (3) Generator at 500 events/s during the load, worker running: the replica ends equal to the origin. */
    @Test
    void carga_comGeradorACincoCentosPorSegundo_replicaRefleteOEstadoMaisRecente() throws Exception {
        try (Worker worker = Worker.iniciar(configWorker(), CLOCK)) {
            long sequenciaAntes = escalar(origem, "SELECT seq_evento.NEXTVAL FROM dual");
            CompletableFuture<GeradorRemoto.Resultado> gerador = CompletableFuture.supplyAsync(() -> {
                try {
                    return GeradorRemoto.executar(
                            Map.of("TAXA", "500", "DURACAO_SEG", "240", "THREADS", "8"), evidencias.resolve("gerador"));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            esperarGerador(sequenciaAntes, gerador);

            String resumo = carregar(Map.of("THREADS", "4"));
            long durante = escalar(origem, "SELECT seq_evento.NEXTVAL FROM dual") - sequenciaAntes - 1;
            LOG.log(System.Logger.Level.INFO, "eventos capturados durante a carga: " + durante);
            assertThat(durante).as("the generator wrote while the load ran").isGreaterThan(1_000);
            assertThat(resumo).contains("tempo_total_s=");

            GeradorRemoto.Resultado resultado = gerador.get(20, TimeUnit.MINUTES);
            assertThat(resultado.exitCode()).as(resultado.saida()).isZero();
            assertThat(resultado.linhas()).isPositive();
            aguardarPendentesZero();
            assertThat(worker.anomalias()).as("anomalies in the worker").isNotNegative();
        }

        for (TableSpec tabela : Esquema.TABELAS) {
            assertThat(contar(destino, tabela)).as(tabela.nome()).isEqualTo(contar(origem, tabela));
        }
        assertThat(reconciliar()).as("reconciliacao completo DRY_RUN=true").isZero();
    }

    /** (4) TABELAS=titular copies only titular. */
    @Test
    void carga_tabelaSelecionada_copiaSomenteTitular() throws Exception {
        String resumo = carregar(Map.of("THREADS", "4", "TABELAS", "titular"));
        assertThat(resumo).contains("tabela=TITULAR linhas=").doesNotContain("tabela=OBRA_MUSICAL");
        assertThat(resumo).doesNotContain("tabela=FONOGRAMA");
        assertThat(contar(destino, Esquema.TITULAR)).isEqualTo(contar(origem, Esquema.TITULAR));
        assertThat(contar(destino, Esquema.TITULAR)).isPositive();
        assertThat(contar(destino, Esquema.OBRA_MUSICAL)).isZero();
        assertThat(contar(destino, Esquema.FONOGRAMA)).isZero();
    }

    // --- load, worker, reconciliation ---

    private String carregar(Map<String, String> ambiente) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (PrintStream saida = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            new CargaInicial(BancosPoc.config(), ConfigCarga.load(ambiente::get), CLOCK, saida).executar();
        }
        String resumo = buffer.toString(StandardCharsets.UTF_8);
        LOG.log(System.Logger.Level.INFO, "resumo da carga:\n" + resumo);
        Files.writeString(evidencias.resolve("resumo-" + System.nanoTime() + ".txt"), resumo);
        return resumo;
    }

    private void drenarComWorker() throws Exception {
        try (Worker worker = Worker.iniciar(configWorker(), CLOCK)) {
            aguardarPendentesZero();
            assertThat(worker.anomalias()).isNotNegative();
        }
    }

    /** Waits (monotonic clock) until the outbox stays empty for three consecutive polls. */
    private void aguardarPendentesZero() throws Exception {
        long limite = System.nanoTime() + PRAZO_NS;
        int vazias = 0;
        while (vazias < 3) {
            assertThat(System.nanoTime()).as("outbox not drained in time").isLessThan(limite);
            if (escalar(origem, "SELECT COUNT(*) FROM evento_replicacao") == 0) vazias++;
            else vazias = 0;
            Thread.sleep(1_000);
        }
    }

    private void esperarGerador(long sequenciaAntes, CompletableFuture<GeradorRemoto.Resultado> gerador)
            throws Exception {
        long limite = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        while (escalar(origem, "SELECT seq_evento.NEXTVAL FROM dual") - sequenciaAntes < 50) {
            assertThat(gerador.isDone()).as("generator ended before writing").isFalse();
            assertThat(System.nanoTime()).as("generator did not start writing").isLessThan(limite);
            Thread.sleep(500);
        }
    }

    private long reconciliar() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        long total;
        try (PrintStream saida = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            total = new Reconciliacao(origem, destino, new ConfigReconciliacao("completo", true, 60, 5), saida)
                    .executar();
        }
        LOG.log(System.Logger.Level.INFO, "reconciliacao completo DRY_RUN=true:\n" + buffer);
        return total;
    }

    private Config configWorker() {
        Config base = BancosPoc.config();
        Map<String, String> valores = Map.of(
                "LEGADO_URL", base.legadoUrl(),
                "LEGADO_USUARIO", base.legadoUsuario(),
                "LEGADO_SENHA", base.legadoSenha(),
                "NOVO_URL", base.novoUrl(),
                "NOVO_USUARIO", base.novoUsuario(),
                "NOVO_SENHA", base.novoSenha(),
                "WORKER_ID", WORKER_ID,
                "RESULTADOS_DIR", evidencias.resolve("worker").toString());
        return Config.load(valores::get, CLOCK);
    }

    /** The real {@code carga-inicial} subcommand in its own JVM, so that it can be killed for real. */
    private Process iniciarProcessoDeCarga(Path log) throws Exception {
        Config base = BancosPoc.config();
        Path classes = Path.of(
                Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path driver = Path.of(oracle.jdbc.OracleDriver.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        ProcessBuilder pb = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Xmx512m",
                        "-cp",
                        classes + System.getProperty("path.separator") + driver,
                        Main.class.getName(),
                        "carga-inicial")
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        pb.environment().put("LEGADO_URL", base.legadoUrl());
        pb.environment().put("LEGADO_USUARIO", base.legadoUsuario());
        pb.environment().put("LEGADO_SENHA", base.legadoSenha());
        pb.environment().put("NOVO_URL", base.novoUrl());
        pb.environment().put("NOVO_USUARIO", base.novoUsuario());
        pb.environment().put("NOVO_SENHA", base.novoSenha());
        pb.environment().put("THREADS", "4");
        return pb.start();
    }

    // --- id snapshots (same CSV as scripts/snapshot-ids.sh: "codigo,ID" per line) ---

    /** Writes one CSV per table into {@code dir} and returns the total number of rows. */
    private long snapshot(Path dir) throws Exception {
        Files.createDirectories(dir);
        long total = 0;
        for (TableSpec tabela : Esquema.TABELAS) {
            try (Statement s = destino.createStatement();
                    BufferedWriter w = Files.newBufferedWriter(dir.resolve(tabela.tabelaSql() + ".csv"))) {
                s.setFetchSize(20_000);
                try (ResultSet rs =
                        s.executeQuery("SELECT codigo, RAWTOHEX(id) FROM " + tabela.tabelaSql() + " ORDER BY codigo")) {
                    while (rs.next()) {
                        w.write(rs.getLong(1) + "," + rs.getString(2) + "\n");
                        total++;
                    }
                }
            }
        }
        destino.commit();
        return total;
    }

    /** Runs {@code scripts/comparar-snapshots.sh} (V-08); returns the difference lines (header excluded). */
    private List<String> compararSnapshots(Path antes, Path depois) throws Exception {
        Path diferencas = evidencias.resolve("diferencas.csv");
        Process p = new ProcessBuilder("bash", "scripts/comparar-snapshots.sh", antes.toString(), depois.toString())
                .redirectOutput(diferencas.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        assertThat(p.waitFor(10, TimeUnit.MINUTES)).isTrue();
        assertThat(p.exitValue())
                .as("comparar-snapshots.sh exit (2 = usage/infra)")
                .isLessThan(2);
        List<String> linhas = new ArrayList<>(Files.readAllLines(diferencas));
        assertThat(linhas).isNotEmpty();
        linhas.remove(0);
        return linhas;
    }

    // --- sql helpers ---

    private long contarReplica() throws Exception {
        return escalar(
                destino,
                "SELECT (SELECT COUNT(*) FROM titular) + (SELECT COUNT(*) FROM obra_musical)"
                        + " + (SELECT COUNT(*) FROM fonograma) FROM dual");
    }

    private static long contar(Connection c, TableSpec t) throws Exception {
        return escalar(c, "SELECT COUNT(*) FROM " + t.tabelaSql());
    }

    private static long escalar(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            long valor = rs.getLong(1);
            c.commit();
            return valor;
        }
    }

    private static void executar(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
        c.commit();
    }

    private static String ler(Path arquivo) throws Exception {
        return Files.exists(arquivo) ? Files.readString(arquivo) : "";
    }
}
