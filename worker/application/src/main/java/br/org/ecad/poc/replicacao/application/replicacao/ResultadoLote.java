package br.org.ecad.poc.replicacao.application.replicacao;

/** What one batch did: events consumed, rows applied and keys that vanished from the origin. */
public record ResultadoLote(int eventos, int linhas, int anomalias) {

    public static final ResultadoLote VAZIO = new ResultadoLote(0, 0, 0);
}
