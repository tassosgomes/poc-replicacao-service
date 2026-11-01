package br.org.ecad.poc.replicacao.exportador;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only known metric schemas enter Elasticsearch; CSV input is never logged. */
final class CsvSample {
    static final String WORKER =
            "ts,particoes,eventos_s,linhas_s,coalesce,p50_ms,p95_ms,p99_ms,max_ms,pendentes,lag_ms,anomalias,ms_poll,ms_busca,ms_upsert,ms_ack,skew_ms,skew_incerteza_ms,transacoes_longas,valida";
    static final String RESOURCES = "ts,container,cpu_percent,memoria,sessoes_ativas";
    private String header = "";

    Map<String, Object> parse(String line, ExportContext context) {
        if (line.equals(WORKER) || line.equals(RESOURCES)) {
            header = line;
            return Map.of();
        }
        if (header.isEmpty() || line.isBlank()) return Map.of();
        String[] fields = header.split(",");
        String[] values = line.split(",", -1);
        if (fields.length != values.length) throw ElasticKibana.failure();
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("cenario", context.scenario());
        document.put("execucao", context.execution());
        document.put("fonte", context.source());
        document.put("tipo", header.equals(WORKER) ? "worker" : "recursos");
        try {
            for (int i = 0; i < fields.length; i++) {
                Object value = switch (fields[i]) {
                    case "ts" -> Instant.parse(values[i]).toString();
                    case "container" -> {
                        if (!values[i].matches("poc-[A-Za-z0-9_-]+")) throw ElasticKibana.failure();
                        yield values[i];
                    }
                    case "memoria" -> {
                        if (!values[i].matches("[0-9a-zA-Z./ ]{1,80}")) throw ElasticKibana.failure();
                        yield values[i];
                    }
                    case "valida" -> {
                        if (!values[i].equals("VALIDA") && !values[i].equals("INVALIDA")) throw ElasticKibana.failure();
                        yield values[i];
                    }
                    default -> {
                        double number = Double.parseDouble(values[i].replace("%", ""));
                        if (!Double.isFinite(number)) throw ElasticKibana.failure();
                        yield number;
                    }
                };
                document.put(fields[i], value);
            }
            return document;
        } catch (RuntimeException e) {
            throw ElasticKibana.failure();
        }
    }

    static String id(ExportContext context, String line) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest((context.execution() + "\n" + context.source() + "\n" + line)
                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw ElasticKibana.failure();
        }
    }

    record ExportContext(String scenario, String execution, String source) {
        ExportContext {
            if (scenario == null
                    || !scenario.matches("[A-Za-z0-9_-]{1,80}")
                    || source == null
                    || !source.matches("[A-Za-z0-9_./:-]{1,300}")) throw ElasticKibana.failure();
            ConfigExportador.index(execution);
        }
    }
}
