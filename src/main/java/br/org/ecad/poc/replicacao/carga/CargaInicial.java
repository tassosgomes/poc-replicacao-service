package br.org.ecad.poc.replicacao.carga;

import br.org.ecad.poc.replicacao.Config;
import br.org.ecad.poc.replicacao.core.Linha;
import br.org.ecad.poc.replicacao.core.ReprocessavelException;
import br.org.ecad.poc.replicacao.core.TableSpec;
import br.org.ecad.poc.replicacao.core.Uuid7;
import br.org.ecad.poc.replicacao.oracle.Conexoes;
import br.org.ecad.poc.replicacao.oracle.OracleReplicaSink;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Copies independent key ranges using the same version-guarded merge as the worker. */
public final class CargaInicial {
    private static final long FAIXA = 10_000;
    private static final int TENTATIVAS = 10;
    private final Config config;
    private final ConfigCarga opcoes;
    private final Clock clock;
    private final PrintStream saida;

    public CargaInicial(Config config, ConfigCarga opcoes, Clock clock, PrintStream saida) {
        this.config = config;
        this.opcoes = opcoes;
        this.clock = clock;
        this.saida = saida;
    }

    public void executar() throws Exception {
        long inicio = System.nanoTime();
        try (var executor = Executors.newFixedThreadPool(opcoes.threads())) {
            for (TableSpec tabela : opcoes.tabelas()) {
                long comeco = System.nanoTime();
                List<Callable<Long>> tarefas = new ArrayList<>();
                for (long faixa : faixas(tabela)) tarefas.add(() -> copiar(tabela, faixa));
                long linhas = 0;
                for (Future<Long> resultado : executor.invokeAll(tarefas)) linhas += resultado.get();
                double segundos = Math.max((System.nanoTime() - comeco) / 1e9, 0.000001);
                saida.printf(
                        Locale.ROOT, "tabela=%s linhas=%d linhas_s=%.2f%n", tabela.nome(), linhas, linhas / segundos);
            }
        }
        saida.printf(Locale.ROOT, "tempo_total_s=%.3f%n", (System.nanoTime() - inicio) / 1e9);
    }

    private List<Long> faixas(TableSpec tabela) throws SQLException {
        List<Long> faixas = new ArrayList<>();
        String sql = "SELECT DISTINCT FLOOR(codigo / " + FAIXA + ") FROM " + tabela.tabelaSql()
                + " WHERE codigo >= ? AND codigo < ? ORDER BY 1";
        try (Connection origem = legado();
                PreparedStatement ps = origem.prepareStatement(sql)) {
            ps.setLong(1, opcoes.codigoMin());
            ps.setLong(2, opcoes.codigoMax());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) faixas.add(rs.getLong(1));
            }
        }
        return faixas;
    }

    private long copiar(TableSpec tabela, long faixa) throws SQLException {
        long min = Math.max(opcoes.codigoMin(), faixa * FAIXA);
        long max = Math.min(
                opcoes.codigoMax(), faixa > (Long.MAX_VALUE - FAIXA) / FAIXA ? Long.MAX_VALUE : (faixa + 1) * FAIXA);
        for (int tentativa = 1; ; tentativa++) {
            try (Connection origem = legado();
                    Connection destino = novo();
                    OracleReplicaSink sink = new OracleReplicaSink(destino, new Uuid7(clock))) {
                List<Linha> linhas = ler(origem, tabela, min, max);
                if (!linhas.isEmpty()) {
                    sink.upsert(tabela, linhas);
                    sink.commit();
                }
                return linhas.size();
            } catch (ReprocessavelException e) {
                if (tentativa >= TENTATIVAS) throw e;
            }
        }
    }

    private List<Linha> ler(Connection origem, TableSpec tabela, long min, long max) throws SQLException {
        String sql = "SELECT codigo, updated_at, " + String.join(", ", tabela.colunas()) + " FROM " + tabela.tabelaSql()
                + " WHERE codigo >= ? AND codigo < ? ORDER BY codigo";
        List<Linha> linhas = new ArrayList<>();
        try (PreparedStatement ps = origem.prepareStatement(sql)) {
            ps.setLong(1, min);
            ps.setLong(2, max);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<Object> valores = new ArrayList<>();
                    for (int i = 0; i < tabela.colunas().size(); i++) valores.add(rs.getObject(i + 3));
                    linhas.add(new Linha(
                            rs.getLong(1), rs.getObject(2, LocalDateTime.class).toInstant(ZoneOffset.UTC), valores));
                }
            }
        }
        return linhas;
    }

    private Connection legado() {
        return Conexoes.abrir(config.legadoUrl(), config.legadoUsuario(), config.legadoSenha());
    }

    private Connection novo() {
        return Conexoes.abrir(config.novoUrl(), config.novoUsuario(), config.novoSenha());
    }
}
