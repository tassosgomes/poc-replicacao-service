package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

import br.org.ecad.poc.replicacao.domain.replicacao.ChangeEvent;
import br.org.ecad.poc.replicacao.domain.replicacao.Esquema;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Translates a Debezium Oracle envelope (JSON without schema: {@code before}, {@code after},
 * {@code op}, {@code source}) into a {@link ChangeEvent} that already carries the state, so the
 * worker never queries the origin.
 *
 * <ul>
 *   <li>{@code c}, {@code r} (snapshot) and {@code u}: op {@code I}/{@code I}/{@code U}; state from {@code after};
 *       version = {@code updated_at} of {@code after}.
 *   <li>{@code d} (physical delete): op {@code D}; state is the {@code before} with {@code excluido = 1}. The row has
 *       no new {@code updated_at}, so the version is {@code max(source.ts_ms, before.updated_at + 1 us)}: the instant
 *       the origin database recorded the change (same clock that stamps {@code updated_at}), forced strictly after the
 *       last known version so the sink guard accepts it. {@code ts_ms} and {@code commit_ts_ms} of the real
 *       connector have 1 s resolution (LogMiner) and are truncated, so they are usually <em>earlier</em> than the
 *       microsecond {@code updated_at} of the same second; then the {@code + 1 us} branch decides, which is still
 *       correct: the delete is by definition after the last version we know, and the guard only needs "newer than
 *       the stored row". What is lost is precision against a competing write inside the same second; ordering
 *       between transactions is already guaranteed by the topic order of one key (same partition). Risk: a later
 *       re-insert of the same {@code codigo} whose {@code updated_at} falls before that instant would be discarded as
 *       stale; the lab generator never deletes physically nor reuses keys. {@code before} needs supplemental
 *       logging of all columns, otherwise the translation fails loudly instead of writing nulls.
 *   <li>Tombstone (null value) and {@code m} (logical message): ignored, empty result. {@code t} (truncate) fails.
 * </ul>
 *
 * {@code criadoEm} is the {@code updated_at} of {@code after} (microsecond DML time stamped by the origin trigger,
 * the same moment the lab Java outbox row is created, so latencies are comparable); for a delete it is
 * {@code source.commit_ts_ms}/{@code ts_ms} (1 s resolution), then the record timestamp. Thread-safe.
 */
public final class TradutorDebezium {

    static final char OP_INSERT = 'I';
    static final char OP_UPDATE = 'U';
    static final char OP_DELETE = 'D';
    private static final String VERSAO = "updated_at";
    private static final String EXCLUIDO = "excluido";

    private final ObjectMapper json = JsonMapper.builder().build();
    private final ValoresDebezium valores;

    public TradutorDebezium(FormatoDebezium formato) {
        this.valores = new ValoresDebezium(formato);
    }

    /** Translates one message of the topic of {@code spec}; empty for tombstones and ignored operations. */
    public Optional<ChangeEvent> traduzir(TableSpec spec, MensagemKafka mensagem) {
        if (mensagem.valor() == null) {
            return Optional.empty();
        }
        JsonNode raiz;
        try {
            raiz = json.readTree(mensagem.valor());
        } catch (RuntimeException e) {
            throw erro(spec, mensagem, "invalid JSON", e);
        }
        // Some converter settings keep the envelope under "payload" (schemas.enable=true).
        JsonNode envelope = raiz.has("payload") && raiz.get("payload").isObject() ? raiz.get("payload") : raiz;
        String op = texto(envelope.get("op"));
        if (op == null) {
            throw erro(spec, mensagem, "missing op (not a Debezium change envelope)", null);
        }
        JsonNode origem = envelope.get("source");
        confereTabela(spec, mensagem, origem);
        Instant criadoEm = criadoEm(origem, envelope, mensagem);
        return switch (op) {
            case "c", "r" -> Optional.of(comEstado(spec, mensagem, envelope.get("after"), OP_INSERT));
            case "u" -> Optional.of(comEstado(spec, mensagem, envelope.get("after"), OP_UPDATE));
            case "d" -> Optional.of(exclusao(spec, mensagem, envelope.get("before"), criadoEm));
            case "m" -> Optional.empty();
            default -> throw erro(spec, mensagem, "unsupported op '" + op + "'", null);
        };
    }

    private ChangeEvent comEstado(TableSpec spec, MensagemKafka m, JsonNode after, char op) {
        if (after == null || !after.isObject()) {
            throw erro(spec, m, "op " + op + " without after", null);
        }
        Instant versao = valores.instante(VERSAO, campo(after, VERSAO));
        if (versao == null) {
            throw erro(spec, m, "after without " + VERSAO, null);
        }
        return new ChangeEvent(
                m.offset(), spec.nome(), codigo(spec, m, after), op, versao, versao, estado(spec, after));
    }

    private ChangeEvent exclusao(TableSpec spec, MensagemKafka m, JsonNode before, Instant criadoEm) {
        if (before == null || !before.isObject()) {
            throw erro(spec, m, "delete without before", null);
        }
        Instant ultimaVersao = valores.instante(VERSAO, campo(before, VERSAO));
        if (ultimaVersao == null) {
            throw erro(
                    spec, m, "delete whose before has no " + VERSAO + " (supplemental logging of all columns?)", null);
        }
        Instant versao = criadoEm.isAfter(ultimaVersao) ? criadoEm : ultimaVersao.plus(1, ChronoUnit.MICROS);
        List<Object> estado = estado(spec, before);
        int posicao = spec.colunas().indexOf(EXCLUIDO);
        if (posicao < 0) {
            throw erro(spec, m, "table has no " + EXCLUIDO + " column to mark the delete", null);
        }
        estado.set(posicao, 1L);
        return new ChangeEvent(m.offset(), spec.nome(), codigo(spec, m, before), OP_DELETE, criadoEm, versao, estado);
    }

    private List<Object> estado(TableSpec spec, JsonNode linha) {
        List<Object> estado = new ArrayList<>(spec.colunas().size());
        for (String coluna : spec.colunas()) {
            estado.add(valores.coluna(coluna, campo(linha, coluna)));
        }
        return estado;
    }

    private long codigo(TableSpec spec, MensagemKafka m, JsonNode linha) {
        Object codigo = valores.coluna("codigo", campo(linha, "codigo"));
        if (codigo instanceof Long valor) {
            return valor;
        }
        throw erro(spec, m, "missing or non-integer codigo: " + codigo, null);
    }

    /** Oracle names arrive in upper case; the worker schema is lower case. */
    private static JsonNode campo(JsonNode linha, String nome) {
        JsonNode valor = linha.get(nome);
        if (valor == null) {
            valor = linha.get(nome.toUpperCase(Locale.ROOT));
        }
        return valor;
    }

    private void confereTabela(TableSpec spec, MensagemKafka m, JsonNode origem) {
        String tabela = origem == null ? null : texto(origem.get("table"));
        if (tabela != null && !tabela.equalsIgnoreCase(spec.nome())) {
            throw erro(spec, m, "message of table " + tabela + " in the topic of " + spec.nome(), null);
        }
    }

    private Instant criadoEm(JsonNode origem, JsonNode envelope, MensagemKafka m) {
        for (JsonNode candidato : new JsonNode[] {
            origem == null ? null : origem.get("commit_ts_ms"), origem == null ? null : origem.get("ts_ms")
        }) {
            if (candidato != null && candidato.isNumber()) {
                return Instant.ofEpochMilli(candidato.longValue());
            }
        }
        return Instant.ofEpochMilli(m.timestampMs());
    }

    private static String texto(JsonNode no) {
        return no == null || no.isNull() ? null : no.asString();
    }

    private static ReplicacaoException erro(TableSpec spec, MensagemKafka m, String motivo, Throwable causa) {
        return new ReplicacaoException(
                "cannot translate " + m.topico() + "/" + m.particao() + "@" + m.offset() + " (" + spec.nome() + "): "
                        + motivo,
                causa);
    }

    /** Looks a table up by name in the schema. */
    public static TableSpec tabela(String nome) {
        return Esquema.TABELAS.stream()
                .filter(t -> t.nome().equals(nome))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown table " + nome));
    }
}
