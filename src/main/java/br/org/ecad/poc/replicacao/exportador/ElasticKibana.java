package br.org.ecad.poc.replicacao.exportador;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** All mutations pass namespace guards; failures never propagate HTTP bodies, endpoints or causes. */
final class ElasticKibana implements AutoCloseable {
    private static final List<String> TYPE_ORDER = List.of("dashboard", "visualization", "index-pattern");
    private final ConfigExportador config;
    private final String space;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    ElasticKibana(ConfigExportador config, String space) {
        ConfigExportador.guardSpace(space);
        this.config = config;
        this.space = space;
    }

    void preflight() {
        Object privileges = json(
                request(config.elastic(), "GET", "/_security/user/_privileges", "", "application/json", List.of(200)));
        boolean excessive = false;
        if (privileges instanceof Map<?, ?> map && map.get("indices") instanceof List<?> indices) {
            for (Object item : indices) {
                if (item instanceof Map<?, ?> grant
                        && grant.get("names") instanceof List<?> names
                        && names.contains("*")
                        && grant.get("privileges") instanceof List<?> rights
                        && rights.stream()
                                .anyMatch(p -> List.of("all", "write", "index", "create", "create_doc")
                                        .contains(p))) excessive = true;
            }
        }
        if (excessive)
            System.getLogger(ElasticKibana.class.getName())
                    .log(
                            System.Logger.Level.WARNING,
                            "Security pending: unrestricted_write accepted by human decision (2026-11-01); PoC namespace enforced by code");
    }

    void ensureIndex(String index) {
        ConfigExportador.guardIndex(index);
        HttpResponse<String> exists =
                request(config.elastic(), "HEAD", "/" + index, "", "application/json", List.of(200, 404));
        if (exists.statusCode() == 404) {
            Map<String, Object> properties = new java.util.LinkedHashMap<>();
            for (String field : CsvSample.WORKER.split(",")) properties.put(field, Map.of("type", "double"));
            properties.put("ts", Map.of("type", "date"));
            properties.put("valida", Map.of("type", "keyword"));
            for (String field : List.of("cenario", "execucao", "fonte", "tipo", "container", "memoria"))
                properties.put(field, Map.of("type", "keyword"));
            for (String field : List.of("cpu_percent", "sessoes_ativas"))
                properties.put(field, Map.of("type", "double"));
            request(
                    config.elastic(),
                    "PUT",
                    "/" + index,
                    Json.write(Map.of("mappings", Map.of("dynamic", "strict", "properties", properties))),
                    "application/json",
                    List.of(200));
        }
        // A PoC-looking alias could redirect a write to someone else's index. Refuse aliases.
        Object resolved = json(
                request(config.elastic(), "GET", "/_resolve/index/" + index, "", "application/json", List.of(200)));
        if (!(resolved instanceof Map<?, ?> map)
                || !(map.get("indices") instanceof List<?> indices)
                || indices.size() != 1
                || !(indices.getFirst() instanceof Map<?, ?> entry)
                || !index.equals(entry.get("name"))
                || !(map.get("aliases") instanceof List<?> aliases)
                || !aliases.isEmpty()) throw failure();
    }

    void write(String index, String id, Map<String, Object> document) {
        ConfigExportador.guardIndex(index);
        if (!id.matches("[a-f0-9]{64}")) throw failure();
        request(
                config.elastic(),
                "PUT",
                "/" + index + "/_doc/" + id,
                Json.write(document),
                "application/json",
                List.of(200, 201));
    }

    void panel() {
        String definition = definition();
        request(
                config.kibana(),
                "POST",
                "/api/spaces/space",
                Json.write(Map.of("id", space, "name", "PoC replicacao BDAD", "disabledFeatures", List.of())),
                "application/json",
                List.of(200, 409));
        String boundary = "poc-replicacao-bdad-boundary";
        String multipart = "--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"poc.ndjson\"\r\nContent-Type: application/ndjson\r\n\r\n"
                + definition + "\r\n--" + boundary + "--\r\n";
        Object result = json(request(
                config.kibana(),
                "POST",
                spacePath() + "/api/saved_objects/_import?overwrite=true",
                multipart,
                "multipart/form-data; boundary=" + boundary,
                List.of(200)));
        if (!(result instanceof Map<?, ?> map) || !Boolean.TRUE.equals(map.get("success"))) throw failure();
        // Kibana may assign new destination IDs (the NDJSON ID survives only as originId). Never assume
        // the fixed ID: require that every manifest object was imported, then resolve by origin.
        if (!(map.get("successResults") instanceof List<?> imported)) throw failure();
        for (Map<?, ?> expected : objects()) {
            boolean present = imported.stream()
                    .anyMatch(o -> o instanceof Map<?, ?> r
                            && expected.get("type").equals(r.get("type"))
                            && expected.get("id").equals(r.get("id")));
            if (!present) throw failure();
        }
        if (savedObjects().size() != objects().size()) throw failure();
    }

    void cleanup(String index) {
        ConfigExportador.guardIndex(index);
        // Validate the entire manifest before issuing even the first deletion.
        objects();
        request(config.elastic(), "DELETE", "/" + index, "", "application/json", List.of(200, 404));
        List<Map<?, ?>> found = new java.util.ArrayList<>(savedObjects());
        // Dashboards reference visualizations, which reference the index pattern.
        found.sort(java.util.Comparator.comparingInt(o -> TYPE_ORDER.indexOf(o.get("type"))));
        for (Map<?, ?> object : found) {
            request(
                    config.kibana(),
                    "DELETE",
                    spacePath() + "/api/saved_objects/" + object.get("type") + "/" + object.get("id"),
                    "",
                    "application/json",
                    List.of(200, 404));
        }
        // A silent miss would leave orphans behind: fail loudly instead.
        if (!savedObjects().isEmpty()) throw failure();
    }

    Map<?, ?> search(String index, String execution) {
        ConfigExportador.guardIndex(index);
        return (Map<?, ?>) json(request(
                config.elastic(),
                "POST",
                "/" + index + "/_search",
                Json.write(Map.of("query", Map.of("term", Map.of("execucao", execution)), "size", 100)),
                "application/json",
                List.of(200)));
    }

    void refresh(String index) {
        ConfigExportador.guardIndex(index);
        request(config.elastic(), "POST", "/" + index + "/_refresh", "", "application/json", List.of(200));
    }

    /**
     * PoC saved objects present in the space, resolved by manifest ID or {@code originId} (Kibana 9 may
     * rewrite the ID on import). Only objects whose type and origin are in the manifest are returned, so
     * the actual (possibly UUID) ID that callers receive is always one owned by the PoC.
     */
    List<Map<?, ?>> savedObjects() {
        Object result = json(request(
                config.kibana(),
                "GET",
                spacePath() + "/api/saved_objects/_find?type=index-pattern&type=visualization&type=dashboard"
                        + "&per_page=1000",
                "",
                "application/json",
                List.of(200)));
        if (!(result instanceof Map<?, ?> map) || !(map.get("saved_objects") instanceof List<?> all)) throw failure();
        List<Map<?, ?>> manifest = objects();
        List<Map<?, ?>> owned = new java.util.ArrayList<>();
        for (Object item : all) {
            if (!(item instanceof Map<?, ?> object)) continue;
            Object origin = object.get("originId") != null ? object.get("originId") : object.get("id");
            if (manifest.stream()
                    .noneMatch(m -> m.get("type").equals(object.get("type"))
                            && m.get("id").equals(origin))) continue;
            if (!(object.get("id") instanceof String id) || !id.matches("[A-Za-z0-9_-]{1,100}")) throw failure();
            owned.add(object);
        }
        return owned;
    }

    boolean indexExists(String index) {
        ConfigExportador.guardIndex(index);
        return request(config.elastic(), "HEAD", "/" + index, "", "application/json", List.of(200, 404))
                        .statusCode()
                == 200;
    }

    private String spacePath() {
        ConfigExportador.guardSpace(space);
        return "/s/" + space;
    }

    private static List<Map<?, ?>> objects() {
        return definition()
                .lines()
                .filter(l -> !l.isBlank())
                .<Map<?, ?>>map(l -> (Map<?, ?>) Json.read(l))
                .toList();
    }

    private static String definition() {
        try (var input = ElasticKibana.class.getResourceAsStream("/poc-kibana.ndjson")) {
            if (input == null) throw failure();
            String definition = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : definition.lines().toList()) {
                if (line.isBlank()) continue;
                Map<?, ?> object = (Map<?, ?>) Json.read(line);
                guardObject(object.get("type"), object.get("id"));
                if (object.get("references") instanceof List<?> refs) {
                    for (Object ref : refs) {
                        Map<?, ?> reference = (Map<?, ?>) ref;
                        guardObject(reference.get("type"), reference.get("id"));
                    }
                }
                if ("index-pattern".equals(object.get("type"))
                        && !((Map<?, ?>) object.get("attributes")).get("title").equals(ConfigExportador.PREFIX + "*"))
                    throw failure();
            }
            return definition;
        } catch (IOException | IllegalArgumentException | ClassCastException e) {
            throw failure();
        }
    }

    static void guardObject(Object type, Object id) {
        if (!List.of("index-pattern", "visualization", "dashboard").contains(type)
                || !(id instanceof String text)
                || !text.matches(ConfigExportador.PREFIX + "[a-z0-9-]+")) throw failure();
    }

    private HttpResponse<String> request(
            URI base, String method, String path, String body, String contentType, List<Integer> expected) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base.toString() + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", config.authorization())
                    .header("kbn-xsrf", "poc")
                    .header("Content-Type", contentType)
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (!expected.contains(response.statusCode())) {
                throw new IllegalStateException(
                        "PoC export HTTP status " + response.statusCode() + "; CSV evidence is unaffected");
            }
            return response;
        } catch (IOException | IllegalArgumentException e) {
            throw failure();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure();
        }
    }

    private static Object json(HttpResponse<String> response) {
        try {
            return Json.read(response.body());
        } catch (RuntimeException e) {
            throw failure();
        }
    }

    static IllegalStateException failure() {
        return new IllegalStateException("PoC export operation failed; CSV evidence is unaffected");
    }

    @Override
    public void close() {
        http.close();
    }
}
