package br.org.ecad.poc.replicacao.application.port;

import br.org.ecad.poc.replicacao.domain.coordenacao.Particao;
import java.util.List;

/** Coordination transaction. Confirmation boundaries remain explicit without exposing JDBC. */
public interface LeaseRepository {
    int MARGEM_SEG = 2;

    Sessao abrir();

    interface Sessao extends AutoCloseable {
        int heartbeat();

        List<Particao> renovar();

        List<Particao> adquirir(int quantidade);

        void liberar(Particao particao);

        void liberarTodos();

        void sair();

        void confirmar();

        @Override
        void close();
    }
}
