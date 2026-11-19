package br.org.ecad.poc.replicacao.application.port;

import br.org.ecad.poc.replicacao.domain.replicacao.ChangeEvent;
import java.time.Instant;
import java.util.List;

/** Receives measurements of a successfully acknowledged batch. */
@FunctionalInterface
public interface MetricasLote {
    void registrar(List<ChangeEvent> changes, Instant commit, int aplicadas, int faltantes, long[] tempos);
}
