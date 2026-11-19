package br.org.ecad.poc.replicacao.carga;

import br.org.ecad.poc.replicacao.bootstrap.config.ConfigException;
import br.org.ecad.poc.replicacao.domain.replicacao.Esquema;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

public record ConfigCarga(int threads, List<TableSpec> tabelas, long codigoMin, long codigoMax) {
    public ConfigCarga {
        tabelas = List.copyOf(tabelas);
        if (threads < 1 || tabelas.isEmpty() || codigoMin < 0 || codigoMax <= codigoMin) {
            throw new ConfigException("invalid carga-inicial configuration");
        }
    }

    public static ConfigCarga load(Function<String, String> lookup) {
        int threads;
        try {
            String raw = lookup.apply("THREADS");
            threads = raw == null ? 4 : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("THREADS must be a positive integer");
        }
        String raw = lookup.apply("TABELAS");
        List<TableSpec> tabelas = raw == null
                ? Esquema.TABELAS
                : Arrays.stream(raw.split(",", -1))
                        .map(String::trim)
                        .map(nome -> Esquema.TABELAS.stream()
                                .filter(t -> t.nome().equalsIgnoreCase(nome))
                                .findFirst()
                                .orElseThrow(() -> new ConfigException("invalid TABELAS: " + nome)))
                        .distinct()
                        .toList();
        return new ConfigCarga(
                threads, tabelas, bound(lookup, "CODIGO_MIN", 0), bound(lookup, "CODIGO_MAX", Long.MAX_VALUE));
    }

    private static long bound(Function<String, String> lookup, String key, long fallback) {
        String raw = lookup.apply(key);
        try {
            return raw == null ? fallback : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(key + " must be a non-negative integer");
        }
    }
}
