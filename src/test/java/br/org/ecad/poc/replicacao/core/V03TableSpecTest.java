package br.org.ecad.poc.replicacao.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class V03TableSpecTest {

    @Test
    void merge_titular_generatesGuardedUpsertWithIdOnlyOnInsert() {
        assertThat(Esquema.TITULAR.merge())
                .isEqualTo("MERGE INTO titular t USING (SELECT ? AS codigo, ? AS nome, ? AS tipo, ? AS documento,"
                        + " ? AS situacao, ? AS excluido, ? AS origem_updated_at FROM dual) s"
                        + " ON (t.codigo = s.codigo)"
                        + " WHEN MATCHED THEN UPDATE SET t.nome = s.nome, t.tipo = s.tipo,"
                        + " t.documento = s.documento, t.situacao = s.situacao, t.excluido = s.excluido,"
                        + " t.origem_updated_at = s.origem_updated_at, t.replicado_em = SYSTIMESTAMP"
                        + " WHERE t.origem_updated_at IS NULL OR s.origem_updated_at >= t.origem_updated_at"
                        + " WHEN NOT MATCHED THEN INSERT (id, codigo, nome, tipo, documento, situacao, excluido,"
                        + " origem_updated_at, replicado_em)"
                        + " VALUES (?, s.codigo, s.nome, s.tipo, s.documento, s.situacao, s.excluido,"
                        + " s.origem_updated_at, SYSTIMESTAMP)");
    }

    @Test
    void merge_obraMusical_matchesTheDocumentedStatement() {
        String merge = Esquema.OBRA_MUSICAL.merge();

        assertThat(merge)
                .startsWith("MERGE INTO obra_musical t USING (SELECT ? AS codigo, ? AS titulo, ? AS iswc,"
                        + " ? AS genero, ? AS situacao, ? AS excluido, ? AS origem_updated_at FROM dual) s")
                .contains("t.titulo = s.titulo, t.iswc = s.iswc, t.genero = s.genero, t.situacao = s.situacao,"
                        + " t.excluido = s.excluido, t.origem_updated_at = s.origem_updated_at")
                .contains("WHERE t.origem_updated_at IS NULL OR s.origem_updated_at >= t.origem_updated_at")
                .endsWith("VALUES (?, s.codigo, s.titulo, s.iswc, s.genero, s.situacao, s.excluido,"
                        + " s.origem_updated_at, SYSTIMESTAMP)");
        assertThat(merge.chars().filter(c -> c == '?').count()).isEqualTo(8);
    }

    @Test
    void selectEstado_fonograma_listsKeyVersionAndBusinessColumnsForTheBlock() {
        assertThat(Esquema.FONOGRAMA.selectEstado(3))
                .isEqualTo("SELECT codigo, updated_at, obra_codigo, titulo, isrc, duracao_seg, excluido"
                        + " FROM fonograma WHERE codigo IN (?, ?, ?)");
        assertThat(Esquema.FONOGRAMA.merge()).contains("MERGE INTO fonograma t").contains("obra_codigo");
        assertThat(Esquema.TABELAS).containsExactly(Esquema.TITULAR, Esquema.OBRA_MUSICAL, Esquema.FONOGRAMA);
    }
}
