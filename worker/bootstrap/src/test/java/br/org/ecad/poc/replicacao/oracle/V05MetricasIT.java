package br.org.ecad.poc.replicacao.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.ecad.poc.replicacao.bootstrap.config.Config;
import br.org.ecad.poc.replicacao.bootstrap.execucao.Worker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V05MetricasIT {
    @TempDir
    Path dir;

    private final Clock clock = Clock.systemUTC();

    private Config config(String id) {
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
                    case "RESULTADOS_DIR" -> dir.toString();
                    default -> null;
                },
                clock);
    }

    private Path file(String id) {
        return dir.resolve("worker-" + id + ".csv");
    }

    private void update(Connection c) throws Exception {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("update titular set nome=nome where codigo=(select min(codigo) from titular)");
        }
    }

    @Test
    void minuteOfLoad_writesTwelveCompleteWindows() throws Exception {
        try (Worker worker = Worker.iniciar(config("load"), clock);
                Connection c = BancosPoc.legado()) {
            long end = System.nanoTime() + 62_000_000_000L;
            while (System.nanoTime() < end) {
                update(c);
                c.commit();
                Thread.sleep(3000);
            }
        }
        List<String> rows = Files.readAllLines(file("load"));
        assertThat(rows.size()).isGreaterThanOrEqualTo(13);
        for (String row : rows) assertThat(row.split(",", -1)).hasSize(20).doesNotContain("");
        assertThat(rows.stream().skip(1).anyMatch(row -> Double.parseDouble(row.split(",")[2]) > 0))
                .isTrue();
    }

    @Test
    void twoWorkers_writeSeparateFiles() throws Exception {
        try (Worker a = Worker.iniciar(config("a"), clock)) {
            Thread.sleep(6000);
        }
        try (Worker b = Worker.iniciar(config("b"), clock)) {
            Thread.sleep(6000);
        }
        assertThat(Files.readAllLines(file("a")).size()).isGreaterThan(1);
        assertThat(Files.readAllLines(file("b")).size()).isGreaterThan(1);
    }

    @Test
    void noTraffic_forThirtySeconds_producesZeros() throws Exception {
        try (Worker worker = Worker.iniciar(config("idle"), clock)) {
            long limit = System.nanoTime() + 60_000_000_000L;
            while (pending() != 0 && System.nanoTime() < limit) Thread.sleep(500);
            assertThat(pending()).isZero();
            Thread.sleep(31_000);
        }
        List<String> rows = Files.readAllLines(file("idle"));
        long idle = rows.stream()
                .skip(1)
                .filter(row -> {
                    String[] c = row.split(",");
                    return c[2].equals("0.00") && c[9].equals("0") && c[5].equals("0");
                })
                .count();
        assertThat(idle).isGreaterThanOrEqualTo(6);
    }

    @Test
    void openTransaction_excludedFromSla() throws Exception {
        try (Worker worker = Worker.iniciar(config("long"), clock);
                Connection c = BancosPoc.legado()) {
            long limit = System.nanoTime() + 60_000_000_000L;
            while (pending() != 0 && System.nanoTime() < limit) Thread.sleep(500);
            assertThat(pending()).isZero();
            Thread.sleep(6000);
            update(c);
            Thread.sleep(12_000);
            c.commit();
            Thread.sleep(12_000);
        }
        List<String> rows = Files.readAllLines(file("long"));
        assertThat(rows.stream()
                        .skip(1)
                        .map(row -> row.split(","))
                        .anyMatch(c -> Long.parseLong(c[18]) > 0 && c[5].equals("0") && c[8].equals("0")))
                .isTrue();
    }

    @Test
    void csv_containsNoConfiguredCredentials() throws Exception {
        Config config = config("safe");
        try (Worker worker = Worker.iniciar(config, clock)) {
            Thread.sleep(6000);
        }
        String csv = Files.readString(file("safe"));
        assertThat(csv)
                .doesNotContain(config.legadoUsuario(), config.legadoSenha(), config.novoUsuario(), config.novoSenha());
    }

    private long pending() throws Exception {
        try (Connection c = BancosPoc.legado();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("select count(*) from evento_replicacao")) {
            r.next();
            return r.getLong(1);
        }
    }
}
