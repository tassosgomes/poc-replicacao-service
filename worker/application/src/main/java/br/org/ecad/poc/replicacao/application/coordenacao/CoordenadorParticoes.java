package br.org.ecad.poc.replicacao.application.coordenacao;

import br.org.ecad.poc.replicacao.application.port.ExecucaoParticoes;
import br.org.ecad.poc.replicacao.application.port.LeaseRepository;
import br.org.ecad.poc.replicacao.domain.coordenacao.Particao;
import br.org.ecad.poc.replicacao.domain.replicacao.Esquema;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Heartbeat, lease renewal and fair allocation; runtime and persistence are ports. */
public final class CoordenadorParticoes implements Runnable {
    private static final System.Logger LOG = System.getLogger(CoordenadorParticoes.class.getName());
    private final LeaseRepository leases;
    private final ExecucaoParticoes execucao;
    private final int leaseSeg;
    private volatile boolean parar;

    public CoordenadorParticoes(LeaseRepository leases, ExecucaoParticoes execucao, int leaseSeg) {
        this.leases = leases;
        this.execucao = execucao;
        this.leaseSeg = leaseSeg;
    }

    public void solicitarParada() {
        parar = true;
    }

    @Override
    public void run() {
        try {
            while (!parar) {
                long start = System.nanoTime();
                try {
                    coordenar(start);
                } catch (RuntimeException e) {
                    if (!parar) LOG.log(System.Logger.Level.WARNING, "lease coordination failed", e);
                }
                if (!parar) {
                    // Preserve one LEASE_SEG/3 cycle. Failover waits for expiration before claiming.
                    long ciclo = TimeUnit.MILLISECONDS.toNanos(Math.max(1, leaseSeg * 1000L / 3));
                    long espera = ciclo - (System.nanoTime() - start);
                    if (espera > 0) TimeUnit.NANOSECONDS.sleep(espera);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            Thread.interrupted();
            execucao.encerrar();
            try (LeaseRepository.Sessao sessao = leases.abrir()) {
                sessao.liberarTodos();
                sessao.sair();
                sessao.confirmar();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "cannot release leases during shutdown", e);
            }
        }
    }

    /** One allocation cycle; each confirmation preserves the original transaction boundary. */
    public void coordenar(long start) {
        try (LeaseRepository.Sessao sessao = leases.abrir()) {
            int ativos = sessao.heartbeat();
            List<Particao> owned = sessao.renovar();
            sessao.confirmar();
            for (Particao p : execucao.emExecucao()) {
                if (!owned.contains(p)) execucao.parar(p);
            }
            long deadline = start + TimeUnit.SECONDS.toNanos(leaseSeg - (long) LeaseRepository.MARGEM_SEG);
            for (Particao p : owned) execucao.renovarPrazo(p, deadline);
            int total = Esquema.TABELAS.size() * Esquema.PARTICOES;
            int quota = (total + ativos - 1) / ativos;
            int liberadas = 0;
            for (Particao p : List.copyOf(owned)) {
                if (owned.size() <= quota) break;
                execucao.parar(p);
                sessao.liberar(p);
                sessao.confirmar();
                owned.remove(p);
                liberadas++;
            }
            List<Particao> claimed = sessao.adquirir(quota - owned.size());
            sessao.confirmar();
            owned.addAll(claimed);
            execucao.possuidas(owned.size());
            if (liberadas > 0 || !claimed.isEmpty()) {
                LOG.log(
                        System.Logger.Level.INFO,
                        "leases: workers=" + ativos + " quota=" + quota + " released=" + liberadas + " claimed="
                                + claimed.size() + " owned=" + owned.size());
            }
            for (Particao p : owned) {
                if (System.nanoTime() < deadline) execucao.iniciar(p, deadline);
            }
        }
    }
}
