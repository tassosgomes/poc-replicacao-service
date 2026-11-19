package br.org.ecad.poc.replicacao.bootstrap.execucao;

import br.org.ecad.poc.replicacao.application.port.ExecucaoParticoes;
import br.org.ecad.poc.replicacao.domain.coordenacao.Particao;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns the threads requested by the coordinator. Only its coordination thread mutates the map. */
final class ExecutorParticoes implements ExecucaoParticoes {
    private final Map<Particao, ExecutorParticao> processors = new LinkedHashMap<>();
    private final ExecutorParticao.Recursos recursos;
    private final Falha falha;

    ExecutorParticoes(ExecutorParticao.Recursos recursos, Falha falha) {
        this.recursos = recursos;
        this.falha = falha;
    }

    record Falha(TableSpec tabela, int particao, Runnable executar) {}

    public List<Particao> emExecucao() {
        return List.copyOf(processors.keySet());
    }

    public void possuidas(int quantidade) {
        recursos.possuidas().set(quantidade);
    }

    public void renovarPrazo(Particao p, long deadline) {
        ExecutorParticao processor = processors.get(p);
        if (processor != null) processor.deadline = deadline;
    }

    public void iniciar(Particao p, long deadline) {
        if (processors.containsKey(p)) return;
        Runnable hook =
                p.tabela().equals(falha.tabela()) && p.numero() == falha.particao() ? falha.executar() : () -> {};
        ExecutorParticao processor = new ExecutorParticao(recursos, p, hook, deadline);
        processors.put(p, processor);
        processor.thread.start();
    }

    public void parar(Particao p) {
        ExecutorParticao processor = processors.remove(p);
        if (processor == null) return;
        processor.stopped = true;
        processor.thread.interrupt();
        boolean interrupted = false;
        while (processor.thread.isAlive()) {
            try {
                processor.thread.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public void encerrar() {
        // Signal every processor before joining so connection closure can proceed concurrently.
        for (ExecutorParticao processor : processors.values()) {
            processor.stopped = true;
            processor.thread.interrupt();
        }
        for (Particao p : emExecucao()) parar(p);
    }
}
