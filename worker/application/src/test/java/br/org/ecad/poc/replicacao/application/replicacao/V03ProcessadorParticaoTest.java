package br.org.ecad.poc.replicacao.application.replicacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.ecad.poc.replicacao.application.port.ChangeSource;
import br.org.ecad.poc.replicacao.application.port.ReplicaSink;
import br.org.ecad.poc.replicacao.application.port.StateProvider;
import br.org.ecad.poc.replicacao.domain.replicacao.ChangeEvent;
import br.org.ecad.poc.replicacao.domain.replicacao.Esquema;
import br.org.ecad.poc.replicacao.domain.replicacao.Estados;
import br.org.ecad.poc.replicacao.domain.replicacao.Linha;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The pipeline runs on in-memory ports: no database, and nothing Oracle-specific in the loop. */
class V03ProcessadorParticaoTest {

    private static final Instant VERSAO = Instant.parse("2026-10-31T12:00:00Z");

    @Test
    void processarLote_inMemorySource_feedsTheSinkCoalescedAndAcksOnlyAfterCommit() {
        List<String> ordem = new ArrayList<>();
        List<ChangeEvent> pendentes = new ArrayList<>(List.of(
                ChangeEvent.soChave(1, "OBRA_MUSICAL", 42, 'U', Instant.EPOCH),
                ChangeEvent.soChave(2, "OBRA_MUSICAL", 42, 'U', Instant.EPOCH),
                ChangeEvent.soChave(3, "OBRA_MUSICAL", 7, 'I', Instant.EPOCH),
                ChangeEvent.soChave(4, "OBRA_MUSICAL", 99, 'U', Instant.EPOCH)));
        ChangeSource source = new ChangeSource() {
            @Override
            public List<ChangeEvent> poll(String tabela, int particao, int max) {
                return List.copyOf(pendentes.subList(0, Math.min(max, pendentes.size())));
            }

            @Override
            public void ack(List<ChangeEvent> eventos) {
                ordem.add("ack");
                pendentes.removeAll(eventos);
            }
        };
        Map<Long, Linha> origem = Map.of(
                42L, new Linha(42, VERSAO, List.of("t42", "i", "g", "ATIVO", 0)),
                7L, new Linha(7, VERSAO, List.of("t7", "i", "g", "ATIVO", 0)));
        List<Collection<Long>> buscas = new ArrayList<>();
        StateProvider state = (spec, codigos) -> {
            buscas.add(List.copyOf(codigos));
            Map<Long, Linha> achadas = new LinkedHashMap<>();
            Set<Long> ausentes = new HashSet<>();
            for (Long codigo : codigos) {
                if (origem.containsKey(codigo)) {
                    achadas.put(codigo, origem.get(codigo));
                } else {
                    ausentes.add(codigo);
                }
            }
            return new Estados(achadas, ausentes);
        };
        Map<Long, Linha> destino = new LinkedHashMap<>();
        ReplicaSink sink = new ReplicaSink() {
            @Override
            public void upsert(TableSpec spec, List<Linha> linhas) {
                ordem.add("upsert");
                linhas.forEach(l -> destino.put(l.codigo(), l));
            }

            @Override
            public void commit() {
                ordem.add("commit");
            }
        };
        ProcessadorParticao processador = new ProcessadorParticao(Esquema.OBRA_MUSICAL, 3, 500, source, state, sink);

        ResultadoLote resultado = processador.processarLote();

        assertThat(buscas).hasSize(1);
        assertThat(buscas.get(0)).containsExactly(42L, 7L, 99L);
        assertThat(destino).containsOnlyKeys(42L, 7L).containsValue(origem.get(42L));
        assertThat(ordem).containsExactly("upsert", "commit", "ack");
        assertThat(pendentes).isEmpty();
        assertThat(resultado).isEqualTo(new ResultadoLote(4, 2, 1));
        assertThat(processador.processarLote()).isEqualTo(ResultadoLote.VAZIO);
    }
}
