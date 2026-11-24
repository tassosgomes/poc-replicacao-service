package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

/**
 * Consumer properties: defaults owned by the worker (manual commit, no automatic reset, string
 * deserializers) plus the caller's extras, which carry the security settings (SASL_SSL for Confluent
 * Cloud) read from the environment or a file, never hardcoded. Settings the design depends on are
 * applied last and cannot be overridden.
 */
public final class PropriedadesConsumidor {

    private PropriedadesConsumidor() {}

    public static Map<String, Object> montar(String bootstrapServers, String groupId, Map<String, String> extras) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000);
        props.putAll(extras);
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // The position is set explicitly (committed or beginning); never a silent jump to "latest".
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return props;
    }

    /** One {@link KafkaConsumer} per partition, with a unique {@code client.id} (JMX). */
    public static KafkaChangeSource.FabricaConsumidor fabrica(Map<String, Object> props, String clientIdBase) {
        return particao -> new KafkaConsumer<>(comClientId(props, clientIdBase + "-" + sufixo(particao)));
    }

    public static KafkaConsumer<String, String> paraLag(Map<String, Object> props, String clientIdBase) {
        return new KafkaConsumer<>(comClientId(props, clientIdBase + "-lag"));
    }

    private static Map<String, Object> comClientId(Map<String, Object> props, String clientId) {
        Map<String, Object> copia = new HashMap<>(props);
        copia.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId);
        return copia;
    }

    private static String sufixo(TopicPartition tp) {
        return tp.topic() + "-" + tp.partition();
    }
}
