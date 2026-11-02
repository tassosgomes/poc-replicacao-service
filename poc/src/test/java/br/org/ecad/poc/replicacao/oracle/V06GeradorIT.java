package br.org.ecad.poc.replicacao.oracle;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.ecad.poc.replicacao.gerador.Gerador;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** V-06 against the real legacy database; the generator runs in its 1 GB container (DP-06). */
class V06GeradorIT {
    private static final System.Logger LOG = System.getLogger(V06GeradorIT.class.getName());

    @TempDir
    Path dir;

    private final Clock clock = Clock.systemUTC();

    private GeradorRemoto.Resultado gerar(Map<String, String> parametros) throws Exception {
        GeradorRemoto.Resultado r = GeradorRemoto.executar(parametros, dir);
        r.saida()
                .lines()
                .filter(l -> l.contains("transacao_media_ms"))
                .forEach(l -> LOG.log(System.Logger.Level.INFO, l));
        assertThat(r.exitCode()).as(r.saida()).isZero();
        assertThat(r.memoriaContainer()).isEqualTo(GeradorRemoto.LIMITE_MEMORIA);
        return r;
    }

    private long eventos() throws Exception {
        try (Connection c = BancosPoc.legado();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("select count(*) from evento_replicacao")) {
            r.next();
            return r.getLong(1);
        }
    }

    @Test
    void taxaDoisMil() throws Exception {
        var r = gerar(Map.of("TAXA", "2000", "DURACAO_SEG", "60", "THREADS", "8"));
        assertThat(r.taxa()).as(r.saida()).isGreaterThanOrEqualTo(1960);
        assertThat(r.valido()).as(r.saida()).isTrue();
    }

    @Test
    void mixPorCentoEVinteSegundos() throws Exception {
        long[] antes = distribuicao();
        gerar(Map.of("TAXA", "1000", "DURACAO_SEG", "120", "MIX", "40,40,20"));
        long[] depois = distribuicao();
        long total = 0;
        for (int i = 0; i < 3; i++) {
            depois[i] -= antes[i];
            total += depois[i];
        }
        assertThat(total).isPositive();
        assertThat(100.0 * depois[0] / total).isBetween(37.0, 43.0);
        assertThat(100.0 * depois[1] / total).isBetween(37.0, 43.0);
        assertThat(100.0 * depois[2] / total).isBetween(17.0, 23.0);
    }

    private long[] distribuicao() throws Exception {
        long[] valores = new long[3];
        try (Connection c = BancosPoc.legado();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("select tabela, count(*) from evento_replicacao group by tabela")) {
            while (r.next()) {
                int i = switch (r.getString(1)) {
                    case "FONOGRAMA" -> 0;
                    case "OBRA_MUSICAL" -> 1;
                    default -> 2;
                };
                valores[i] = r.getLong(2);
            }
        }
        return valores;
    }

    @Test
    void rajadaCinquentaMilEventos() throws Exception {
        long antes = eventos();
        Instant de = clock.instant().minusSeconds(60);
        var r = gerar(Map.of("RAJADA_LINHAS", "50000"));
        assertThat(eventos() - antes).isEqualTo(50_000);
        assertThat(r.transacoes()).as(r.saida()).isEqualTo(1);
        assertThat(r.valido()).as(r.saida()).isEqualTo(r.taxa() >= 980);
        assertThat(r.invalido()).as(r.saida()).isEqualTo(r.taxa() < 980);
        Path log = r.log();
        try (var linhas = Files.lines(log)) {
            assertThat(linhas.count()).isEqualTo(50_001);
        }
        assertThat(Gerador.linhasNaJanela(log, de, clock.instant().plusSeconds(60)))
                .isEqualTo(50_000);
        try (Connection c = BancosPoc.legado();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select count(distinct ora_rowscn) from (select ora_rowscn from"
                        + " evento_replicacao order by id desc fetch first 50000 rows only)")) {
            rs.next();
            assertThat(rs.getLong(1)).as("um único COMMIT").isEqualTo(1);
        }
    }

    @Test
    void transacaoFicaAberta() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor()) {
            var futuro = executor.submit(() -> gerar(Map.of("TX_ABERTA_SEG", "30")));
            long primeira = -1;
            long ultima = -1;
            long limite = System.nanoTime() + 120_000_000_000L;
            try (Connection c = BancosPoc.legado();
                    Statement s = c.createStatement()) {
                while (System.nanoTime() < limite) {
                    boolean aberta;
                    try (ResultSet r = s.executeQuery(
                            "select count(*) from v$transaction t join v$session se"
                                    + " on se.taddr = t.addr where se.username = 'BDAD' and se.sid <> sys_context('USERENV','SID')")) {
                        r.next();
                        aberta = r.getLong(1) > 0;
                    }
                    long agora = System.nanoTime();
                    if (aberta) {
                        if (primeira < 0) primeira = agora;
                        ultima = agora;
                    } else if (primeira >= 0) {
                        break;
                    }
                    Thread.sleep(250);
                }
            }
            var r = futuro.get();
            assertThat(r.invalido())
                    .as("1 linha em 30 s fica abaixo de 98% do alvo: " + r.saida())
                    .isTrue();
            assertThat(primeira).as("transação observada em v$transaction").isPositive();
            double segundos = (ultima - primeira) / 1e9;
            assertThat(segundos).isBetween(28.0, 32.0);
        }
    }

    @Test
    void hotDezPorCento() throws Exception {
        long maximo;
        try (Connection c = BancosPoc.legado();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("select max(codigo) from titular")) {
            rs.next();
            maximo = rs.getLong(1);
        }
        long chavesQuentes = maximo / 100; // 1% of the keys; the table grows across tests
        var r = gerar(Map.of("TAXA", "500", "DURACAO_SEG", "20", "MIX", "0,0,100", "PCT_INSERT", "0", "HOT_PCT", "10"));
        try (var linhas = Files.lines(r.log())) {
            var codigos =
                    linhas.skip(1).map(l -> Long.parseLong(l.split(",")[2])).toList();
            long quentes = codigos.stream().filter(c -> c <= chavesQuentes).count();
            assertThat(100.0 * quentes / codigos.size()).isBetween(9.0, 11.0);
        }
    }

    @Test
    void execucoesSimultaneasNaoMisturamLogs() throws Exception {
        // C4/C5: two generators at once on the same results directory keep separate row logs.
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> gerar(Map.of("TAXA", "300", "DURACAO_SEG", "20", "SEMENTE", "1")));
            var b = executor.submit(() -> gerar(Map.of("TAXA", "300", "DURACAO_SEG", "20", "SEMENTE", "2")));
            var ra = a.get();
            var rb = b.get();
            assertThat(ra.log()).isNotEqualTo(rb.log());
            for (var r : List.of(ra, rb)) {
                try (var linhas = Files.lines(r.log())) {
                    assertThat(linhas.count() - 1).as(r.saida()).isEqualTo(r.linhas());
                }
            }
        }
    }

    @Test
    void taxaInalcancavel() throws Exception {
        var r = gerar(Map.of("TAXA", "20000", "DURACAO_SEG", "10"));
        assertThat(r.invalido()).as(r.saida()).isTrue();
        assertThat(r.taxa()).isLessThan(19600);
    }
}
