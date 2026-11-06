package br.org.ecad.poc.replicacao.reconciliacao;

import br.org.ecad.poc.replicacao.bootstrap.config.ConfigException;
import java.util.function.Function;

/**
 * Options of the {@code reconciliacao} subcommand. {@code codigoMin} (inclusive) and {@code codigoMax}
 * (exclusive) restrict the comparison, and the events it creates, to a key range; the defaults cover
 * every key. The range lets a run on shared databases touch only its own rows.
 */
public record ConfigReconciliacao(
        String modo, boolean dryRun, int desdeMin, int janelaMin, long codigoMin, long codigoMax) {
    public ConfigReconciliacao(String modo, boolean dryRun, int desdeMin, int janelaMin) {
        this(modo, dryRun, desdeMin, janelaMin, 0, Long.MAX_VALUE);
    }

    public static ConfigReconciliacao load(Function<String, String> lookup) {
        String modo = value(lookup, "MODO", "completo");
        if (!modo.equals("delta") && !modo.equals("completo"))
            throw new ConfigException("MODO must be delta or completo");
        String dry = value(lookup, "DRY_RUN", "true");
        if (!dry.equalsIgnoreCase("true") && !dry.equalsIgnoreCase("false"))
            throw new ConfigException("DRY_RUN must be true or false");
        long min = bound(lookup, "CODIGO_MIN", 0);
        long max = bound(lookup, "CODIGO_MAX", Long.MAX_VALUE);
        if (max <= min) throw new ConfigException("CODIGO_MAX must be greater than CODIGO_MIN");
        return new ConfigReconciliacao(
                modo,
                Boolean.parseBoolean(dry),
                number(lookup, "DESDE_MIN", 60),
                number(lookup, "JANELA_MIN", 5),
                min,
                max);
    }

    private static String value(Function<String, String> lookup, String key, String fallback) {
        String value = lookup.apply(key);
        return value == null ? fallback : value;
    }

    private static int number(Function<String, String> lookup, String key, int fallback) {
        try {
            int n = Integer.parseInt(value(lookup, key, Integer.toString(fallback)));
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " must be a non-negative integer");
        }
    }

    private static long bound(Function<String, String> lookup, String key, long fallback) {
        String raw = lookup.apply(key);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            long n = Long.parseLong(raw.trim());
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " must be a non-negative integer");
        }
    }
}
