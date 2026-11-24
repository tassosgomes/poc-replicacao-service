package br.org.ecad.poc.replicacao.application.port;

/** One ordered stream of the transactional source: a topic partition (a table partition or the transaction topic). */
public record Fluxo(String topico, int particao) {}
