package br.org.ecad.poc.replicacao.gerador;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class V06SelecaoTest {
    @Test
    void agendadorDeTaxaEEscolhaPorMixEHot() {
        // Agendador: linhas são devidas a 1/taxa uma da outra, a partir do início.
        var agendador = new Agendador(1_000, 250);
        assertThat(agendador.proximo(3)).isEqualTo(1_000);
        assertThat(agendador.proximo(1)).isEqualTo(1_000 + 3 * 4_000_000L);
        assertThat(agendador.proximo(5)).isEqualTo(1_000 + 4 * 4_000_000L);
        assertThat(agendador.proximo(1)).isEqualTo(1_000 + 9 * 4_000_000L);

        // Mix e hot: distribuição e repetibilidade com a mesma semente.
        var r = new SplittableRandom(123);
        var repetido = new SplittableRandom(123);
        int[] contagens = new int[3];
        int quentes = 0;
        for (int i = 0; i < 100_000; i++) {
            int tabela = Gerador.tabela(r.nextInt(100), new int[] {40, 40, 20});
            long chave = Gerador.chave(r, 100_000, 10);
            contagens[tabela]++;
            if (chave <= 1000) quentes++;
            assertThat(Gerador.tabela(repetido.nextInt(100), new int[] {40, 40, 20}))
                    .isEqualTo(tabela);
            assertThat(Gerador.chave(repetido, 100_000, 10)).isEqualTo(chave);
        }
        assertThat(contagens[0]).isBetween(39_000, 41_000);
        assertThat(contagens[1]).isBetween(39_000, 41_000);
        assertThat(contagens[2]).isBetween(19_000, 21_000);
        assertThat(quentes).isBetween(9_000, 11_000);
    }
}
