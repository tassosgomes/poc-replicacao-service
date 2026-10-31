package br.org.ecad.poc.replicacao.worker;

import br.org.ecad.poc.replicacao.core.ChangeEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Thread-safe window accumulator and CSV writer. */
public final class Metricas {
    public static final String CABECALHO =
            "ts,particoes,eventos_s,linhas_s,coalesce,p50_ms,p95_ms,p99_ms,max_ms,pendentes,lag_ms,anomalias,ms_poll,ms_busca,ms_upsert,ms_ack,skew_ms,skew_incerteza_ms,transacoes_longas,valida";
    private static final System.Logger LOG = System.getLogger(Metricas.class.getName());
    private final Path arquivo;
    private final Clock clock;
    private final List<Long> latencias = new ArrayList<>();
    private long eventos, linhas, anomalias, longas, lotes, poll, busca, upsert, ack;

    public Metricas(Path diretorio, String workerId, Clock clock) {
        if (!workerId.matches("[A-Za-z0-9._-]+") || workerId.equals(".") || workerId.equals("..")) {
            throw new IllegalArgumentException("invalid worker id");
        }
        this.clock = clock;
        arquivo = diretorio.resolve("worker-" + workerId + ".csv");
        try {
            Files.createDirectories(diretorio);
            if (!Files.exists(arquivo) || Files.size(arquivo) == 0) {
                Files.writeString(
                        arquivo,
                        CABECALHO + "\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void registrar(
            List<ChangeEvent> changes, Instant commit, int aplicadas, int faltantes, long[] tempos) {
        eventos += changes.size();
        linhas += aplicadas;
        anomalias += faltantes;
        lotes++;
        poll += tempos[0];
        busca += tempos[1];
        upsert += tempos[2];
        ack += tempos[3];
        for (ChangeEvent event : changes) {
            long ms = Duration.between(event.criadoEm(), commit).toMillis();
            if (ms > 5000) longas++;
            else latencias.add(ms);
        }
    }

    public synchronized String gravar(int particoes, long pendentes, long lag, Skew skew) {
        Collections.sort(latencias);
        String linha = String.format(
                Locale.ROOT,
                "%s,%d,%.2f,%.2f,%.2f,%d,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%d,%d,%d,%s",
                clock.instant(),
                particoes,
                eventos / 5.0,
                linhas / 5.0,
                linhas == 0 ? 0.0 : (double) eventos / linhas,
                percentil(latencias, 0.50),
                percentil(latencias, 0.95),
                percentil(latencias, 0.99),
                percentil(latencias, 1),
                pendentes,
                lag,
                anomalias,
                media(poll),
                media(busca),
                media(upsert),
                media(ack),
                skew.ms(),
                skew.incertezaMs(),
                longas,
                skew.valido() ? "VALIDA" : "INVALIDA");
        try {
            Files.writeString(arquivo, linha + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        LOG.log(System.Logger.Level.INFO, linha);
        eventos = linhas = anomalias = longas = lotes = poll = busca = upsert = ack = 0;
        latencias.clear();
        return linha;
    }

    private double media(long nanos) {
        return lotes == 0 ? 0 : nanos / 1_000_000.0 / lotes;
    }

    static long percentil(List<Long> valores, double p) {
        return valores.isEmpty() ? 0 : valores.get(Math.max(0, (int) Math.ceil(p * valores.size()) - 1));
    }

    public record Skew(long ms, long incertezaMs, boolean valido) {
        public static Skew calcular(Instant inicio, Instant remoto, Instant fim) {
            long rtt = Duration.between(inicio, fim).toMillis();
            long offset = Duration.between(remoto, inicio.plusMillis(rtt / 2)).toMillis();
            return new Skew(offset, (rtt + 1) / 2, Math.abs(offset) <= 100);
        }
    }
}
