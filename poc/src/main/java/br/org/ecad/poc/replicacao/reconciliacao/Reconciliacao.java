package br.org.ecad.poc.replicacao.reconciliacao;

import br.org.ecad.poc.replicacao.Config;
import br.org.ecad.poc.replicacao.core.Esquema;
import br.org.ecad.poc.replicacao.core.TableSpec;
import br.org.ecad.poc.replicacao.oracle.Conexoes;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/**
 * Compares the replica with the legacy and re-enqueues what diverges. The legacy is always the source
 * of truth (RN-10): a divergence becomes an {@code op = 'R'} event in the legacy outbox, which the
 * worker applies like any other event.
 *
 * <p>Output is one {@code key=value} line per fact, readable by a script:
 *
 * <pre>
 * tabela=OBRA_MUSICAL faixa=90000000001 codigo=9000000100123   (one per divergent row)
 * tabela=OBRA_MUSICAL faixa=90000000001 divergencias=1         (one per divergent range)
 * tabela=OBRA_MUSICAL total=1
 * total=1 tempo_ms=42 hash=ora_hash_32_probabilistico
 * </pre>
 *
 * <p>Limitation: the range hash is {@code ORA_HASH} (32 bits) summed per range, so it is
 * probabilistic; two different ranges can, rarely, sum to the same value. {@code delta} complements
 * it by comparing versions of recently changed rows without hashing. Rows that exist only in the
 * replica are counted but not enqueued: there is nothing in the legacy to reapply.
 */
public final class Reconciliacao {
    private static final long FAIXA = 100_000;
    private static final int IN_MAX = 1000;
    private final Connection legado;
    private final Connection novo;
    private final ConfigReconciliacao config;
    private final PrintStream saida;

    public Reconciliacao(Connection legado, Connection novo, ConfigReconciliacao config, PrintStream saida) {
        this.legado = legado;
        this.novo = novo;
        this.config = config;
        this.saida = saida;
    }

    public static void executar(Config config, ConfigReconciliacao opcoes, PrintStream saida) throws SQLException {
        try (Connection legado = Conexoes.abrir(config.legadoUrl(), config.legadoUsuario(), config.legadoSenha());
                Connection novo = Conexoes.abrir(config.novoUrl(), config.novoUsuario(), config.novoSenha())) {
            new Reconciliacao(legado, novo, opcoes, saida).executar();
        }
    }

    /** Runs the comparison and returns the number of divergences found. */
    public long executar() throws SQLException {
        long inicio = System.nanoTime();
        long total = 0;
        try {
            for (TableSpec spec : Esquema.TABELAS) total += tabela(spec);
            if (!config.dryRun()) legado.commit();
            saida.println("total=" + total + " tempo_ms=" + (System.nanoTime() - inicio) / 1_000_000
                    + " hash=ora_hash_32_probabilistico");
            return total;
        } catch (SQLException | RuntimeException e) {
            legado.rollback();
            throw e;
        }
    }

    private long tabela(TableSpec spec) throws SQLException {
        Map<Long, Long> contagens = new TreeMap<>();
        List<Long> reenfileirar = new ArrayList<>();
        if (config.modo().equals("delta")) delta(spec, contagens, reenfileirar);
        else completo(spec, contagens, reenfileirar);
        if (!config.dryRun()) reenfileirar(spec, reenfileirar);
        long total = 0;
        for (var entrada : contagens.entrySet()) {
            saida.println(
                    "tabela=" + spec.nome() + " faixa=" + entrada.getKey() + " divergencias=" + entrada.getValue());
            total += entrada.getValue();
        }
        saida.println("tabela=" + spec.nome() + " total=" + total);
        return total;
    }

    // --- completo ---

    private void completo(TableSpec spec, Map<Long, Long> contagens, List<Long> reenfileirar) throws SQLException {
        Map<Long, String> origem = hashes(legado, spec, false);
        Map<Long, String> replica = hashes(novo, spec, true);
        Set<Long> faixas = new TreeSet<>(origem.keySet());
        faixas.addAll(replica.keySet());
        for (long faixa : faixas)
            if (!Objects.equals(origem.get(faixa), replica.get(faixa)))
                linhaALinha(spec, faixa, contagens, reenfileirar);
    }

    /** Includes the key and unambiguous, length-prefixed column representations. */
    public static String hashLinha(TableSpec spec, boolean replica) {
        List<String> campos = new ArrayList<>();
        campos.add("TO_CHAR(codigo, 'TM9', 'NLS_NUMERIC_CHARACTERS=''.,''')");
        for (String c : spec.colunas()) campos.add("CAST(" + c + " AS VARCHAR2(4000))");
        campos.add("TO_CHAR(" + (replica ? "origem_updated_at" : "updated_at") + ", 'YYYYMMDDHH24MISSFF6')");
        List<String> partes = new ArrayList<>();
        for (String campo : campos)
            partes.add("NVL(TO_CHAR(LENGTH(" + campo + ")), '-1') || ':' || " + campo + " || ';'");
        return "ORA_HASH(" + String.join(" || ", partes) + ")";
    }

    private Map<Long, String> hashes(Connection c, TableSpec spec, boolean replica) throws SQLException {
        String sql = "SELECT FLOOR(codigo / " + FAIXA + "), COUNT(*), SUM(" + hashLinha(spec, replica) + ") FROM "
                + spec.tabelaSql() + " WHERE codigo >= ? AND codigo < ? GROUP BY FLOOR(codigo / " + FAIXA + ")";
        Map<Long, String> resultado = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, config.codigoMin());
            ps.setLong(2, config.codigoMax());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) resultado.put(rs.getLong(1), rs.getString(2) + ":" + rs.getString(3));
            }
        }
        return resultado;
    }

    /** Row by row inside one divergent range; the range is clipped to the configured key bounds. */
    private void linhaALinha(TableSpec spec, long faixa, Map<Long, Long> contagens, List<Long> reenfileirar)
            throws SQLException {
        long de = Math.max(faixa * FAIXA, config.codigoMin());
        long ate = Math.min((faixa + 1) * FAIXA, config.codigoMax());
        String colunas = String.join(", ", spec.colunas());
        String origemSql = "SELECT codigo, updated_at, " + colunas + " FROM " + spec.tabelaSql()
                + " WHERE codigo >= ? AND codigo < ? ORDER BY codigo";
        String replicaSql = "SELECT codigo, origem_updated_at, " + colunas + " FROM " + spec.tabelaSql()
                + " WHERE codigo >= ? AND codigo < ? ORDER BY codigo";
        int colunasLidas = spec.colunas().size() + 2;
        try (PreparedStatement a = legado.prepareStatement(origemSql);
                PreparedStatement b = novo.prepareStatement(replicaSql)) {
            for (PreparedStatement ps : List.of(a, b)) {
                ps.setLong(1, de);
                ps.setLong(2, ate);
            }
            try (ResultSet x = a.executeQuery();
                    ResultSet y = b.executeQuery()) {
                boolean temY = y.next();
                while (x.next()) {
                    long codigo = x.getLong(1);
                    while (temY && y.getLong(1) < codigo) {
                        registrar(spec, y.getLong(1), contagens); // only in the replica: counted, not enqueued
                        temY = y.next();
                    }
                    boolean igualChave = temY && y.getLong(1) == codigo;
                    if (!igualChave) {
                        registrar(spec, codigo, contagens);
                        reenfileirar.add(codigo);
                    } else {
                        if (!iguais(x, y, colunasLidas)) {
                            registrar(spec, codigo, contagens);
                            // A replica newer than the legacy cannot be fixed by the guarded MERGE.
                            if (!versaoMaisNova(y, x)) reenfileirar.add(codigo);
                        }
                        temY = y.next();
                    }
                }
                while (temY) {
                    registrar(spec, y.getLong(1), contagens);
                    temY = y.next();
                }
            }
        }
    }

    // --- delta ---

    /** Legacy rows changed in the window whose version is absent or older in the replica. */
    private void delta(TableSpec spec, Map<Long, Long> contagens, List<Long> reenfileirar) throws SQLException {
        Map<Long, Timestamp> recentes = new TreeMap<>();
        String sql = "SELECT codigo, updated_at FROM " + spec.tabelaSql()
                + " WHERE updated_at > SYSTIMESTAMP - NUMTODSINTERVAL(?, 'MINUTE') AND codigo >= ? AND codigo < ?";
        try (PreparedStatement ps = legado.prepareStatement(sql)) {
            ps.setLong(1, (long) config.desdeMin() + config.janelaMin());
            ps.setLong(2, config.codigoMin());
            ps.setLong(3, config.codigoMax());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) recentes.put(rs.getLong(1), rs.getTimestamp(2));
            }
        }
        List<Long> codigos = new ArrayList<>(recentes.keySet());
        for (int i = 0; i < codigos.size(); i += IN_MAX) {
            List<Long> bloco = codigos.subList(i, Math.min(i + IN_MAX, codigos.size()));
            Map<Long, Timestamp> naReplica = versoesNaReplica(spec, bloco);
            for (long codigo : bloco) {
                Timestamp origem = recentes.get(codigo);
                Timestamp copia = naReplica.get(codigo);
                if (!naReplica.containsKey(codigo) || copia == null || (origem != null && origem.after(copia))) {
                    registrar(spec, codigo, contagens);
                    reenfileirar.add(codigo);
                }
            }
        }
    }

    private Map<Long, Timestamp> versoesNaReplica(TableSpec spec, List<Long> codigos) throws SQLException {
        String marcas = String.join(", ", Collections.nCopies(codigos.size(), "?"));
        Map<Long, Timestamp> versoes = new HashMap<>();
        try (PreparedStatement ps = novo.prepareStatement(
                "SELECT codigo, origem_updated_at FROM " + spec.tabelaSql() + " WHERE codigo IN (" + marcas + ")")) {
            for (int i = 0; i < codigos.size(); i++) ps.setLong(i + 1, codigos.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) versoes.put(rs.getLong(1), rs.getTimestamp(2));
            }
        }
        return versoes;
    }

    // --- shared ---

    private void reenfileirar(TableSpec spec, List<Long> codigos) throws SQLException {
        if (codigos.isEmpty()) return;
        try (PreparedStatement evento = legado.prepareStatement(
                "INSERT INTO evento_replicacao(tabela,codigo,op,particao) VALUES (?,?,'R',ORA_HASH(?,15))")) {
            for (long codigo : codigos) {
                evento.setString(1, spec.nome());
                evento.setLong(2, codigo);
                evento.setLong(3, codigo);
                evento.addBatch();
            }
            evento.executeBatch();
        }
    }

    /** Whether the replica version (column 2 of {@code replica}) is newer than the legacy one. */
    private static boolean versaoMaisNova(ResultSet replica, ResultSet origem) throws SQLException {
        Timestamp copia = replica.getTimestamp(2), fonte = origem.getTimestamp(2);
        return copia != null && (fonte == null || copia.after(fonte));
    }

    private static boolean iguais(ResultSet a, ResultSet b, int n) throws SQLException {
        for (int i = 2; i <= n; i++) {
            Object p = a.getObject(i), q = b.getObject(i);
            if (p instanceof BigDecimal m && q instanceof BigDecimal o) {
                if (m.compareTo(o) != 0) return false;
            } else if (!Objects.equals(p, q)) return false;
        }
        return true;
    }

    private void registrar(TableSpec spec, long codigo, Map<Long, Long> contagens) {
        long faixa = Math.floorDiv(codigo, FAIXA);
        contagens.merge(faixa, 1L, Long::sum);
        saida.println("tabela=" + spec.nome() + " faixa=" + faixa + " codigo=" + codigo);
    }
}
