package br.org.ecad.poc.replicacao.gerador;

import br.org.ecad.poc.replicacao.Config;
import br.org.ecad.poc.replicacao.oracle.Conexoes;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public final class Gerador {
    private static final System.Logger LOG = System.getLogger(Gerador.class.getName());
    private static final String[] TABELAS = {"FONOGRAMA", "OBRA_MUSICAL", "TITULAR"};
    private final Config banco;
    private final ConfigGerador config;
    private final Clock clock;
    private final AtomicLong linhas = new AtomicLong();
    private final AtomicLong transacoes = new AtomicLong();
    private final AtomicLong nanosTransacoes = new AtomicLong();
    private BufferedWriter arquivo;
    private volatile long inicio;
    private volatile long fimCarga;
    private final AtomicLong ultimoCommit = new AtomicLong();
    private final AtomicReference<Throwable> falha = new AtomicReference<>();
    private volatile Path logLinhas;
    private double taxaFinal;

    public Gerador(Config banco, ConfigGerador config, Clock clock) {
        this.banco = banco;
        this.config = config;
        this.clock = clock;
    }

    public void executar() throws Exception {
        Path diretorio = Path.of(banco.resultadosDir());
        Files.createDirectories(diretorio);
        Path log = novoLog(diretorio, clock);
        logLinhas = log;
        LOG.log(System.Logger.Level.INFO, "log_linhas=" + log.getFileName());
        try (BufferedWriter writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            arquivo = writer;
            inicio = System.nanoTime();
            boolean carga = config.rajada() == 0 && config.aberta() == 0;
            if (config.rajada() > 0) rajada();
            else if (config.aberta() > 0) aberta();
            else carga();
            long ate = carga ? Math.max(ultimoCommit.get(), fimCarga) : System.nanoTime();
            double segundos = Math.max(1e-9, (ate - inicio) / 1_000_000_000.0);
            double taxa = linhas.get() / segundos;
            taxaFinal = taxa;
            double media = transacoes.get() == 0 ? 0 : nanosTransacoes.get() / 1_000_000.0 / transacoes.get();
            String validade = validade(taxa, config.taxa());
            LOG.log(
                    System.Logger.Level.INFO,
                    String.format(
                            Locale.ROOT,
                            "linhas=%d transacoes=%d taxa=%.2f alvo=%d transacao_media_ms=%.3f %s",
                            linhas.get(),
                            transacoes.get(),
                            taxa,
                            config.taxa(),
                            media,
                            validade));
        }
    }

    /** DP-02: the run is valid only when the mean rate reaches 98% of the target, in every mode. */
    public static String validade(double taxa, int alvo) {
        return taxa >= alvo * 0.98 ? "VALIDO" : "INVALIDO";
    }

    /**
     * Creates this run's own row log. The name carries the start instant, the process and a
     * nanosecond stamp, and the file is created exclusively, so generators running at once (C4/C5)
     * on the same {@code RESULTADOS_DIR} never share or truncate each other's log.
     */
    public static Path novoLog(Path diretorio, Clock clock) throws IOException {
        String instante = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
                .withZone(ZoneOffset.UTC)
                .format(clock.instant());
        while (true) {
            Path log = diretorio.resolve("gerador-linhas-" + instante + "-"
                    + ProcessHandle.current().pid() + "-" + Long.toHexString(System.nanoTime()) + ".csv");
            try {
                Files.writeString(
                        log, "instante,tabela,codigo\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                return log;
            } catch (FileAlreadyExistsException e) {
                // another run claimed this exact name; try the next stamp
            }
        }
    }

    public Path logLinhas() {
        return logLinhas;
    }

    public double taxaFinal() {
        return taxaFinal;
    }

    private Connection conexao() {
        return Conexoes.abrir(banco.legadoUrl(), banco.legadoUsuario(), banco.legadoSenha());
    }

    private void carga() throws Exception {
        CountDownLatch prontos = new CountDownLatch(config.threads());
        CountDownLatch largada = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(config.threads())) {
            List<Future<?>> tarefas = new ArrayList<>();
            for (int i = 0; i < config.threads(); i++) {
                final int id = i;
                tarefas.add(executor.submit(() -> {
                    executarThread(id, prontos, largada);
                    return null;
                }));
            }
            prontos.await();
            inicio = System.nanoTime();
            long fim = inicio + TimeUnit.SECONDS.toNanos(config.duracao());
            fimCarga = falha.get() == null ? fim : inicio;
            largada.countDown();
            long proxima = inicio + TimeUnit.SECONDS.toNanos(5);
            long anterior = 0;
            while (System.nanoTime() < fimCarga && falha.get() == null) {
                long espera = Math.min(proxima, fim) - System.nanoTime();
                if (espera > 0) TimeUnit.NANOSECONDS.sleep(espera);
                if (System.nanoTime() >= proxima) {
                    long atual = linhas.get();
                    LOG.log(
                            System.Logger.Level.INFO,
                            String.format(
                                    Locale.ROOT,
                                    "janela_linhas=%d taxa=%.2f",
                                    atual - anterior,
                                    (atual - anterior) / 5.0));
                    anterior = atual;
                    proxima += TimeUnit.SECONDS.toNanos(5);
                }
            }
            for (Future<?> tarefa : tarefas) {
                try {
                    tarefa.get();
                } catch (ExecutionException e) {
                    throw e.getCause() instanceof Exception causa ? causa : e;
                }
            }
        }
    }

    private void executarThread(int id, CountDownLatch prontos, CountDownLatch largada) throws Exception {
        try {
            trabalhar(id, prontos, largada);
        } catch (Throwable e) {
            falha.compareAndSet(null, e);
            throw e;
        }
    }

    private void trabalhar(int id, CountDownLatch prontos, CountDownLatch largada) throws Exception {
        SplittableRandom random = new SplittableRandom(config.semente() * 1_000_003L + id);
        Connection c = null;
        long[] maximos = new long[3];
        try {
            c = conexao();
            for (int i = 0; i < 3; i++) {
                try (Statement s = c.createStatement();
                        ResultSet rs = s.executeQuery("SELECT MAX(codigo) FROM " + TABELAS[i])) {
                    rs.next();
                    maximos[i] = rs.getLong(1);
                    if (maximos[i] < 1) throw new IllegalStateException("tabela vazia: " + TABELAS[i]);
                }
            }
        } catch (Throwable e) {
            if (c != null) {
                try {
                    c.close();
                } catch (SQLException fechamento) {
                    e.addSuppressed(fechamento);
                }
            }
            throw e;
        } finally {
            prontos.countDown();
        }
        try (Connection conn = c) {
            largada.await();
            long fim = fimCarga;
            Agendador agendador = new Agendador(inicio, (double) config.taxa() / config.threads());
            while (System.nanoTime() < fim && falha.get() == null) {
                int tamanho = random.nextInt(1, config.txMax() + 1);
                long agendado = agendador.proximo(tamanho);
                if (agendado >= fim) break;
                long espera = agendado - System.nanoTime();
                if (espera > 0) LockSupport.parkNanos(espera);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                long comeco = System.nanoTime();
                List<String> alteradas = new ArrayList<>();
                for (int j = 0; j < tamanho; j++) {
                    int tabela = tabela(random.nextInt(100), config.mix());
                    long codigo = chave(random, maximos[tabela], config.hot());
                    int tipo = random.nextInt(100);
                    if (tipo < config.insert()) codigo = inserir(conn, tabela);
                    else if (!atualizar(conn, tabela, codigo, tipo < config.insert() + config.delete())) continue;
                    alteradas.add(TABELAS[tabela] + "," + codigo);
                }
                conn.commit();
                registrar(alteradas, System.nanoTime() - comeco);
            }
        }
    }

    public static int tabela(int valor, int[] mix) {
        return valor < mix[0] ? 0 : valor < mix[0] + mix[1] ? 1 : 2;
    }

    public static long chave(SplittableRandom random, long maximo, int hot) {
        long limite = Math.max(1, maximo / 100);
        if (random.nextInt(100) < hot || limite == maximo) return random.nextLong(1, limite + 1);
        return random.nextLong(limite + 1, maximo + 1);
    }

    private static boolean atualizar(Connection c, int tabela, long codigo, boolean delete) throws SQLException {
        String sql = "UPDATE " + TABELAS[tabela] + " SET excluido=" + (delete ? "1" : "0") + " WHERE codigo=?";
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, codigo);
            return s.executeUpdate() == 1;
        }
    }

    private static long inserir(Connection c, int tabela) throws SQLException {
        String seq = "SEQ_" + TABELAS[tabela];
        long codigo;
        try (Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT " + seq + ".NEXTVAL FROM dual")) {
            r.next();
            codigo = r.getLong(1);
        }
        String sql = switch (tabela) {
            case 0 -> "INSERT INTO FONOGRAMA(codigo,obra_codigo,titulo,excluido) VALUES (?,1,'gerador',0)";
            case 1 -> "INSERT INTO OBRA_MUSICAL(codigo,titulo,excluido) VALUES (?,'gerador',0)";
            default -> "INSERT INTO TITULAR(codigo,nome,excluido) VALUES (?,'gerador',0)";
        };
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, codigo);
            s.executeUpdate();
        }
        return codigo;
    }

    private void rajada() throws Exception {
        try (Connection c = conexao()) {
            List<Long> chaves = new ArrayList<>();
            try (PreparedStatement s =
                    c.prepareStatement("SELECT codigo FROM TITULAR ORDER BY codigo FETCH FIRST ? ROWS ONLY")) {
                s.setInt(1, config.rajada());
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) chaves.add(rs.getLong(1));
                }
            }
            if (chaves.size() < config.rajada())
                throw new IllegalStateException("TITULAR tem apenas " + chaves.size() + " linhas");
            long comeco = System.nanoTime();
            int n;
            try (PreparedStatement s = c.prepareStatement("UPDATE TITULAR SET excluido=0 WHERE codigo <= ?")) {
                s.setLong(1, chaves.get(chaves.size() - 1));
                n = s.executeUpdate();
            }
            c.commit();
            if (n != chaves.size()) throw new IllegalStateException("rajada alterou " + n + " linhas");
            List<String> alteradas = new ArrayList<>(n);
            for (long codigo : chaves) alteradas.add("TITULAR," + codigo);
            registrar(alteradas, System.nanoTime() - comeco);
        }
    }

    private void aberta() throws Exception {
        try (Connection c = conexao()) {
            long comeco = System.nanoTime();
            if (!atualizar(c, 2, 1, false)) throw new IllegalStateException("titular 1 ausente");
            TimeUnit.SECONDS.sleep(config.aberta());
            c.commit();
            registrar(List.of("TITULAR,1"), System.nanoTime() - comeco);
        }
    }

    private synchronized void registrar(List<String> alteradas, long nanos) throws IOException {
        String instante = clock.instant().toString();
        for (String linha : alteradas) arquivo.write(instante + "," + linha + "\n");
        arquivo.flush();
        linhas.addAndGet(alteradas.size());
        transacoes.incrementAndGet();
        nanosTransacoes.addAndGet(nanos);
        ultimoCommit.accumulateAndGet(System.nanoTime(), Math::max);
    }

    /**
     * Rows changed in {@code [de, ate)} according to the generator log (distinct table and key):
     * the input of the C8 scenario, which needs the rows touched while a trigger was disabled.
     */
    public static long linhasNaJanela(Path log, Instant de, Instant ate) throws IOException {
        try (var linhas = Files.lines(log, StandardCharsets.UTF_8)) {
            return linhas.skip(1)
                    .map(l -> l.split(",", 3))
                    .filter(p -> {
                        Instant t = Instant.parse(p[0]);
                        return !t.isBefore(de) && t.isBefore(ate);
                    })
                    .map(p -> p[1] + "," + p[2])
                    .distinct()
                    .count();
        }
    }
}
