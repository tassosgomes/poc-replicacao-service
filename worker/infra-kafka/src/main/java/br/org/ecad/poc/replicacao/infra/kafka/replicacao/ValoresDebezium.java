package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** Conversion of Debezium JSON values (no schema) to what the sink binds with {@code setObject}. */
final class ValoresDebezium {

    private static final Pattern NUMERO = Pattern.compile("[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");
    private static final long LIMITE_MILIS = 100_000_000_000_000L; // year ~5138 in ms, 1973 in us
    private static final long LIMITE_MICROS = 100_000_000_000_000_000L; // year ~5138 in us, 1973 in ns

    private final FormatoDebezium formato;

    ValoresDebezium(FormatoDebezium formato) {
        this.formato = formato;
    }

    /** Value of a business column: numbers become Long (or BigDecimal when fractional or too large). */
    Object coluna(String coluna, JsonNode no) {
        if (no == null || no.isNull()) {
            return null;
        }
        boolean numerica = formato.colunasNumericas().contains(coluna.toLowerCase(java.util.Locale.ROOT));
        if (no.isBoolean()) {
            return no.booleanValue() ? 1L : 0L;
        }
        if (no.isNumber()) {
            return numero(no.decimalValue());
        }
        if (no.isObject()) {
            return numero(decimalVariavel(coluna, no));
        }
        String texto = no.asString();
        if (!numerica) {
            return texto;
        }
        return numero(decimalDeTexto(coluna, texto));
    }

    /** Instant of a temporal column ({@code updated_at}): epoch number (any unit) or ISO-8601 text. */
    Instant instante(String coluna, JsonNode no) {
        if (no == null || no.isNull()) {
            return null;
        }
        if (no.isNumber()) {
            return epoch(no.longValue());
        }
        if (no.isString()) {
            String texto = no.asString().trim();
            if (NUMERO.matcher(texto).matches() && texto.indexOf('.') < 0) {
                return epoch(Long.parseLong(texto));
            }
            return iso(coluna, texto);
        }
        throw new ReplicacaoException("temporal column " + coluna + " has an unsupported shape: " + no, null);
    }

    Instant epoch(long valor) {
        FormatoDebezium.UnidadeTemporal unidade = formato.unidadeTemporal();
        if (unidade == FormatoDebezium.UnidadeTemporal.AUTO) {
            long abs = Math.abs(valor);
            unidade = abs < LIMITE_MILIS
                    ? FormatoDebezium.UnidadeTemporal.MILLIS
                    : abs < LIMITE_MICROS
                            ? FormatoDebezium.UnidadeTemporal.MICROS
                            : FormatoDebezium.UnidadeTemporal.NANOS;
        }
        return switch (unidade) {
            case MILLIS -> Instant.ofEpochMilli(valor);
            case MICROS ->
                Instant.ofEpochSecond(Math.floorDiv(valor, 1_000_000L), Math.floorMod(valor, 1_000_000L) * 1_000L);
            case NANOS ->
                Instant.ofEpochSecond(Math.floorDiv(valor, 1_000_000_000L), Math.floorMod(valor, 1_000_000_000L));
            case AUTO -> throw new IllegalStateException("unreachable");
        };
    }

    private static Instant iso(String coluna, String texto) {
        try {
            return Instant.parse(texto);
        } catch (DateTimeParseException ignorada) {
            // try the next shape
        }
        try {
            return OffsetDateTime.parse(texto).toInstant();
        } catch (DateTimeParseException ignorada) {
            // try the next shape
        }
        try {
            // No zone: Debezium encodes TIMESTAMP as the local value; the PoC stores it as UTC.
            return LocalDateTime.parse(texto).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new ReplicacaoException("temporal column " + coluna + " is not ISO-8601: " + texto, e);
        }
    }

    /** Debezium {@code VariableScaleDecimal}: {@code {"scale": n, "value": "<base64 unscaled>"}}. */
    private BigDecimal decimalVariavel(String coluna, JsonNode no) {
        JsonNode valor = no.get("value");
        JsonNode escala = no.get("scale");
        if (valor == null || escala == null || !valor.isString() || !escala.isNumber()) {
            throw new ReplicacaoException("column " + coluna + " has an unsupported object shape: " + no, null);
        }
        return new BigDecimal(new BigInteger(Base64.getDecoder().decode(valor.asString())), escala.intValue());
    }

    private BigDecimal decimalDeTexto(String coluna, String texto) {
        String limpo = texto.trim();
        if (NUMERO.matcher(limpo).matches()) {
            return new BigDecimal(limpo);
        }
        try {
            // Connect Decimal without schema arrives as base64 of the unscaled value.
            return new BigDecimal(new BigInteger(Base64.getDecoder().decode(limpo)), formato.escalaDecimalBase64());
        } catch (IllegalArgumentException e) {
            throw new ReplicacaoException("numeric column " + coluna + " is neither a number nor base64: " + texto, e);
        }
    }

    private static Object numero(BigDecimal valor) {
        BigDecimal semZeros = valor.stripTrailingZeros();
        if (semZeros.scale() <= 0) {
            try {
                return semZeros.longValueExact();
            } catch (ArithmeticException e) {
                return semZeros.setScale(0);
            }
        }
        return valor;
    }
}
