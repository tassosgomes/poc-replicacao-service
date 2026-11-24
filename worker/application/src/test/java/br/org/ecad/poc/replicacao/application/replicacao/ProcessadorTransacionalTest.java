package br.org.ecad.poc.replicacao.application.replicacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.org.ecad.poc.replicacao.application.coordenacao.LeasePerdidoException;
import br.org.ecad.poc.replicacao.application.port.Cerca;
import br.org.ecad.poc.replicacao.application.port.Fluxo;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.EventoTabela;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.FimTransacao;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.Ignorado;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.Registro;
import br.org.ecad.poc.replicacao.application.port.ReplicaSink;
import br.org.ecad.poc.replicacao.application.port.RepositorioOffsets;
import br.org.ecad.poc.replicacao.domain.replicacao.ChangeEvent;
import br.org.ecad.poc.replicacao.domain.replicacao.Linha;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ProcessadorTransacionalTest {

    private static final Fluxo OBRA_P0 = new Fluxo("kf.BDAD.OBRA_MUSICAL", 0);
    private static final Fluxo FONO_P0 = new Fluxo("kf.BDAD.FONOGRAMA", 0);
    private static final Fluxo FONO_P1 = new Fluxo("kf.BDAD.FONOGRAMA", 1);
    private static final Fluxo TX = new Fluxo("kf.transaction", 0);
    private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");

    /** Source scripted per poll; positions are honored on start like the real one (a restart re-reads from them). */
    private static final class FonteFalsa implements FonteTransacional {
        final Map<Fluxo, List<Registro>> logs = new LinkedHashMap<>();
        final List<List<Registro>> entregas = new ArrayList<>();
        Map<Fluxo, Long> iniciada;
        final List<Map<Fluxo, Long>> informadas = new ArrayList<>();

        @Override
        public Map<Fluxo, Long> iniciar(Map<Fluxo, Long> salvas) {
            Map<Fluxo, Long> posicoes = new HashMap<>();
            for (Fluxo f : List.of(OBRA_P0, FONO_P0, FONO_P1, TX)) {
                posicoes.put(f, salvas.getOrDefault(f, 0L));
            }
            iniciada = posicoes;
            return posicoes;
        }

        @Override
        public List<Registro> poll() {
            return entregas.isEmpty() ? List.of() : entregas.remove(0);
        }

        @Override
        public void informar(Map<Fluxo, Long> posicoes) {
            informadas.add(new HashMap<>(posicoes));
        }

        void entregar(Registro... registros) {
            entregas.add(List.of(registros));
        }
    }

    /** Sink with a commit log: what a reader of the destination can see after each commit. */
    private static final class SinkFalso implements ReplicaSink {
        final Map<String, Map<Long, Linha>> destino = new HashMap<>();
        final Map<String, Map<Long, Linha>> pendente = new HashMap<>();
        final List<Map<String, Integer>> commits = new ArrayList<>();
        int upserts;

        @Override
        public void upsert(TableSpec spec, List<Linha> linhas) {
            upserts++;
            for (Linha l : linhas) {
                pendente.computeIfAbsent(spec.nome(), k -> new HashMap<>()).put(l.codigo(), l);
            }
        }

        @Override
        public void commit() {
            Map<String, Integer> visto = new HashMap<>();
            pendente.forEach(
                    (t, m) -> destino.computeIfAbsent(t, k -> new HashMap<>()).putAll(m));
            destino.forEach((t, m) -> visto.put(t, m.size()));
            commits.add(visto);
            pendente.clear();
        }
    }

    private static final class OffsetsFalsos implements RepositorioOffsets {
        final Map<Fluxo, Long> comitados = new HashMap<>();
        final Map<Fluxo, Long> pendentes = new HashMap<>();

        @Override
        public Map<Fluxo, Long> carregar() {
            return new HashMap<>(comitados);
        }

        @Override
        public void gravar(Map<Fluxo, Long> posicoes) {
            pendentes.putAll(posicoes);
        }

        void commit() {
            comitados.putAll(pendentes);
            pendentes.clear();
        }
    }

    private final FonteFalsa fonte = new FonteFalsa();
    private final SinkFalso sink = new SinkFalso();
    private final OffsetsFalsos offsets = new OffsetsFalsos();
    private final Clock[] relogio = {Clock.fixed(T0, ZoneOffset.UTC)};
    private final AtomicBoolean cercaAberta = new AtomicBoolean(true);

    private final Cerca cerca = new Cerca() {
        @Override
        public boolean podeConfirmarDestino() {
            return cercaAberta.get();
        }

        @Override
        public boolean confirmarDestino(ReplicaSink s) {
            if (!cercaAberta.get()) {
                return false;
            }
            s.commit();
            offsets.commit();
            return true;
        }

        @Override
        public boolean podeConfirmarOrigem() {
            return true;
        }
    };

    private ProcessadorTransacional processador(int transacoesPorCommit, int bufferMax) {
        Clock proxy = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return relogio[0].instant();
            }
        };
        ProcessadorTransacional p = new ProcessadorTransacional(
                fonte,
                sink,
                offsets,
                cerca,
                proxy,
                new ProcessadorTransacional.Parametros(transacoesPorCommit, 10_000, bufferMax, Duration.ofSeconds(60)));
        p.iniciar();
        sink.commits.clear(); // the commit of the initial positions is not under test here
        return p;
    }

    private static EventoTabela evento(Fluxo fluxo, long offset, String tx, long ordem, String tabela, long codigo) {
        Instant versao = T0.plusMillis(offset);
        ChangeEvent e = new ChangeEvent(offset, tabela, codigo, 'I', versao, versao, List.of("x"));
        return new EventoTabela(fluxo, offset, tx, ordem, e);
    }

    private static EventoTabela obra(long offset, String tx, long ordem, long codigo) {
        return evento(OBRA_P0, offset, tx, ordem, "OBRA_MUSICAL", codigo);
    }

    private static EventoTabela fono(Fluxo fluxo, long offset, String tx, long ordem, long codigo) {
        return evento(fluxo, offset, tx, ordem, "FONOGRAMA", codigo);
    }

    private static FimTransacao fim(long offset, String tx, int obras, int fonos) {
        Map<String, Integer> porTabela = new LinkedHashMap<>();
        if (obras > 0) porTabela.put("OBRA_MUSICAL", obras);
        if (fonos > 0) porTabela.put("FONOGRAMA", fonos);
        return new FimTransacao(TX, offset, tx, obras + fonos, porTabela);
    }

    @Test
    void processarCiclo_transacaoCompleta_aplicaTudoEmUmCommitEGravaOsOffsets() {
        ProcessadorTransacional p = processador(50, 1000);
        fonte.entregar(
                new Ignorado(TX, 0),
                obra(0, "t1", 1, 1),
                fono(FONO_P0, 0, "t1", 2, 10),
                fono(FONO_P1, 0, "t1", 3, 11),
                fim(1, "t1", 1, 2));

        ResultadoCiclo r = p.processarCiclo();

        assertThat(r.transacoes()).isEqualTo(1);
        assertThat(r.eventosAplicados()).isEqualTo(3);
        assertThat(sink.commits).hasSize(1);
        assertThat(sink.commits.get(0)).containsEntry("OBRA_MUSICAL", 1).containsEntry("FONOGRAMA", 2);
        assertThat(offsets.comitados)
                .containsEntry(OBRA_P0, 1L)
                .containsEntry(FONO_P0, 1L)
                .containsEntry(FONO_P1, 1L)
                .containsEntry(TX, 2L);
        assertThat(p.emMemoria()).isZero();
    }

    @Test
    void processarCiclo_endAntesDosEventos_esperaOsEventosENaoAplicaParcial() {
        ProcessadorTransacional p = processador(50, 1000);
        fonte.entregar(fim(1, "t1", 1, 2), obra(0, "t1", 1, 1));
        fonte.entregar(fono(FONO_P0, 0, "t1", 2, 10));
        fonte.entregar(fono(FONO_P1, 0, "t1", 3, 11));

        assertThat(p.processarCiclo().transacoes()).isZero();
        assertThat(p.processarCiclo().transacoes()).isZero();
        assertThat(sink.commits).isEmpty();
        assertThat(sink.upserts).isZero();

        assertThat(p.processarCiclo().transacoes()).isEqualTo(1);
        assertThat(sink.commits).hasSize(1);
        assertThat(sink.destino.get("FONOGRAMA")).hasSize(2);
    }

    @Test
    void processarCiclo_transacoesIntercaladas_aplicaNaOrdemDoEndSemFurarFila() {
        ProcessadorTransacional p = processador(1, 1000);
        // t1 ENDs before t2 but only t2 is complete at first: t2 must wait for t1.
        fonte.entregar(fim(1, "t1", 0, 2), fim(3, "t2", 1, 0), obra(0, "t2", 1, 2), fono(FONO_P0, 0, "t1", 1, 10));
        fonte.entregar(fono(FONO_P1, 0, "t1", 2, 11));

        assertThat(p.processarCiclo().transacoes()).isZero();
        assertThat(sink.commits).isEmpty();

        assertThat(p.processarCiclo().transacoes()).isEqualTo(1);
        assertThat(sink.commits).hasSize(1);
        assertThat(sink.commits.get(0)).containsEntry("FONOGRAMA", 2).doesNotContainKey("OBRA_MUSICAL");

        assertThat(p.processarCiclo().transacoes()).isEqualTo(1);
        assertThat(sink.commits.get(1)).containsEntry("OBRA_MUSICAL", 1);
    }

    @Test
    void processarCiclo_varias_transacoesCompletasConsecutivas_dividemUmCommitAteOLimite() {
        ProcessadorTransacional p = processador(2, 1000);
        fonte.entregar(
                obra(0, "t1", 1, 1),
                fim(0, "t1", 1, 0),
                obra(1, "t2", 1, 2),
                fim(1, "t2", 1, 0),
                obra(2, "t3", 1, 3),
                fim(2, "t3", 1, 0));

        assertThat(p.processarCiclo().transacoes()).isEqualTo(2);
        assertThat(sink.commits).hasSize(1);
        assertThat(sink.commits.get(0)).containsEntry("OBRA_MUSICAL", 2);
        assertThat(p.processarCiclo().transacoes()).isEqualTo(1);
        assertThat(sink.commits).hasSize(2);
    }

    @Test
    void processarCiclo_offsetNuncaPassaDeEventoAindaNaoAplicado() {
        ProcessadorTransacional p = processador(50, 1000);
        // t1 complete; t2 has an event read (offset 1 of FONO_P0) but no END yet.
        fonte.entregar(fono(FONO_P0, 0, "t1", 1, 10), fim(0, "t1", 0, 1), fono(FONO_P0, 1, "t2", 1, 20));

        p.processarCiclo();

        assertThat(sink.destino.get("FONOGRAMA")).containsOnlyKeys(10L);
        assertThat(offsets.comitados).containsEntry(FONO_P0, 1L).containsEntry(TX, 1L);
    }

    @Test
    void reiniciar_aPartirDosOffsetsGravados_naoReaplicaEAplicaOQueFaltava() {
        ProcessadorTransacional p = processador(50, 1000);
        fonte.entregar(fono(FONO_P0, 0, "t1", 1, 10), fim(0, "t1", 0, 1), fono(FONO_P0, 1, "t2", 1, 20));
        p.processarCiclo();
        int upsertsAntes = sink.upserts;

        // New instance (restart or failover): positions come from the destination, memory is gone.
        FonteFalsa nova = new FonteFalsa();
        ProcessadorTransacional q = new ProcessadorTransacional(
                nova,
                sink,
                offsets,
                cerca,
                Clock.fixed(T0, ZoneOffset.UTC),
                new ProcessadorTransacional.Parametros(50, 10_000, 1000, Duration.ofSeconds(60)));
        q.iniciar();
        assertThat(nova.iniciada).containsEntry(FONO_P0, 1L).containsEntry(TX, 1L);

        nova.entregar(fono(FONO_P0, 1, "t2", 1, 20), fim(1, "t2", 0, 1));
        assertThat(q.processarCiclo().transacoes()).isEqualTo(1);
        assertThat(sink.upserts).isEqualTo(upsertsAntes + 1);
        assertThat(sink.destino.get("FONOGRAMA")).containsOnlyKeys(10L, 20L);
    }

    @Test
    void processarCiclo_eventoSemTransacao_eAplicadoSozinho() {
        ProcessadorTransacional p = processador(50, 1000);
        fonte.entregar(obra(0, null, 0, 7));

        ResultadoCiclo r = p.processarCiclo();

        assertThat(r.transacoes()).isZero();
        assertThat(r.eventosAplicados()).isEqualTo(1);
        assertThat(sink.destino.get("OBRA_MUSICAL")).containsOnlyKeys(7L);
        assertThat(offsets.comitados).containsEntry(OBRA_P0, 1L);
    }

    @Test
    void processarCiclo_transacaoSemEndAposOPrazo_eAplicadaSozinhaComAviso() {
        ProcessadorTransacional p = processador(50, 1000);
        fonte.entregar(obra(0, "orfa", 1, 5));
        assertThat(p.processarCiclo().eventosAplicados()).isZero();

        relogio[0] = Clock.fixed(T0.plusSeconds(61), ZoneOffset.UTC);
        ResultadoCiclo r = p.processarCiclo();

        assertThat(r.eventosAplicados()).isEqualTo(1);
        assertThat(p.orfas()).isEqualTo(1);
        assertThat(sink.destino.get("OBRA_MUSICAL")).containsOnlyKeys(5L);
    }

    @Test
    void processarCiclo_transacaoGrande_aplicaEmUmCommitSoComEventosEmVariosPolls() {
        ProcessadorTransacional p = processador(50, 10_000);
        int total = 5_000;
        for (int i = 0; i < total; i += 500) {
            List<Registro> lote = new ArrayList<>();
            for (int j = i; j < i + 500; j++) {
                lote.add(fono(j % 2 == 0 ? FONO_P0 : FONO_P1, j / 2, "grande", j, 1000 + j));
            }
            fonte.entregas.add(lote);
        }
        fonte.entregar(fim(0, "grande", 0, total));

        for (int i = 0; i < total / 500; i++) {
            assertThat(p.processarCiclo().transacoes()).isZero();
        }
        assertThat(sink.commits).isEmpty();
        assertThat(p.emMemoria()).isEqualTo(total);

        assertThat(p.processarCiclo().transacoes()).isEqualTo(1);
        assertThat(sink.commits).hasSize(1);
        assertThat(sink.destino.get("FONOGRAMA")).hasSize(total);
        assertThat(p.emMemoria()).isZero();
    }

    @Test
    void processarCiclo_bufferExcedido_falhaAltoEmVezDeCrescerSemLimite() {
        ProcessadorTransacional p = processador(50, 3);
        fonte.entregar(
                fono(FONO_P0, 0, "t1", 1, 1),
                fono(FONO_P0, 1, "t1", 2, 2),
                fono(FONO_P0, 2, "t1", 3, 3),
                fono(FONO_P0, 3, "t1", 4, 4));

        assertThatThrownBy(p::processarCiclo)
                .isInstanceOf(ReplicacaoException.class)
                .hasMessageContaining("buffer exceeded");
    }

    @Test
    void processarCiclo_transacaoComMaisEventosQueOEnd_falha() {
        ProcessadorTransacional p = processador(50, 1000);
        fonte.entregar(fono(FONO_P0, 0, "t1", 1, 1), fono(FONO_P0, 1, "t1", 2, 2), fim(0, "t1", 0, 1));

        assertThatThrownBy(p::processarCiclo).isInstanceOf(ReplicacaoException.class);
    }

    @Test
    void processarCiclo_cercaRecusa_nadaFicaVisivelEORetryAplicaDeNovoIdempotente() {
        ProcessadorTransacional p = processador(50, 1000);
        fonte.entregar(obra(0, "t1", 1, 1), fim(0, "t1", 1, 0));
        cercaAberta.set(false);

        assertThatThrownBy(p::processarCiclo).isInstanceOf(LeasePerdidoException.class);
        assertThat(sink.destino).isEmpty();
        assertThat(offsets.comitados).containsEntry(OBRA_P0, 0L);

        cercaAberta.set(true);
        assertThat(p.processarCiclo().transacoes()).isEqualTo(1);
        assertThat(sink.destino.get("OBRA_MUSICAL")).containsOnlyKeys(1L);
    }

    @Test
    void iniciar_posicoesPadrao_ficamDuraveisNoMesmoInstante() {
        processador(50, 1000);

        assertThat(offsets.comitados).containsKeys(OBRA_P0, FONO_P0, FONO_P1, TX);
        assertThat(fonte.informadas).isNotEmpty();
    }

    @Test
    void processarCiclo_mesmaChaveEmDuasTransacoesDoGrupo_vaiOUltimoEstado() {
        ProcessadorTransacional p = processador(50, 1000);
        fonte.entregar(obra(0, "t1", 1, 1), fim(0, "t1", 1, 0), obra(1, "t2", 1, 1), fim(1, "t2", 1, 0));

        p.processarCiclo();

        assertThat(sink.destino.get("OBRA_MUSICAL").get(1L).versao()).isEqualTo(T0.plusMillis(1));
        assertThat(sink.commits).hasSize(1);
    }
}
