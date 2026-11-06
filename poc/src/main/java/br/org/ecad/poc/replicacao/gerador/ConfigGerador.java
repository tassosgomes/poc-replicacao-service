package br.org.ecad.poc.replicacao.gerador;

import br.org.ecad.poc.replicacao.bootstrap.config.ConfigException;
import java.util.function.Function;

public record ConfigGerador(
        int taxa,
        int duracao,
        int threads,
        int txMax,
        int[] mix,
        int insert,
        int delete,
        int hot,
        int rajada,
        int aberta,
        long semente) {
    public static ConfigGerador load(Function<String, String> lookup) {
        int taxa = numero(lookup, "TAXA", 1000, 1);
        int duracao = numero(lookup, "DURACAO_SEG", 600, 1);
        int threads = numero(lookup, "THREADS", 8, 1);
        int txMax = numero(lookup, "TX_MAX", 5, 1);
        int insert = numero(lookup, "PCT_INSERT", 5, 0);
        int delete = numero(lookup, "PCT_DELETE", 1, 0);
        int hot = numero(lookup, "HOT_PCT", 10, 0);
        int rajada = numero(lookup, "RAJADA_LINHAS", 0, 0);
        int aberta = numero(lookup, "TX_ABERTA_SEG", 0, 0);
        String valor = lookup.apply("MIX");
        String[] partes = (valor == null ? "40,40,20" : valor).split(",", -1);
        if (partes.length != 3) throw new ConfigException("MIX deve ter três pesos");
        int[] mix = new int[3];
        try {
            for (int i = 0; i < 3; i++) mix[i] = Integer.parseInt(partes[i].trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("MIX inválido");
        }
        if (mix[0] < 0
                || mix[1] < 0
                || mix[2] < 0
                || mix[0] + mix[1] + mix[2] != 100
                || insert > 100
                || delete > 100
                || insert + delete > 100
                || hot > 100) throw new ConfigException("percentuais inválidos");
        long semente;
        try {
            String v = lookup.apply("SEMENTE");
            semente = v == null ? 1L : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("SEMENTE inválida");
        }
        return new ConfigGerador(taxa, duracao, threads, txMax, mix, insert, delete, hot, rajada, aberta, semente);
    }

    private static int numero(Function<String, String> lookup, String chave, int padrao, int minimo) {
        String valor = lookup.apply(chave);
        try {
            int n = valor == null ? padrao : Integer.parseInt(valor.trim());
            if (n < minimo) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new ConfigException(chave + " inválido");
        }
    }
}
