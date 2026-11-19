package br.org.ecad.poc.replicacao.application.coordenacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.ecad.poc.replicacao.application.port.ExecucaoParticoes;
import br.org.ecad.poc.replicacao.application.port.LeaseRepository;
import br.org.ecad.poc.replicacao.domain.coordenacao.Particao;
import br.org.ecad.poc.replicacao.domain.replicacao.Esquema;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoordenadorParticoesTest {
    private final Particao primeira = new Particao(Esquema.TITULAR, 0);
    private final Particao segunda = new Particao(Esquema.TITULAR, 1);
    private final List<String> operacoes = new ArrayList<>();

    @Test
    void coordenar_whenOverQuota_stopsBeforeReleaseAndConfirmsBeforeStarting() {
        Sessao sessao = new Sessao(48, List.of(primeira, segunda), List.of());
        Execucao execucao = new Execucao(List.of(primeira, segunda));
        new CoordenadorParticoes(() -> sessao, execucao, 15).coordenar(System.nanoTime());

        assertThat(operacoes)
                .containsExactly(
                        "heartbeat",
                        "renovar",
                        "commit",
                        "prazo:0",
                        "prazo:1",
                        "parar:0",
                        "liberar:0",
                        "commit",
                        "adquirir:0",
                        "commit",
                        "possuidas:1",
                        "iniciar:1",
                        "close");
    }

    @Test
    void coordenar_whenLeaseLost_stopsOldPartitionBeforeClaimingReplacement() {
        Sessao sessao = new Sessao(48, List.of(), List.of(segunda));
        Execucao execucao = new Execucao(List.of(primeira));
        new CoordenadorParticoes(() -> sessao, execucao, 15).coordenar(System.nanoTime());

        assertThat(operacoes)
                .containsExactly(
                        "heartbeat",
                        "renovar",
                        "commit",
                        "parar:0",
                        "adquirir:1",
                        "commit",
                        "possuidas:1",
                        "iniciar:1",
                        "close");
    }

    @Test
    void coordenar_whenCycleExceedsDeadline_doesNotStartProcessors() {
        Sessao sessao = new Sessao(48, List.of(), List.of(primeira));
        new CoordenadorParticoes(() -> sessao, new Execucao(List.of()), 15)
                .coordenar(System.nanoTime() - java.util.concurrent.TimeUnit.SECONDS.toNanos(20));
        assertThat(operacoes).doesNotContain("iniciar:0");
    }

    private final class Sessao implements LeaseRepository.Sessao {
        private final int ativos;
        private final List<Particao> owned;
        private final List<Particao> claimed;

        Sessao(int ativos, List<Particao> owned, List<Particao> claimed) {
            this.ativos = ativos;
            this.owned = owned;
            this.claimed = claimed;
        }

        public int heartbeat() {
            operacoes.add("heartbeat");
            return ativos;
        }

        public List<Particao> renovar() {
            operacoes.add("renovar");
            return new ArrayList<>(owned);
        }

        public List<Particao> adquirir(int quantidade) {
            operacoes.add("adquirir:" + quantidade);
            return claimed;
        }

        public void liberar(Particao p) {
            operacoes.add("liberar:" + p.numero());
        }

        public void confirmar() {
            operacoes.add("commit");
        }

        public void liberarTodos() {
            operacoes.add("liberarTodos");
        }

        public void sair() {
            operacoes.add("sair");
        }

        public void close() {
            operacoes.add("close");
        }
    }

    private final class Execucao implements ExecucaoParticoes {
        private final List<Particao> running;

        Execucao(List<Particao> running) {
            this.running = running;
        }

        public List<Particao> emExecucao() {
            return running;
        }

        public void renovarPrazo(Particao p, long deadline) {
            operacoes.add("prazo:" + p.numero());
        }

        public void parar(Particao p) {
            operacoes.add("parar:" + p.numero());
        }

        public void iniciar(Particao p, long deadline) {
            operacoes.add("iniciar:" + p.numero());
        }

        public void possuidas(int quantidade) {
            operacoes.add("possuidas:" + quantidade);
        }

        public void encerrar() {
            operacoes.add("encerrar");
        }
    }
}
