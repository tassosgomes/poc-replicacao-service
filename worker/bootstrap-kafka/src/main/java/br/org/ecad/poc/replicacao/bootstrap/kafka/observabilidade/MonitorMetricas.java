package br.org.ecad.poc.replicacao.bootstrap.kafka.observabilidade;

import br.org.ecad.poc.replicacao.bootstrap.kafka.config.Config;
import br.org.ecad.poc.replicacao.bootstrap.observabilidade.Metricas;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import br.org.ecad.poc.replicacao.infra.kafka.replicacao.LagConsumidor;
import br.org.ecad.poc.replicacao.infra.kafka.replicacao.PropriedadesConsumidor;
import br.org.ecad.poc.replicacao.infra.oracle.conexao.Conexoes;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Metrics window writer of the Kafka worker. Same 5 s windows on the wall-clock boundaries as the lab
 * Java, so rows of several workers pair by {@code ts}. Differences, by design:
 *
 * <ul>
 *   <li>{@code pendentes} is the consumer lag (end offset minus committed offset, summed over every
 *       table and partition), measured with its own consumer; {@code lag_ms} is 0 (the age of the oldest
 *       pending record would need a fetch per partition).
 *   <li>There is no legacy connection: the clock skew is measured against the destination database. It
 *       validates the worker clock against the destination, not against the origin whose {@code ts_ms}
 *       stamps the events, so latency percentiles still need the origin skew measured outside
 *       (scripts of the lab).
 * </ul>
 */
public final class MonitorMetricas implements Runnable {
    private static final System.Logger LOG = System.getLogger(MonitorMetricas.class.getName());
    static final long JANELA_MS = 5_000;
    private final Config config;
    private final Map<String, Object> propriedades;
    private final Clock clock;
    private final Metricas metricas;
    private final IntSupplier particoes;
    private Metricas.Skew skew = new Metricas.Skew(0, 0, false);
    private long proximaMedida;
    private LagConsumidor lag;

    public MonitorMetricas(
            Config config, Map<String, Object> propriedades, Clock clock, Metricas metricas, IntSupplier particoes) {
        this.config = config;
        this.propriedades = propriedades;
        this.clock = clock;
        this.metricas = metricas;
        this.particoes = particoes;
    }

    @Override
    public void run() {
        medirSkew();
        long fronteira = proximaFronteira(clock.millis());
        try {
            while (!Thread.currentThread().isInterrupted()) {
                for (long espera = fronteira - clock.millis(); espera > 0; espera = fronteira - clock.millis()) {
                    TimeUnit.MILLISECONDS.sleep(Math.min(JANELA_MS, espera));
                }
                int ativas = particoes.getAsInt();
                Instant ts = Instant.ofEpochMilli(fronteira);
                fronteira = Math.max(fronteira + JANELA_MS, proximaFronteira(clock.millis()));
                if (System.nanoTime() >= proximaMedida) medirSkew();
                amostrar(ts, ativas);
            }
        } catch (InterruptedException e) {
            // Clear interruption before the final measurement, then restore it.
        } finally {
            boolean interrupted = Thread.interrupted();
            int ativas = particoes.getAsInt();
            Instant ts = clock.instant();
            medirSkew();
            amostrar(ts, ativas);
            fecharLag();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static long proximaFronteira(long agoraMs) {
        return Math.floorDiv(agoraMs, JANELA_MS) * JANELA_MS + JANELA_MS;
    }

    private void medirSkew() {
        try (Connection c = Conexoes.abrir(config.novoUrl(), config.novoUsuario(), config.novoSenha());
                Statement s = c.createStatement()) {
            Instant inicio = clock.instant();
            try (ResultSet rs = s.executeQuery("SELECT SYSTIMESTAMP FROM dual")) {
                if (!rs.next()) throw new SQLException("missing timestamp");
                Instant remoto = rs.getTimestamp(1).toInstant();
                Instant fim = clock.instant();
                skew = Metricas.Skew.calcular(inicio, remoto, fim);
            }
        } catch (SQLException | ReplicacaoException e) {
            skew = new Metricas.Skew(0, 0, false);
            LOG.log(System.Logger.Level.WARNING, "clock measurement failed", e);
        }
        proximaMedida = System.nanoTime() + Duration.ofMinutes(1).toNanos();
    }

    private void amostrar(Instant ts, int ativas) {
        try {
            if (lag == null) {
                lag = new LagConsumidor(
                        PropriedadesConsumidor.paraLag(propriedades, config.workerId()), config.topicos());
            }
            metricas.gravar(ts, ativas, lag.total(), 0, skew);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "lag measurement failed", e);
            fecharLag();
        }
    }

    private void fecharLag() {
        if (lag != null) {
            try {
                lag.close();
            } catch (RuntimeException ignorada) {
                // best effort; a new consumer is created on the next window
            }
            lag = null;
        }
    }
}
