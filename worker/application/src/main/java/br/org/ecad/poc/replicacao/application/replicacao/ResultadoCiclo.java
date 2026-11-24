package br.org.ecad.poc.replicacao.application.replicacao;

/** What one cycle of the transactional processor did. */
public record ResultadoCiclo(int registros, int transacoes, int eventosAplicados) {

    public static final ResultadoCiclo VAZIO = new ResultadoCiclo(0, 0, 0);

    public boolean ocioso() {
        return registros == 0 && eventosAplicados == 0;
    }
}
