package br.org.ecad.poc.replicacao.bootstrap.observabilidade;

import br.org.ecad.poc.replicacao.bootstrap.config.Config;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import br.org.ecad.poc.replicacao.infra.oracle.conexao.Conexoes;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.IntSupplier;

/** Queries the origin independently of the partition connections. */
public final class MonitorMetricas implements Runnable {
    private static final System.Logger LOG = System.getLogger(MonitorMetricas.class.getName());
    private final Config config;
    private final Clock clock;
    private final Metricas metricas;
    private final IntSupplier particoes;
    static final long JANELA_MS = 5_000;
    private Metricas.Skew skew = new Metricas.Skew(0, 0, false);
    private long proximaMedida;

    public MonitorMetricas(Config config, Clock clock, Metricas metricas, IntSupplier particoes) {
        this.config = config;
        this.clock = clock;
        this.metricas = metricas;
        this.particoes = particoes;
    }

    /**
     * Windows end on multiples of {@link #JANELA_MS} of the wall clock and {@code ts} is that boundary,
     * with {@code particoes} read at it (before any query), so the rows of workers on the same clock
     * describe the same instant and can be paired by {@code ts}. The row written at shutdown carries
     * the instant it was taken instead.
     */
    @Override
    public void run() {
        medirSkew();
        long fronteira = proximaFronteira(clock.millis());
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // Sleep is monotonic but the boundary is wall clock: re-check after waking, so a backward
                // step of the wall clock (NTP/hypervisor time sync) delays the sample instead of labelling
                // it with a boundary the clock has not reached yet.
                for (long espera = fronteira - clock.millis(); espera > 0; espera = fronteira - clock.millis()) {
                    java.util.concurrent.TimeUnit.MILLISECONDS.sleep(Math.min(JANELA_MS, espera));
                }
                int ativas = particoes.getAsInt();
                Instant ts = Instant.ofEpochMilli(fronteira);
                fronteira = Math.max(fronteira + JANELA_MS, proximaFronteira(clock.millis()));
                if (System.nanoTime() >= proximaMedida) medirSkew();
                amostrar(ts, ativas);
            }
        } catch (InterruptedException e) {
            // Clear interruption before the final JDBC measurement, then restore it.
        } finally {
            boolean interrupted = Thread.interrupted();
            int ativas = particoes.getAsInt();
            Instant ts = clock.instant();
            medirSkew();
            amostrar(ts, ativas);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    static long proximaFronteira(long agoraMs) {
        return Math.floorDiv(agoraMs, JANELA_MS) * JANELA_MS + JANELA_MS;
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

    private void amostrar(Instant ts, int ativas) {
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
            metricas.gravar(ts, ativas, pendentes, lag, skew);
        } catch (SQLException | ReplicacaoException e) {
            LOG.log(System.Logger.Level.WARNING, "backlog measurement failed", e);
        }
    }
}
