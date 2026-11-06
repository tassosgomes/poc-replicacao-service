package br.org.ecad.poc.replicacao.gerador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import br.org.ecad.poc.replicacao.bootstrap.config.Config;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V06ValidadeTest {
    @TempDir
    Path dir;

    @Test
    void invalidoSempreQueATaxaMediaFicaAbaixoDeNoventaEOitoPorCento() {
        assertThat(Gerador.validade(1960, 2000)).isEqualTo("VALIDO");
        assertThat(Gerador.validade(1959.99, 2000)).isEqualTo("INVALIDO");
        assertThat(Gerador.validade(2500, 2000)).isEqualTo("VALIDO");
        // rajada e transação aberta usam o mesmo critério: 1 linha em 30 s contra alvo 1000
        assertThat(Gerador.validade(1 / 30.0, 1000)).isEqualTo("INVALIDO");
        assertThat(Gerador.validade(50_000 / 10.0, 1000)).isEqualTo("VALIDO");
    }

    @Test
    void conexaoIndisponivelFalhaSemTravarEPropagaOErro() {
        Config banco = Config.load(
                Map.of("LEGADO_URL", "jdbc:oracle:thin:@//127.0.0.1:1/FREEPDB1", "RESULTADOS_DIR", dir.toString())::get,
                Clock.systemUTC());
        for (String modo : new String[] {"", "RAJADA_LINHAS", "TX_ABERTA_SEG"}) {
            Map<String, String> parametros =
                    modo.isEmpty() ? Map.of("TAXA", "100", "DURACAO_SEG", "5", "THREADS", "4") : Map.of(modo, "10");
            Gerador gerador = new Gerador(banco, ConfigGerador.load(parametros::get), Clock.systemUTC());
            assertTimeoutPreemptively(
                    Duration.ofSeconds(60),
                    () -> assertThatThrownBy(gerador::executar).hasMessageContaining("cannot connect"));
        }
    }

    @Test
    void execucoesSimultaneasNaoCompartilhamOLog() throws Exception {
        Set<Path> logs = new HashSet<>();
        try (var executor = Executors.newFixedThreadPool(16)) {
            var futuros = new java.util.ArrayList<Future<Path>>();
            for (int i = 0; i < 64; i++) futuros.add(executor.submit(() -> Gerador.novoLog(dir, Clock.systemUTC())));
            for (var f : futuros) assertThat(logs.add(f.get())).isTrue();
        }
        assertThat(logs).hasSize(64);
        try (var arquivos = Files.list(dir)) {
            assertThat(arquivos.count()).isEqualTo(64);
        }
    }
}
