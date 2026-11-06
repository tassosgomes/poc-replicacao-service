package br.org.ecad.poc.replicacao.application.port;

import br.org.ecad.poc.replicacao.domain.coordenacao.Particao;
import java.util.List;

/** Runtime operations requested by the partition coordination policy. */
public interface ExecucaoParticoes {
    List<Particao> emExecucao();

    void renovarPrazo(Particao particao, long deadline);

    void parar(Particao particao);

    void iniciar(Particao particao, long deadline);

    void possuidas(int quantidade);

    void encerrar();
}
