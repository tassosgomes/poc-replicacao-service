package br.org.ecad.poc.replicacao.reconciliacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.org.ecad.poc.replicacao.core.Esquema;
import org.junit.jupiter.api.Test;

class V09HashTest {
    @Test
    void hashLinha_incluiCodigoEColunasDeNegocioEVersao() {
        String legado = Reconciliacao.hashLinha(Esquema.OBRA_MUSICAL, false);
        String replica = Reconciliacao.hashLinha(Esquema.OBRA_MUSICAL, true);
        assertThat(legado)
                .contains("ORA_HASH(", "TO_CHAR(codigo", "titulo", "updated_at")
                .doesNotContain("replicado_em");
        assertThat(replica.replace("origem_updated_at", "updated_at")).isEqualTo(legado);
    }
}
