package br.org.ecad.poc.replicacao.bootstrap.kafka.config;

import br.org.ecad.poc.replicacao.infra.kafka.replicacao.FormatoDebezium;
import br.org.ecad.poc.replicacao.infra.kafka.replicacao.MapaTopicos;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Configuration of the Kafka worker. Same rules as the lab Java worker: each key is read from a
 * {@code -D} property first and from the environment second (injected lookup, hermetic tests); a key
 * set to a blank or malformed value fails fast with a one-line {@link ConfigException}. There is no
 * legacy database here: only the destination (lease, fence and sink) and Kafka.
 *
 * <p>Kafka security (SASL_SSL of Confluent Cloud, for instance) is never hardcoded and never logged:
 * {@code KAFKA_PROPERTIES_FILE} points to a {@code .properties} file and every environment variable
 * {@code KAFKA_PROP_<NAME>} becomes the property {@code <name>} in lower case with {@code _} turned
 * into {@code .} (so {@code KAFKA_PROP_SASL_JAAS_CONFIG} is {@code sasl.jaas.config}). The environment
 * wins over the file.
 */
public final class Config {

    static final String PREFIXO_PROPRIEDADE = "KAFKA_PROP_";

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
    private final String bootstrapServers;
    private final String groupId;
    private final MapaTopicos topicos;
    private final int kafkaPollMs;
    private final FormatoDebezium formato;
    private final Map<String, String> propriedadesKafka;

    private Config(Builder b) {
        this.novoUrl = b.novoUrl;
        this.novoUsuario = b.novoUsuario;
        this.novoSenha = b.novoSenha;
        this.workerId = b.workerId;
        this.loteMax = b.loteMax;
        this.pollMs = b.pollMs;
        this.leaseSeg = b.leaseSeg;
        this.virtualThreads = b.virtualThreads;
        this.resultadosDir = b.resultadosDir;
        this.eventosCsv = b.eventosCsv;
        this.bootstrapServers = b.bootstrapServers;
        this.groupId = b.groupId;
        this.topicos = b.topicos;
        this.kafkaPollMs = b.kafkaPollMs;
        this.formato = b.formato;
        this.propriedadesKafka = Map.copyOf(b.propriedadesKafka);
    }

    private static final class Builder {
        String novoUrl;
        String novoUsuario;
        String novoSenha;
        String workerId;
        int loteMax;
        int pollMs;
        int leaseSeg;
        boolean virtualThreads;
        String resultadosDir;
        boolean eventosCsv;
        String bootstrapServers;
        String groupId;
        MapaTopicos topicos;
        int kafkaPollMs;
        FormatoDebezium formato;
        Map<String, String> propriedadesKafka;
    }

    public static Config load(Function<String, String> lookup, Map<String, String> ambiente, Clock clock) {
        Objects.requireNonNull(lookup, "lookup");
        Objects.requireNonNull(ambiente, "ambiente");
        Objects.requireNonNull(clock, "clock");
        Builder b = new Builder();
        b.novoUrl = text(lookup, "NOVO_URL", "jdbc:oracle:thin:@//localhost:1526/FREEPDB1");
        b.novoUsuario = text(lookup, "NOVO_USUARIO", "replica");
        b.novoSenha = text(lookup, "NOVO_SENHA", "replica");
        b.workerId = text(lookup, "WORKER_ID", defaultWorkerId(clock));
        b.loteMax = positiveInt(lookup, "LOTE_MAX", 500);
        b.pollMs = nonNegativeInt(lookup, "POLL_MS", 200);
        b.leaseSeg = positiveInt(lookup, "LEASE_SEG", 15);
        b.virtualThreads = bool(lookup, "VIRTUAL_THREADS", true);
        b.resultadosDir = text(lookup, "RESULTADOS_DIR", "resultados");
        b.eventosCsv = bool(lookup, "EVENTOS_CSV", false);
        b.bootstrapServers = text(lookup, "KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        b.groupId = text(lookup, "KAFKA_GROUP_ID", "kf-worker");
        b.kafkaPollMs = positiveInt(lookup, "KAFKA_POLL_MS", 50);
        b.topicos = topicos(lookup);
        b.formato = formato(lookup);
        b.propriedadesKafka = propriedades(lookup, ambiente);
        return new Config(b);
    }

    private static MapaTopicos topicos(Function<String, String> lookup) {
        String prefixo = text(lookup, "KAFKA_TOPIC_PREFIX", "kf.BDAD");
        Map<String, String> excecoes = new LinkedHashMap<>();
        String lista = lookup.apply("KAFKA_TOPICS");
        if (lista != null) {
            if (lista.isBlank()) {
                throw new ConfigException("KAFKA_TOPICS must not be blank");
            }
            for (String par : lista.split(",")) {
                String[] partes = par.split("=", 2);
                if (partes.length != 2 || partes[0].isBlank() || partes[1].isBlank()) {
                    throw new ConfigException("KAFKA_TOPICS must be TABLE=topic[,TABLE=topic], got '" + par + "'");
                }
                excecoes.put(partes[0].trim(), partes[1].trim());
            }
        }
        try {
            return MapaTopicos.de(prefixo, excecoes);
        } catch (IllegalArgumentException e) {
            throw new ConfigException("KAFKA_TOPICS: " + e.getMessage());
        }
    }

    private static FormatoDebezium formato(Function<String, String> lookup) {
        FormatoDebezium padrao = FormatoDebezium.padrao();
        FormatoDebezium.UnidadeTemporal unidade = padrao.unidadeTemporal();
        String texto = lookup.apply("DEBEZIUM_TEMPORAL");
        if (texto != null) {
            try {
                unidade = FormatoDebezium.UnidadeTemporal.de(texto);
            } catch (IllegalArgumentException e) {
                throw new ConfigException("DEBEZIUM_TEMPORAL must be one of "
                        + Arrays.toString(FormatoDebezium.UnidadeTemporal.values()) + ", got '" + texto + "'");
            }
        }
        Set<String> numericas = new TreeSet<>(padrao.colunasNumericas());
        String extra = lookup.apply("DEBEZIUM_COLUNAS_NUMERICAS");
        if (extra != null) {
            numericas.clear();
            for (String coluna : extra.split(",")) {
                if (!coluna.isBlank()) {
                    numericas.add(coluna.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return new FormatoDebezium(
                unidade, numericas, nonNegativeInt(lookup, "DEBEZIUM_ESCALA_BASE64", padrao.escalaDecimalBase64()));
    }

    private static Map<String, String> propriedades(Function<String, String> lookup, Map<String, String> ambiente) {
        Map<String, String> props = new HashMap<>();
        String arquivo = lookup.apply("KAFKA_PROPERTIES_FILE");
        if (arquivo != null) {
            if (arquivo.isBlank()) {
                throw new ConfigException("KAFKA_PROPERTIES_FILE must not be blank");
            }
            Properties lidas = new Properties();
            try (InputStream in = Files.newInputStream(Path.of(arquivo))) {
                lidas.load(in);
            } catch (IOException e) {
                throw new ConfigException("cannot read KAFKA_PROPERTIES_FILE '" + arquivo + "'");
            }
            lidas.forEach((k, v) -> props.put(k.toString(), v.toString()));
        }
        ambiente.forEach((chave, valor) -> {
            if (chave.startsWith(PREFIXO_PROPRIEDADE) && chave.length() > PREFIXO_PROPRIEDADE.length()) {
                props.put(
                        chave.substring(PREFIXO_PROPRIEDADE.length())
                                .toLowerCase(Locale.ROOT)
                                .replace('_', '.'),
                        valor);
            }
        });
        return props;
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

    public boolean eventosCsv() {
        return eventosCsv;
    }

    public String bootstrapServers() {
        return bootstrapServers;
    }

    public String groupId() {
        return groupId;
    }

    public MapaTopicos topicos() {
        return topicos;
    }

    /** Wait of each consumer poll when the partition has nothing to read. */
    public int kafkaPollMs() {
        return kafkaPollMs;
    }

    public FormatoDebezium formato() {
        return formato;
    }

    /** Extra consumer properties (security). Sensitive: never print. */
    public Map<String, String> propriedadesKafka() {
        return propriedadesKafka;
    }

    @Override
    public String toString() {
        return "Config[workerId=" + workerId + ", bootstrapServers=" + bootstrapServers + ", groupId=" + groupId
                + ", propriedadesKafka=" + propriedadesKafka.keySet() + "]";
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
