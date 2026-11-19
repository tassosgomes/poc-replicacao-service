package br.org.ecad.poc.replicacao.application.port;

import br.org.ecad.poc.replicacao.domain.replicacao.Linha;
import br.org.ecad.poc.replicacao.domain.replicacao.ReprocessavelException;
import br.org.ecad.poc.replicacao.domain.replicacao.TableSpec;
import java.util.List;

/**
 * Destination of the replication. {@link #upsert} is idempotent and guarded by version (an older
 * state never overwrites a newer one); the {@code id} is assigned only when the row is new;
 * {@link #commit} makes the batch durable. A uniqueness violation caused by a concurrent writer
 * is signalled with {@link ReprocessavelException}.
 */
public interface ReplicaSink {

    void upsert(TableSpec spec, List<Linha> linhas);

    void commit();
}
