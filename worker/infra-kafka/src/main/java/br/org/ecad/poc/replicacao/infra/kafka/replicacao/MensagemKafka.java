package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

/** The part of a consumed record the translator needs. {@code valor} is null for a tombstone. */
public record MensagemKafka(String topico, int particao, long offset, long timestampMs, String valor) {}
