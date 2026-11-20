package br.org.ecad.poc.replicacao.bootstrap.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MainTest {

    @Test
    void dispatch_semSubcomando_saiComDois() {
        assertThat(Main.dispatch(new String[0], Map.of(), Clock.systemUTC()).exitCode())
                .isEqualTo(2);
    }

    @Test
    void dispatch_subcomandoDesconhecido_saiComDois() {
        assertThat(Main.dispatch(new String[] {"carga"}, Map.of(), Clock.systemUTC())
                        .exitCode())
                .isEqualTo(2);
    }

    @Test
    void dispatch_configInvalida_saiComUm() {
        assertThat(Main.dispatch(new String[] {"worker"}, Map.of("LOTE_MAX", "x"), Clock.systemUTC())
                        .exitCode())
                .isEqualTo(1);
    }

    @Test
    void dispatch_worker_devolveConfig() {
        Main.Result r = Main.dispatch(new String[] {"worker"}, Map.of("WORKER_ID", "w1"), Clock.systemUTC());

        assertThat(r.exitCode()).isZero();
        assertThat(r.config().workerId()).isEqualTo("w1");
    }
}
