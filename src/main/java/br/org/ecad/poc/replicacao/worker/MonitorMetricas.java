package br.org.ecad.poc.replicacao.worker;

import br.org.ecad.poc.replicacao.Config;
import br.org.ecad.poc.replicacao.core.ReplicacaoException;
import br.org.ecad.poc.replicacao.oracle.Conexoes;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.IntSupplier;

/** Queries the origin independently of the partition connections. */
final class MonitorMetricas implements Runnable {
    private static final System.Logger LOG = System.getLogger(MonitorMetricas.class.getName());
    private final Config config;
    private final Clock clock;
    private final Metricas metricas;
    private final IntSupplier particoes;
    private Metricas.Skew skew = new Metricas.Skew(0, 0, false);
    private long proximaMedida;

    MonitorMetricas(Config config, Clock clock, Metricas metricas, IntSupplier particoes) {
        this.config = config;
        this.clock = clock;
        this.metricas = metricas;
        this.particoes = particoes;
    }

    @Override
    public void run() {
        medirSkew();
        long proximaJanela = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                long espera = proximaJanela - System.nanoTime();
                if (espera > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(espera);
                proximaJanela += Duration.ofSeconds(5).toNanos();
                if (System.nanoTime() >= proximaMedida) medirSkew();
                amostrar();
            }
        } catch (InterruptedException e) {
            // Clear interruption before the final JDBC measurement, then restore it.
        } finally {
            boolean interrupted = Thread.interrupted();
            medirSkew();
            amostrar();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private Connection conectar() throws SQLException {
        return Conexoes.abrir(config.legadoUrl(), config.legadoUsuario(), config.legadoSenha());
    }

    private void medirSkew() {
        try (Connection c = conectar();
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

    private void amostrar() {
        try (Connection c = conectar();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*), MIN(criado_em) FROM evento_replicacao")) {
            if (!rs.next()) throw new SQLException("missing backlog");
            long pendentes = rs.getLong(1);
            java.sql.Timestamp antigo = rs.getTimestamp(2);
            long lag = antigo == null
                    ? 0
                    : Math.max(
                            0,
                            Duration.between(antigo.toInstant(), clock.instant())
                                    .toMillis());
            metricas.gravar(particoes.getAsInt(), pendentes, lag, skew);
        } catch (SQLException | ReplicacaoException e) {
            LOG.log(System.Logger.Level.WARNING, "backlog measurement failed", e);
        }
    }
}
