package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

import br.org.ecad.poc.replicacao.domain.replicacao.Esquema;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;

/**
 * Table name to Debezium topic. The default follows the connector naming
 * {@code <topic.prefix>.<SCHEMA>.<TABLE>} (so the prefix given here is {@code topic.prefix + "." + SCHEMA});
 * any table can be overridden explicitly.
 */
public final class MapaTopicos {

    private final Map<String, String> topicos;

    private MapaTopicos(Map<String, String> topicos) {
        this.topicos = Map.copyOf(topicos);
    }

    /** Topic of each table of the {@link Esquema} as {@code prefixo + "." + TABELA}, with overrides. */
    public static MapaTopicos de(String prefixo, Map<String, String> excecoes) {
        Map<String, String> mapa = new HashMap<>();
        for (TableSpec spec : Esquema.TABELAS) {
            mapa.put(spec.nome(), prefixo + "." + spec.nome());
        }
        excecoes.forEach((tabela, topico) -> {
            String chave = tabela.toUpperCase(Locale.ROOT);
            if (!mapa.containsKey(chave)) {
                throw new IllegalArgumentException("unknown table in the topic map: " + tabela);
            }
            mapa.put(chave, topico);
        });
        return new MapaTopicos(mapa);
    }

    public String topico(String tabela) {
        String topico = topicos.get(tabela);
        if (topico == null) {
            throw new IllegalArgumentException("no topic mapped for table " + tabela);
        }
        return topico;
    }

    public TopicPartition particao(String tabela, int particao) {
        return new TopicPartition(topico(tabela), particao);
    }

    /** Every (table, logical partition) of the schema, for the lag computation. */
    public List<TopicPartition> todasAsParticoes() {
        List<TopicPartition> todas = new ArrayList<>();
        for (TableSpec spec : Esquema.TABELAS) {
            for (int p = 0; p < Esquema.PARTICOES; p++) {
                todas.add(particao(spec.nome(), p));
            }
        }
        return todas;
    }
}
