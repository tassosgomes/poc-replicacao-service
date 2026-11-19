package br.org.ecad.poc.replicacao.bootstrap.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Function;

/**
 * Process configuration. Every key is read from a {@code -D} system property first and from the
 * environment second (the lookup is injected so tests stay hermetic); anything unset falls back
 * to the documented default. The PoC databases are throwaway: their users default to the
 * documented {@code bdad}/{@code replica} and the password keys exist to override them. A key set to a blank or malformed value is invalid and fails fast
 * with a one-line {@link ConfigException}.
 */
public final class Config {

    private final String legadoUrl;
    private final String legadoUsuario;
    private final String legadoSenha;
    private final String novoUrl;
    private final String novoUsuario;
    private final String novoSenha;
    private final String workerId;
    private final int loteMax;
    private final int pollMs;
    private final int leaseSeg;
    private final boolean virtualThreads;
    private final String resultadosDir;
    private final boolean eventosCsv;

    private Config(
            String legadoUrl,
            String legadoUsuario,
            String legadoSenha,
            String novoUrl,
            String novoUsuario,
            String novoSenha,
            String workerId,
            int loteMax,
            int pollMs,
            int leaseSeg,
            boolean virtualThreads,
            String resultadosDir,
            boolean eventosCsv) {
        this.legadoUrl = legadoUrl;
        this.legadoUsuario = legadoUsuario;
        this.legadoSenha = legadoSenha;
        this.novoUrl = novoUrl;
        this.novoUsuario = novoUsuario;
        this.novoSenha = novoSenha;
        this.workerId = workerId;
        this.loteMax = loteMax;
        this.pollMs = pollMs;
        this.leaseSeg = leaseSeg;
        this.virtualThreads = virtualThreads;
        this.resultadosDir = resultadosDir;
        this.eventosCsv = eventosCsv;
    }

    public static Config load(Function<String, String> lookup, Clock clock) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(clock, "clock");
        return new Config(
                text(lookup, "LEGADO_URL", "jdbc:oracle:thin:@//localhost:1521/FREEPDB1"),
                text(lookup, "LEGADO_USUARIO", "bdad"),
                text(lookup, "LEGADO_SENHA", "bdad"),
                text(lookup, "NOVO_URL", "jdbc:oracle:thin:@//localhost:1522/FREEPDB1"),
                text(lookup, "NOVO_USUARIO", "replica"),
                text(lookup, "NOVO_SENHA", "replica"),
                text(lookup, "WORKER_ID", defaultWorkerId(clock)),
                positiveInt(lookup, "LOTE_MAX", 500),
                nonNegativeInt(lookup, "POLL_MS", 200),
                positiveInt(lookup, "LEASE_SEG", 15),
                bool(lookup, "VIRTUAL_THREADS", true),
                text(lookup, "RESULTADOS_DIR", "resultados"),
                bool(lookup, "EVENTOS_CSV", false));
    }

    public String legadoUrl() {
        return legadoUrl;
    }

    public String legadoUsuario() {
        return legadoUsuario;
    }

    public String legadoSenha() {
        return legadoSenha;
    }

    public String novoUrl() {
        return novoUrl;
    }

    public String novoUsuario() {
        return novoUsuario;
    }

    public String novoSenha() {
        return novoSenha;
    }

    public String workerId() {
        return workerId;
    }

    public int loteMax() {
        return loteMax;
    }

    public int pollMs() {
        return pollMs;
    }

    public int leaseSeg() {
        return leaseSeg;
    }

    public boolean virtualThreads() {
        return virtualThreads;
    }

    public String resultadosDir() {
        return resultadosDir;
    }

    /** Opt-in event evidence for scenario percentiles, including latencies above the SLA. */
    public boolean eventosCsv() {
        return eventosCsv;
    }

    private static String text(Function<String, String> lookup, String key, String fallback) {
        String value = lookup.apply(key);
        if (value == null) {
            return fallback;
        }
        if (value.isBlank()) {
            throw new ConfigException(key + " must not be blank");
        }
        return value;
    }

    private static int positiveInt(Function<String, String> lookup, String key, int fallback) {
        int value = anyInt(lookup, key, fallback);
        if (value < 1) {
            throw new ConfigException(key + " must be a positive integer, got '" + value + "'");
        }
        return value;
    }

    private static int nonNegativeInt(Function<String, String> lookup, String key, int fallback) {
        int value = anyInt(lookup, key, fallback);
        if (value < 0) {
            throw new ConfigException(key + " must be a non-negative integer, got '" + value + "'");
        }
        return value;
    }

    private static int anyInt(Function<String, String> lookup, String key, int fallback) {
        String value = lookup.apply(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " must be an integer, got '" + value + "'");
        }
    }

    private static boolean bool(Function<String, String> lookup, String key, boolean fallback) {
        String value = lookup.apply(key);
        if (value == null) {
            return fallback;
        }
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new ConfigException(key + " must be 'true' or 'false', got '" + value + "'");
    }

    private static String defaultWorkerId(Clock clock) {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown";
        }
        return host + "-" + clock.millis();
    }
}
