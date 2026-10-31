package br.org.ecad.poc.replicacao.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class V03CoalescerTest {

    private static ChangeEvent evento(long id, long codigo, char op) {
        return ChangeEvent.soChave(id, "OBRA_MUSICAL", codigo, op, Instant.EPOCH);
    }

    @Test
    void coalesce_groupsByCodigoKeepingFirstAppearanceAndDeliveryOrder() {
        List<ChangeEvent> eventos = List.of(
                evento(1, 42, 'U'), evento(2, 7, 'I'), evento(3, 42, 'U'), evento(4, 42, 'D'), evento(5, 7, 'U'));

        List<Coalescer.Grupo> grupos = Coalescer.coalesce(eventos);

        assertThat(grupos).extracting(Coalescer.Grupo::codigo).containsExactly(42L, 7L);
        assertThat(grupos.get(0).eventos()).extracting(ChangeEvent::eventoId).containsExactly(1L, 3L, 4L);
        assertThat(grupos.get(1).eventos()).extracting(ChangeEvent::eventoId).containsExactly(2L, 5L);
        assertThat(grupos.get(0).ultimo().op()).isEqualTo('D');
    }
}
