package br.org.ecad.poc.replicacao.oracle;

import br.org.ecad.poc.replicacao.core.Estados;
import br.org.ecad.poc.replicacao.core.Linha;
import br.org.ecad.poc.replicacao.core.ReplicacaoException;
import br.org.ecad.poc.replicacao.core.StateProvider;
import br.org.ecad.poc.replicacao.core.TableSpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Current state by {@code WHERE codigo IN (...)} in blocks of up to 1.000 (the Oracle limit of
 * an {@code IN} list). The statement is cached per (table, block size) so repeated batches reuse
 * the cursor.
 */
public final class OracleStateProvider implements StateProvider, AutoCloseable {

    static final int BLOCO = 1000;

    private final Connection legado;
    private final Map<String, PreparedStatement> cursores = new HashMap<>();

    public OracleStateProvider(Connection legado) {
        this.legado = legado;
    }

    @Override
    public Estados buscar(TableSpec spec, Collection<Long> codigos) {
        Map<Long, Linha> encontradas = new LinkedHashMap<>();
        List<Long> todos = new ArrayList<>(new LinkedHashSet<>(codigos));
        try {
            for (int inicio = 0; inicio < todos.size(); inicio += BLOCO) {
                List<Long> bloco = todos.subList(inicio, Math.min(inicio + BLOCO, todos.size()));
                lerBloco(spec, bloco, encontradas);
            }
            legado.rollback();
        } catch (SQLException e) {
            throw new ReplicacaoException("cannot read the state of " + spec.nome(), e);
        }
        Set<Long> ausentes = new LinkedHashSet<>(todos);
        ausentes.removeAll(encontradas.keySet());
        return new Estados(encontradas, ausentes);
    }

    private void lerBloco(TableSpec spec, List<Long> bloco, Map<Long, Linha> destino) throws SQLException {
        PreparedStatement ps = cursores.get(spec.nome() + ":" + bloco.size());
        if (ps == null) {
            ps = legado.prepareStatement(spec.selectEstado(bloco.size()));
            cursores.put(spec.nome() + ":" + bloco.size(), ps);
        }
        for (int i = 0; i < bloco.size(); i++) {
            ps.setLong(i + 1, bloco.get(i));
        }
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long codigo = rs.getLong(1);
                LocalDateTime versao = rs.getObject(2, LocalDateTime.class);
                List<Object> valores = new ArrayList<>(spec.colunas().size());
                for (int c = 0; c < spec.colunas().size(); c++) {
                    valores.add(rs.getObject(3 + c));
                }
                destino.put(codigo, new Linha(codigo, versao.toInstant(ZoneOffset.UTC), valores));
            }
        }
    }

    @Override
    public void close() throws SQLException {
        for (PreparedStatement ps : cursores.values()) {
            ps.close();
        }
        cursores.clear();
    }
}
