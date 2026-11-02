package br.org.ecad.poc.replicacao.exportador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.org.ecad.poc.replicacao.ConfigException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V14ExportadorTest {
    @TempDir
    Path directory;

    static final String EXECUTION = "v14-controlled";
    static final String INDEX = ConfigExportador.index(EXECUTION);
    static final String WORKER_ROW =
            "2026-11-01T00:00:00Z,48,1000.00,900.00,1.11,10,20,30,40,2,50,0,1,2,3,4,0,1,0,VALIDA";
    static final String RESOURCE_ROW = "2026-11-01T00:00:00Z,poc-legado,12.50%,1GiB / 3GiB,2";

    @Test
    void http_whenExporting_sendsAuthenticationTypedCsvAndIdempotentManifest() throws Exception {
        List<Call> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        ControlledHttpServer server = server(calls, false);
        try (ElasticKibana client = new ElasticKibana(config(server), ConfigExportador.SPACE)) {
            client.preflight();
            client.ensureIndex(INDEX);
            CsvSample parser = new CsvSample();
            var context = new CsvSample.ExportContext("C2", EXECUTION, "local:worker.csv");
            assertThat(parser.parse(CsvSample.WORKER, context)).isEmpty();
            Map<String, Object> worker = parser.parse(WORKER_ROW, context);
            client.write(INDEX, CsvSample.id(context, WORKER_ROW), worker);
            assertThat(worker)
                    .containsEntry("p99_ms", 30.0)
                    .containsEntry("eventos_s", 1000.0)
                    .containsEntry("valida", "VALIDA")
                    .containsEntry("execucao", EXECUTION)
                    .containsEntry("cenario", "C2");
            parser.parse(CsvSample.RESOURCES, context);
            Map<String, Object> resource = parser.parse(RESOURCE_ROW, context);
            assertThat(resource).containsEntry("cpu_percent", 12.5).containsEntry("sessoes_ativas", 2.0);
            client.write(INDEX, CsvSample.id(context, RESOURCE_ROW), resource);
            client.panel();
            client.panel();
            client.cleanup(INDEX);
            String expected = "Basic "
                    + Base64.getEncoder()
                            .encodeToString("fixture-user:fixture-password".getBytes(StandardCharsets.UTF_8));
            assertThat(calls.stream().allMatch(call -> expected.equals(call.auth())))
                    .isTrue();
            List<Call> imports =
                    calls.stream().filter(c -> c.path().contains("_import")).toList();
            assertThat(imports).hasSize(2);
            assertThat(imports.getFirst().body())
                    .isEqualTo(imports.getLast().body())
                    .contains("poc-replicacao-bdad-painel", "p99_ms", "cpu_percent", "sessoes_ativas");
            assertThat(imports.getFirst().contentType()).startsWith("multipart/form-data; boundary=");
            assertThat(imports.getFirst().path())
                    .isEqualTo("/s/poc-replicacao-bdad/api/saved_objects/_import?overwrite=true");
            assertThat(calls.stream()
                            .allMatch(c -> c.body().isEmpty() || !c.body().contains("fixture-password")))
                    .isTrue();
            assertThat(calls.stream().filter(c -> c.method().equals("DELETE")).count())
                    .isEqualTo(9);
            // Cleanup uses the destination UUIDs resolved through originId, never the fixed NDJSON ID.
            assertThat(calls.stream()
                            .filter(c -> c.method().equals("DELETE") && c.path().contains("/api/saved_objects/"))
                            .noneMatch(c -> c.path().contains(ConfigExportador.PREFIX)))
                    .isTrue();
        } finally {
            server.close();
        }
    }

    @Test
    void namespace_whenTargetIsForeign_refusesBeforeAnyHttpIncludingEncodedPathsAliasesAndObjects() throws Exception {
        List<Call> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        ControlledHttpServer server = server(calls, false);
        try (ElasticKibana client = new ElasticKibana(config(server), ConfigExportador.SPACE)) {
            for (String target : List.of(
                    "foreign",
                    "*",
                    "_all",
                    "poc-replicacao-bdad-*",
                    INDEX + "/../foreign",
                    INDEX + "%2fforeign",
                    INDEX + ",foreign")) {
                assertThatThrownBy(() -> client.write(target, "a".repeat(64), Map.of()))
                        .isInstanceOf(ConfigException.class);
                assertThatThrownBy(() -> client.cleanup(target)).isInstanceOf(ConfigException.class);
                assertThatThrownBy(() -> client.ensureIndex(target)).isInstanceOf(ConfigException.class);
            }
            assertThatThrownBy(() -> ConfigExportador.guardSpace("default")).isInstanceOf(ConfigException.class);
            assertThatThrownBy(() -> ElasticKibana.guardObject("dashboard", "foreign"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> ElasticKibana.guardObject("config", INDEX))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(calls).isEmpty();
            assertThatThrownBy(() -> Exportador.Source.parse("infra-server:poc-worker:/resultados/a.csv;id"))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> Exportador.Source.parse("infra-server:foreign:/resultados/a.csv"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(Exportador.Source.parse("infra-server:poc-worker:/resultados/worker.csv")
                            .command())
                    .contains("SendEnv=-*");
            // The resolver response identifies an alias even though its name has the PoC prefix.
            assertThatThrownBy(() -> client.ensureIndex(ConfigExportador.PREFIX + "alias"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(calls.stream()
                            .noneMatch(c -> c.method().equals("PUT") && c.path().contains("_doc")))
                    .isTrue();
        } finally {
            server.close();
        }
    }

    @Test
    void unavailable_whenHttpFails_exporterExitsRedactedWhileIndependentScenarioKeepsCsvAndVerdict() throws Exception {
        List<Call> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        ControlledHttpServer server = server(calls, true);
        Path csv = directory.resolve("worker.csv");
        Files.writeString(csv, CsvSample.WORKER + "\n" + WORKER_ROW + "\n", StandardCharsets.UTF_8);
        String before = Files.readString(csv);
        Files.writeString(
                directory.resolve(".env"),
                "ELASTIC_HOST=" + endpoint(server) + "\nKIBANA_HOST=" + endpoint(server)
                        + "\nKIBANA_ELASTIC_USER=fixture-user\nKIBANA_ELASTIC_PASSWORD=fixture-password\n");
        Path verdict = directory.resolve("verdict");
        Process scenario = new ProcessBuilder("bash", "-c", "sleep 3; printf 'APROVADO' > verdict")
                .directory(directory.toFile())
                .start();
        Process exporter = child(directory, csv, directory.resolve("exportador.log"));
        try {
            assertThat(exporter.waitFor(20, TimeUnit.SECONDS)).isTrue();
            assertThat(exporter.exitValue()).isEqualTo(1);
            assertThat(scenario.waitFor(10, TimeUnit.SECONDS)).isTrue();
            assertThat(scenario.exitValue()).isZero();
            assertThat(Files.readString(csv)).isEqualTo(before);
            assertThat(Files.readString(verdict)).isEqualTo("APROVADO");
            String log = Files.readString(directory.resolve("exportador.log"));
            assertThat(log.contains("fixture-password") || log.contains("fixture-user"))
                    .isFalse();
            assertThat(log).contains("CSV evidence are unaffected");
            assertThat(calls.stream().anyMatch(c -> c.path().contains("_doc"))).isTrue();
        } finally {
            exporter.destroyForcibly();
            exporter.waitFor();
            scenario.destroyForcibly();
            scenario.waitFor();
            server.close();
        }
    }

    static Process child(Path workingDirectory, Path csv, Path log) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                "-cp",
                Path.of("target/classes").toAbsolutePath().toString(),
                "br.org.ecad.poc.replicacao.Main",
                "exportador");
        builder.directory(workingDirectory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment()
                .putAll(Map.of(
                        "EXPORTADOR_CENARIO",
                        "C2",
                        "EXPORTADOR_EXECUCAO",
                        EXECUTION,
                        "EXPORTADOR_FONTES",
                        "local:" + csv.toAbsolutePath(),
                        "EXPORTADOR_ACAO",
                        "seguir",
                        "LEGADO_URL",
                        ""));
        builder.environment().keySet().removeIf(key -> key.startsWith("ELASTIC_") || key.startsWith("KIBANA_"));
        return builder.start();
    }

    private static ConfigExportador config(ControlledHttpServer server) {
        return ConfigExportador.load(Map.of(
                "ELASTIC_HOST",
                endpoint(server),
                "KIBANA_HOST",
                endpoint(server),
                "KIBANA_ELASTIC_USER",
                "fixture-user",
                "KIBANA_ELASTIC_PASSWORD",
                "fixture-password")::get);
    }

    private static String endpoint(ControlledHttpServer server) {
        return "http://127.0.0.1:" + server.port();
    }

    /**
     * Mimics Kibana 9: the import keeps the NDJSON ID only as originId and answers with UUID destination IDs;
     * _find lists what is currently stored and DELETE removes by the UUID.
     */
    private static ControlledHttpServer server(List<Call> calls, boolean unavailable) throws Exception {
        Map<String, Map<String, Object>> stored = new java.util.concurrent.ConcurrentHashMap<>();
        return new ControlledHttpServer(exchange -> {
            String path = exchange.path();
            String method = exchange.method();
            String body = exchange.body();
            calls.add(new Call(
                    method,
                    path,
                    exchange.headers().get("authorization"),
                    body,
                    exchange.headers().get("content-type")));
            int code = method.equals("HEAD") ? 404 : 200;
            String response = "{}";
            if (path.startsWith("/_resolve/index/"))
                response = Json.write(Map.of(
                        "indices",
                        List.of(Map.of("name", INDEX)),
                        "aliases",
                        path.endsWith("alias") ? List.of(Map.of("name", "alias")) : List.of()));
            if (path.contains("_import")) {
                List<Object> results = new ArrayList<>();
                for (String line :
                        exchange.body().lines().filter(l -> l.startsWith("{")).toList()) {
                    Map<?, ?> object = (Map<?, ?>) Json.read(line);
                    String origin = (String) object.get("id");
                    String uuid = java.util
                            .UUID
                            .nameUUIDFromBytes(origin.getBytes(StandardCharsets.UTF_8))
                            .toString();
                    stored.put(uuid, Map.of("type", object.get("type"), "id", uuid, "originId", origin));
                    results.add(Map.of("type", object.get("type"), "id", origin, "destinationId", uuid));
                }
                response = Json.write(Map.of("success", true, "successResults", results));
            }
            if (path.contains("/_find")) response = Json.write(Map.of("saved_objects", List.copyOf(stored.values())));
            if (method.equals("DELETE") && path.contains("/api/saved_objects/"))
                stored.remove(path.substring(path.lastIndexOf('/') + 1));
            if (path.contains("_doc")) {
                code = unavailable ? 503 : 201;
                response = unavailable ? "fixture-user fixture-password" : "{}";
            }
            return new ControlledHttpServer.Response(code, response);
        });
    }

    private record Call(String method, String path, String auth, String body, String contentType) {}
}
