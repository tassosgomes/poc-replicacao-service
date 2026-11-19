package br.org.ecad.poc.replicacao.exportador;

import br.org.ecad.poc.replicacao.bootstrap.config.ConfigException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** Credentials stay in laptop memory; never expose values through toString or errors. */
public final class ConfigExportador {
    public static final String PREFIX = "poc-replicacao-bdad-";
    public static final String SPACE = "poc-replicacao-bdad";
    private final URI elastic;
    private final URI kibana;
    private final String authorization;

    private ConfigExportador(URI elastic, URI kibana, String authorization) {
        this.elastic = elastic;
        this.kibana = kibana;
        this.authorization = authorization;
    }

    public static ConfigExportador load(Function<String, String> lookup) {
        String user = required(lookup, "KIBANA_ELASTIC_USER");
        String password = required(lookup, "KIBANA_ELASTIC_PASSWORD");
        if (user.contains(":")) throw new ConfigException("Invalid KIBANA_ELASTIC_USER");
        return new ConfigExportador(
                endpoint(lookup, "ELASTIC_HOST"),
                endpoint(lookup, "KIBANA_HOST"),
                "Basic "
                        + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
    }

    /** No sourcing, interpolation, subprocess, system properties, or exported environment. */
    public static ConfigExportador laptop(Path env) {
        Map<String, String> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(env, StandardCharsets.UTF_8)) {
                int equals = line.indexOf('=');
                if (equals < 0) continue;
                String key = line.substring(0, equals).trim();
                if (!java.util.Set.of("ELASTIC_HOST", "KIBANA_HOST", "KIBANA_ELASTIC_USER", "KIBANA_ELASTIC_PASSWORD")
                        .contains(key)) continue;
                String value = line.substring(equals + 1).trim();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                                || (value.startsWith("'") && value.endsWith("'"))))
                    value = value.substring(1, value.length() - 1);
                values.put(key, value);
            }
            return load(values::get);
        } catch (IOException e) {
            throw new ConfigException("Cannot load laptop .env");
        }
    }

    URI elastic() {
        return elastic;
    }

    URI kibana() {
        return kibana;
    }

    String authorization() {
        return authorization;
    }

    public static String index(String execution) {
        if (execution == null || !execution.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,100}"))
            throw new ConfigException("Invalid execution identifier");
        // Hex preserves case and avoids collisions between distinct execution identifiers.
        return PREFIX + java.util.HexFormat.of().formatHex(execution.getBytes(StandardCharsets.UTF_8));
    }

    static void guardIndex(String index) {
        if (index == null || !index.matches(PREFIX + "[a-z0-9][a-z0-9_-]{0,220}"))
            throw new ConfigException("Target outside PoC namespace");
    }

    static void guardSpace(String space) {
        if (!SPACE.equals(space) && !(SPACE + "-teste").equals(space))
            throw new ConfigException("Target outside PoC namespace");
    }

    private static URI endpoint(Function<String, String> lookup, String key) {
        try {
            URI uri = URI.create(required(lookup, key).replaceAll("/+$", ""));
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException e) {
            throw new ConfigException("Invalid " + key);
        }
    }

    private static String required(Function<String, String> lookup, String key) {
        String value = lookup.apply(key);
        if (value == null || value.isBlank()) throw new ConfigException("Missing " + key);
        return value;
    }
}
