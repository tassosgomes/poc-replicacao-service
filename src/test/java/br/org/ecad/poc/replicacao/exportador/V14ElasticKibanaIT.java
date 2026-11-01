package br.org.ecad.poc.replicacao.exportador;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Real laptop credentials are loaded in memory only; assertion diagnostics contain no secrets. */
class V14ElasticKibanaIT {
    private static final String EXECUTION = "v14-it-" + Long.toUnsignedString(System.nanoTime());
    private static final String SPACE = ConfigExportador.SPACE + "-teste";

    @Test
    void dedicatedAccount_whenWriting_createsRealIndexAndCompleteDashboard() {
        String index = ConfigExportador.index(EXECUTION + "-write");
        try (ElasticKibana client = client()) {
            try {
                client.preflight();
                client.ensureIndex(index);
                client.panel();
                writeWorker(client, index, EXECUTION);
                client.refresh(index);
                List<?> hits = hits(client.search(index, EXECUTION));
                assertThat(hits).hasSize(1);
                Map<?, ?> source = (Map<?, ?>) ((Map<?, ?>) hits.getFirst()).get("_source");
                assertThat(source.get("p99_ms")).isEqualTo(new java.math.BigDecimal("30.0"));
                assertThat(panelCount(client)).isEqualTo(1);
                assertThat(client.savedObjects()).hasSize(8);
            } finally {
                client.cleanup(index);
                assertThat(client.savedObjects()).isEmpty();
            }
        }
    }

    @Test
    void panel_whenImportedTwice_overwritesSameSavedObjects() {
        String index = ConfigExportador.index(EXECUTION + "-panel");
        try (ElasticKibana client = client()) {
            try {
                client.panel();
                List<String> first = savedIds(client);
                client.panel();
                assertThat(first).hasSize(8);
                assertThat(savedIds(client)).containsExactlyInAnyOrderElementsOf(first);
                assertThat(panelCount(client)).isEqualTo(1);
            } finally {
                client.cleanup(index);
            }
        }
    }

    @Test
    void executionQueryAndCleanup_whenOtherExecutionExists_preserveItsIndexAndDocuments() {
        String execution = EXECUTION + "-cleanup";
        String index = ConfigExportador.index(execution);
        String other = ConfigExportador.index(EXECUTION + "-preserved");
        try (ElasticKibana client = client()) {
            try {
                client.ensureIndex(index);
                client.ensureIndex(other);
                client.panel();
                writeWorker(client, index, execution);
                writeWorker(client, index, "other-execution");
                writeWorker(client, other, "preserved");
                client.refresh(index);
                client.refresh(other);
                List<?> preserved = hits(client.search(other, "preserved"));
                assertThat(hits(client.search(index, execution))).hasSize(1);
                client.cleanup(index);
                client.cleanup(index);
                assertThat(client.indexExists(index)).isFalse();
                assertThat(panelCount(client)).isZero();
                assertThat(client.savedObjects()).isEmpty();
                assertThat(hits(client.search(other, "preserved"))).isEqualTo(preserved);
            } finally {
                client.cleanup(index);
                client.cleanup(other);
            }
        }
    }

    @Test
    void liveExporter_whenFollowingRealServices_neverLeaksSecretsAndPublishesWithinThirtySeconds() throws Exception {
        try (RemoteCsv remote = new RemoteCsv()) {
            Path evidence = Path.of(".tsg-flow", EXECUTION);
            Files.createDirectories(evidence);
            Path csv = evidence.resolve("worker.csv");
            Path resources = evidence.resolve("recursos.csv");
            Path log = evidence.resolve("exportador.log");
            Files.writeString(csv, CsvSample.WORKER + "\n");
            Files.writeString(resources, CsvSample.RESOURCES + "\n");
            ProcessBuilder builder = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin/java").toString(),
                    "-cp",
                    Path.of("target/classes").toAbsolutePath().toString(),
                    "br.org.ecad.poc.replicacao.Main",
                    "exportador");
            builder.redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().keySet().removeIf(k -> k.startsWith("ELASTIC_") || k.startsWith("KIBANA_"));
            builder.environment()
                    .putAll(Map.of(
                            "EXPORTADOR_ACAO",
                            "seguir",
                            "EXPORTADOR_EXECUCAO",
                            EXECUTION,
                            "EXPORTADOR_CENARIO",
                            "C2",
                            "EXPORTADOR_FONTES",
                            remote.source() + "\nlocal:" + resources.toAbsolutePath()));
            Process exporter = builder.start();
            String index = ConfigExportador.index(EXECUTION);
            try (ElasticKibana client =
                    new ElasticKibana(ConfigExportador.laptop(Path.of(".env")), ConfigExportador.SPACE)) {
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                    while (!client.indexExists(index) && System.nanoTime() < deadline && exporter.isAlive())
                        Thread.sleep(100);
                    String now = Instant.now(java.time.Clock.systemUTC()).toString();
                    String row = V14ExportadorTest.WORKER_ROW.replace("2026-11-01T00:00:00Z", now);
                    Files.writeString(csv, row + "\n", StandardOpenOption.APPEND);
                    remote.append(row);
                    Files.writeString(
                            resources,
                            V14ExportadorTest.RESOURCE_ROW.replace("2026-11-01T00:00:00Z", now) + "\n"
                                    + V14ExportadorTest.RESOURCE_ROW
                                            .replace("2026-11-01T00:00:00Z", now)
                                            .replace("poc-legado", "poc-novo")
                                    + "\n",
                            StandardOpenOption.APPEND);
                    List<?> received = List.of();
                    while (System.nanoTime() < deadline && exporter.isAlive()) {
                        // HEAD may see index metadata before its new primary shard is searchable.
                        try {
                            received = hits(client.search(index, EXECUTION));
                        } catch (IllegalStateException pending) {
                            if (!pending.getMessage().contains("status 503")
                                    && !pending.getMessage().contains("status 404")) throw pending;
                        }
                        if (received.size() == 3) break;
                        Thread.sleep(100);
                    }
                    assertThat(exporter.isAlive())
                            .as("Exporter remains alive (diagnostics redacted)")
                            .isTrue();
                    assertThat(received).hasSize(3);
                    assertThat(System.nanoTime() < deadline).isTrue();
                    assertThat(panelCount(client)).isEqualTo(1);
                    // Re-read bytes only inside this audit; no secret is an assertion argument or shell argv.
                    assertThat(noLeaks(List.of(log, csv, resources)))
                            .as("No secret in evidence, exporter log, ps or diff")
                            .isTrue();
                } finally {
                    exporter.destroy();
                    if (!exporter.waitFor(15, TimeUnit.SECONDS)) {
                        exporter.destroyForcibly();
                        exporter.waitFor();
                    }
                    client.cleanup(index);
                }
            } finally {
                if (exporter.isAlive()) {
                    exporter.destroyForcibly();
                    exporter.waitFor();
                }
            }
        }
    }

    /** Only non-secret test CSV goes to the homelab; the exporter remains on the laptop. */
    private static final class RemoteCsv implements AutoCloseable {
        private final String directory = "/tmp/poc-" + EXECUTION;
        private final String file = directory + "/worker.csv";

        RemoteCsv() throws Exception {
            ssh("mkdir -p " + directory + " && cat > " + file, CsvSample.WORKER + "\n");
        }

        String source() {
            return "infra-server:" + file;
        }

        void append(String line) throws Exception {
            ssh("cat >> " + file, line + "\n");
        }

        @Override
        public void close() throws Exception {
            ssh("rm -f " + file + " && rmdir " + directory, "");
        }

        private static void ssh(String command, String content) throws Exception {
            ProcessBuilder builder = new ProcessBuilder(
                            "ssh",
                            "-o",
                            "BatchMode=yes",
                            "-o",
                            "ConnectTimeout=10",
                            "-o",
                            "SendEnv=-*",
                            "infra-server",
                            command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().keySet().removeIf(k -> k.startsWith("ELASTIC_") || k.startsWith("KIBANA_"));
            Process process = builder.start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(content.getBytes(StandardCharsets.UTF_8));
                }
                assertThat(process.waitFor(30, TimeUnit.SECONDS))
                        .as("Non-secret CSV SSH fixture completed")
                        .isTrue();
                assertThat(process.exitValue()).isZero();
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            }
        }
    }

    private static boolean noLeaks(List<Path> currentEvidence) throws Exception {
        String user = "";
        String password = "";
        for (String line : Files.readAllLines(Path.of(".env"))) {
            String[] parts = line.split("=", 2);
            if (parts.length != 2) continue;
            String value = parts[1].trim();
            if (value.length() >= 2
                    && ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'"))))
                value = value.substring(1, value.length() - 1);
            if (parts[0].trim().equals("KIBANA_ELASTIC_USER")) user = value;
            if (parts[0].trim().equals("KIBANA_ELASTIC_PASSWORD")) password = value;
        }
        if (user.isBlank() || password.isBlank()) return false;
        List<String> secrets = List.of(
                user,
                password,
                Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        List<Path> files = new ArrayList<>(currentEvidence);
        for (Path root : List.of(Path.of("resultados"), Path.of(".tsg-flow", "delegate-logs"))) {
            if (!Files.isDirectory(root)) continue;
            try (var walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(files::add);
            }
        }
        for (Path file : files) {
            try (InputStream input = Files.newInputStream(file)) {
                if (containsSecret(input, secrets)) return false;
            }
        }
        for (ProcessHandle process : ProcessHandle.allProcesses().toList()) {
            if (secretIn(process.info().commandLine().orElse(""), secrets)) return false;
        }
        Process diff = new ProcessBuilder("git", "diff", "--no-ext-diff")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        boolean leak;
        try (InputStream output = diff.getInputStream()) {
            leak = containsSecret(output, secrets);
        }
        if (!diff.waitFor(10, TimeUnit.SECONDS)) {
            diff.destroyForcibly();
            diff.waitFor();
            return false;
        }
        return diff.exitValue() == 0 && !leak;
    }

    private static boolean containsSecret(InputStream input, List<String> secrets) throws Exception {
        int overlap = secrets.stream().mapToInt(String::length).max().orElseThrow();
        String tail = "";
        byte[] chunk = new byte[8192];
        int count;
        while ((count = input.read(chunk)) != -1) {
            String text = tail + new String(chunk, 0, count, StandardCharsets.UTF_8);
            if (secretIn(text, secrets)) return true;
            tail = text.substring(Math.max(0, text.length() - overlap));
        }
        return false;
    }

    private static boolean secretIn(String text, List<String> secrets) {
        return secrets.stream().anyMatch(text::contains);
    }

    private static ElasticKibana client() {
        return new ElasticKibana(ConfigExportador.laptop(Path.of(".env")), SPACE);
    }

    private static List<?> hits(Map<?, ?> response) {
        return (List<?>) ((Map<?, ?>) response.get("hits")).get("hits");
    }

    private static long panelCount(ElasticKibana client) {
        return client.savedObjects().stream()
                .filter(o -> "dashboard".equals(o.get("type")))
                .count();
    }

    private static List<String> savedIds(ElasticKibana client) {
        return client.savedObjects().stream().map(o -> (String) o.get("id")).toList();
    }

    private static void writeWorker(ElasticKibana client, String index, String execution) {
        CsvSample parser = new CsvSample();
        var context = new CsvSample.ExportContext("C2", execution, "local:worker.csv");
        parser.parse(CsvSample.WORKER, context);
        client.write(
                index,
                CsvSample.id(context, V14ExportadorTest.WORKER_ROW),
                parser.parse(V14ExportadorTest.WORKER_ROW, context));
    }
}
