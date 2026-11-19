package br.org.ecad.poc.replicacao.domain.replicacao;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Current state of one row: business key, source version ({@code updated_at}) and the business
 * column values in the order of {@link TableSpec#colunas()} (key and version excluded).
 */
public record Linha(long codigo, Instant versao, List<Object> valores) {

    public Linha {
        valores = Collections.unmodifiableList(new ArrayList<>(valores));
    }
}
