package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

import java.util.Locale;
import java.util.Set;

/**
 * How the Debezium JSON (no schema) encodes the types the worker needs. The exact shape depends on
 * the connector settings ({@code time.precision.mode}, {@code decimal.handling.mode}), so it is
 * configurable instead of assumed.
 *
 * @param unidadeTemporal unit of numeric temporals (epoch); {@link UnidadeTemporal#AUTO} infers it from the magnitude
 * @param colunasNumericas business columns (lowercase) that must reach the sink as numbers even when the connector
 *     emits them as text or base64 decimals
 * @param escalaDecimalBase64 scale applied to a base64 {@code BigDecimal} (Connect {@code Decimal} without schema
 *     loses its scale; the PoC columns are integers, so 0)
 */
public record FormatoDebezium(UnidadeTemporal unidadeTemporal, Set<String> colunasNumericas, int escalaDecimalBase64) {

    public FormatoDebezium {
        colunasNumericas = Set.copyOf(colunasNumericas);
    }

    /** Epoch unit of a numeric temporal. Text temporals are always parsed as ISO-8601. */
    public enum UnidadeTemporal {
        AUTO,
        MILLIS,
        MICROS,
        NANOS;

        public static UnidadeTemporal de(String texto) {
            return valueOf(texto.trim().toUpperCase(Locale.ROOT));
        }
    }

    /** Matches the lab connector: {@code time.precision.mode=adaptive} (micros) and {@code decimal.handling.mode=string}. */
    public static FormatoDebezium padrao() {
        return new FormatoDebezium(
                UnidadeTemporal.MICROS, Set.of("codigo", "excluido", "obra_codigo", "duracao_seg"), 0);
    }
}
