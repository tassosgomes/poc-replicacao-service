package br.org.ecad.poc.replicacao.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Groups the events of a batch by {@code codigo}: one lookup per key, whatever the operation. */
public final class Coalescer {

    /** All events of one key, in delivery order. */
    public record Grupo(long codigo, List<ChangeEvent> eventos) {

        public ChangeEvent ultimo() {
            return eventos.get(eventos.size() - 1);
        }
    }

    private Coalescer() {}

    /** Groups in order of first appearance; events inside a group keep their order. */
    public static List<Grupo> coalesce(List<ChangeEvent> eventos) {
        Map<Long, List<ChangeEvent>> porCodigo = new LinkedHashMap<>();
        for (ChangeEvent evento : eventos) {
            porCodigo.computeIfAbsent(evento.codigo(), k -> new ArrayList<>()).add(evento);
        }
        List<Grupo> grupos = new ArrayList<>(porCodigo.size());
        porCodigo.forEach((codigo, lista) -> grupos.add(new Grupo(codigo, List.copyOf(lista))));
        return List.copyOf(grupos);
    }
}
