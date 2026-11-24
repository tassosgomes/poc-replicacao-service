package br.org.ecad.poc.replicacao.infra.kafka.replicacao;

import br.org.ecad.poc.replicacao.application.port.Fluxo;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.EventoTabela;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.FimTransacao;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.Ignorado;
import br.org.ecad.poc.replicacao.application.port.FonteTransacional.Registro;
import br.org.ecad.poc.replicacao.domain.replicacao.ChangeEvent;
import br.org.ecad.poc.replicacao.domain.replicacao.ReplicacaoException;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Adds the transaction identity to what {@link TradutorDebezium} already translates: the
 * {@code transaction{id,total_order}} block of a table event, and the BEGIN/END markers of the
 * transaction topic ({@code id}, {@code status}, {@code event_count}, {@code data_collections}).
 * Thread-safe.
 */
public final class TradutorTransacional {

    private final TradutorDebezium eventos;
    private final ObjectMapper json = JsonMapper.builder().build();

    public TradutorTransacional(TradutorDebezium eventos) {
        this.eventos = eventos;
    }

    /** A record of a table topic; {@link Ignorado} for tombstones and ignored operations. */
    public Registro evento(TableSpec spec, MensagemKafka m) {
        Fluxo fluxo = new Fluxo(m.topico(), m.particao());
        Optional<ChangeEvent> evento = eventos.traduzir(spec, m);
        if (evento.isEmpty()) {
            return new Ignorado(fluxo, m.offset());
        }
        JsonNode raiz = json.readTree(m.valor());
        JsonNode envelope = raiz.has("payload") && raiz.get("payload").isObject() ? raiz.get("payload") : raiz;
        JsonNode tx = envelope.get("transaction");
        if (tx == null || tx.isNull() || tx.get("id") == null || tx.get("id").isNull()) {
            return new EventoTabela(fluxo, m.offset(), null, 0, evento.get());
        }
        long ordem = tx.get("total_order") == null ? 0 : tx.get("total_order").asLong();
        return new EventoTabela(fluxo, m.offset(), tx.get("id").asString(), ordem, evento.get());
    }

    /** A record of the transaction topic: only END matters; BEGIN and tombstones are {@link Ignorado}. */
    public Registro marcador(MensagemKafka m) {
        Fluxo fluxo = new Fluxo(m.topico(), m.particao());
        if (m.valor() == null) {
            return new Ignorado(fluxo, m.offset());
        }
        JsonNode raiz = json.readTree(m.valor());
        JsonNode envelope = raiz.has("payload") && raiz.get("payload").isObject() ? raiz.get("payload") : raiz;
        JsonNode status = envelope.get("status");
        if (status == null || status.isNull()) {
            throw erro(m, "missing status (not a Debezium transaction marker)");
        }
        if (!"END".equals(status.asString())) {
            return new Ignorado(fluxo, m.offset());
        }
        JsonNode id = envelope.get("id");
        JsonNode total = envelope.get("event_count");
        if (id == null || id.isNull() || total == null || total.isNull()) {
            throw erro(m, "END without id or event_count");
        }
        Map<String, Integer> porTabela = new LinkedHashMap<>();
        JsonNode colecoes = envelope.get("data_collections");
        if (colecoes != null && colecoes.isArray()) {
            for (JsonNode c : colecoes) {
                String nome = c.get("data_collection").asString();
                porTabela.merge(
                        nome.substring(nome.lastIndexOf('.') + 1),
                        c.get("event_count").asInt(),
                        Integer::sum);
            }
        }
        return new FimTransacao(fluxo, m.offset(), id.asString(), total.asInt(), porTabela);
    }

    private static ReplicacaoException erro(MensagemKafka m, String motivo) {
        return new ReplicacaoException(
                "cannot translate " + m.topico() + "/" + m.particao() + "@" + m.offset() + ": " + motivo, null);
    }
}
