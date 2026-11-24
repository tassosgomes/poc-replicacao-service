package br.org.ecad.poc.replicacao.bootstrap.kafka.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.org.ecad.poc.replicacao.infra.kafka.replicacao.FormatoDebezium;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);

    private static Config carregar(Map<String, String> env) {
        return Config.load(env::get, env, CLOCK);
    }

    @Test
    void load_semNada_usaDefaults() {
        Config c = carregar(Map.of());

        assertThat(c.bootstrapServers()).isEqualTo("localhost:9092");
        assertThat(c.groupId()).isEqualTo("kf-worker");
        assertThat(c.topicos().topico("TITULAR")).isEqualTo("kf.BDAD.TITULAR");
        assertThat(c.loteMax()).isEqualTo(500);
        assertThat(c.propriedadesKafka()).isEmpty();
        assertThat(c.formato().unidadeTemporal()).isEqualTo(FormatoDebezium.UnidadeTemporal.MICROS);
    }

    @Test
    void load_topicosPorPrefixoEExcecao() {
        Config c = carregar(Map.of("KAFKA_TOPIC_PREFIX", "x.S", "KAFKA_TOPICS", "FONOGRAMA=fono.topic"));

        assertThat(c.topicos().topico("TITULAR")).isEqualTo("x.S.TITULAR");
        assertThat(c.topicos().topico("FONOGRAMA")).isEqualTo("fono.topic");
    }

    @Test
    void load_propriedadesDoAmbienteViramPropriedadesKafka_eNaoVazamNoToString() {
        Config c = carregar(Map.of(
                "KAFKA_PROP_SECURITY_PROTOCOL", "SASL_SSL",
                "KAFKA_PROP_SASL_JAAS_CONFIG", "segredo-do-teste"));

        assertThat(c.propriedadesKafka())
                .containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.jaas.config", "segredo-do-teste");
        assertThat(c.toString()).doesNotContain("segredo-do-teste");
    }

    @Test
    void load_arquivoDePropriedades_eSobrescritoPeloAmbiente(@TempDir Path dir) throws IOException {
        Path arquivo = dir.resolve("kafka.properties");
        Files.writeString(arquivo, "sasl.mechanism=PLAIN\nsecurity.protocol=PLAINTEXT\n");

        Config c = carregar(Map.of("KAFKA_PROPERTIES_FILE", arquivo.toString(), "KAFKA_PROP_SECURITY_PROTOCOL", "SSL"));

        assertThat(c.propriedadesKafka())
                .containsEntry("sasl.mechanism", "PLAIN")
                .containsEntry("security.protocol", "SSL");
    }

    @Test
    void load_valoresInvalidos_falhamEmUmaLinha() {
        assertThatThrownBy(() -> carregar(Map.of("KAFKA_BOOTSTRAP_SERVERS", " ")))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> carregar(Map.of("KAFKA_TOPICS", "TITULAR"))).isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> carregar(Map.of("KAFKA_TOPICS", "NADA=x"))).isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> carregar(Map.of("DEBEZIUM_TEMPORAL", "dias"))).isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> carregar(Map.of("KAFKA_PROPERTIES_FILE", "/nao/existe")))
                .isInstanceOf(ConfigException.class);
        assertThatThrownBy(() -> carregar(Map.of("LOTE_MAX", "0"))).isInstanceOf(ConfigException.class);
    }

    @Test
    void load_modoPadraoEAtual_eTransacionalPorConfiguracao() {
        assertThat(carregar(Map.of()).modo()).isEqualTo(Config.Modo.ATUAL);

        Config c = carregar(Map.of("MODO", "transacional", "TX_GRUPO_MAX", "7", "KAFKA_INICIO", "inicio"));

        assertThat(c.modo()).isEqualTo(Config.Modo.TRANSACIONAL);
        assertThat(c.txTransacoesPorCommit()).isEqualTo(7);
        assertThat(c.inicioNoFim()).isFalse();
        assertThat(c.topicoTransacoes()).isEqualTo("kf.transaction");
    }

    @Test
    void load_modoInvalido_falhaComUmaLinha() {
        assertThatThrownBy(() -> carregar(Map.of("MODO", "paralelo")))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("MODO");
    }
}
