package br.org.ecad.poc.replicacao.oracle;

import br.org.ecad.poc.replicacao.gerador.Gerador;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the {@code gerador} subcommand where the PoC defines it: in a throwaway {@code poc-gerador-*}
 * container with a 1 GB memory limit on desenv-server, next to the legacy database (DP-06). The
 * compiled classes and the JDBC driver are copied into the container with {@code docker cp}, so
 * nothing is installed on the host; the container is always removed at the end.
 */
final class GeradorRemoto {
    static final String HOST = "desenv-server";
    static final long LIMITE_MEMORIA = 1024L * 1024 * 1024;
    private static final String IMAGEM = "eclipse-temurin:25-jre";
    private static final Pattern TAXA = Pattern.compile("taxa=([0-9.]+) alvo=");
    private static final Pattern LOG = Pattern.compile("log_linhas=(gerador-linhas-[A-Za-z0-9._-]+\\.csv)");
    private static final Pattern LINHAS = Pattern.compile("linhas=(\\d+) transacoes=");
    private static final Pattern TRANSACOES = Pattern.compile("transacoes=(\\d+)");

    record Resultado(int exitCode, String saida, long memoriaContainer, Path log) {
        double taxa() {
            Matcher m = TAXA.matcher(saida);
            if (!m.find()) throw new IllegalStateException("resumo do gerador ausente:\n" + saida);
            return Double.parseDouble(m.group(1));
        }

        long linhas() {
            Matcher m = LINHAS.matcher(saida);
            if (!m.find()) throw new IllegalStateException("resumo do gerador ausente:\n" + saida);
            return Long.parseLong(m.group(1));
        }

        long transacoes() {
            Matcher m = TRANSACOES.matcher(saida);
            if (!m.find()) throw new IllegalStateException("resumo do gerador ausente:\n" + saida);
            return Long.parseLong(m.group(1));
        }

        boolean valido() {
            return saida.lines().anyMatch(l -> l.contains("transacao_media_ms") && l.endsWith(" VALIDO"));
        }

        boolean invalido() {
            return saida.lines().anyMatch(l -> l.contains("transacao_media_ms") && l.endsWith(" INVALIDO"));
        }
    }

    private GeradorRemoto() {}

    /** Runs the generator; its own {@code gerador-linhas-*.csv} is copied to {@code destino}. */
    static Resultado executar(Map<String, String> parametros, Path destino) throws Exception {
        String nome = "poc-gerador-" + Long.toHexString(System.nanoTime());
        try {
            List<String> criar = new ArrayList<>(List.of(
                    "docker create --name",
                    nome,
                    "--network poc-rede --memory=1g --memory-swap=1g --cpus=2 -e RESULTADOS_DIR=/resultados",
                    "-e LEGADO_URL=jdbc:oracle:thin:@//poc-legado:1521/FREEPDB1"));
            parametros.forEach((k, v) -> criar.add("-e " + aspas(k + "=" + v)));
            criar.add(IMAGEM + " java -Xmx512m -cp '/app/classes:/app/*' br.org.ecad.poc.replicacao.Main gerador");
            exigir(ssh(String.join(" ", criar)), "docker create");
            copiarParaContainer(nome);
            long memoria = Long.parseLong(
                    exigir(ssh("docker inspect --format '{{.HostConfig.Memory}}' " + nome), "docker inspect")
                            .strip());
            Saida execucao = ssh("docker start -a " + nome);
            Files.createDirectories(destino);
            Path csv = null;
            if (execucao.codigo == 0) {
                Matcher nomeLog = LOG.matcher(execucao.texto);
                if (!nomeLog.find()) throw new IllegalStateException("log_linhas ausente:\n" + execucao.texto);
                csv = destino.resolve(nomeLog.group(1));
                Process p = new ProcessBuilder(
                                "bash",
                                "-c",
                                "ssh -o BatchMode=yes " + HOST + " 'docker cp " + nome + ":/resultados/"
                                        + nomeLog.group(1) + " -' | tar -x -C '" + destino + "'")
                        .redirectErrorStream(true)
                        .start();
                p.getInputStream().readAllBytes();
                if (p.waitFor() != 0 || !Files.exists(csv))
                    throw new IllegalStateException("não foi possível copiar " + csv.getFileName());
            }
            return new Resultado(execucao.codigo, execucao.texto, memoria, csv);
        } finally {
            ssh("docker rm -f " + nome);
        }
    }

    private static void copiarParaContainer(String nome) throws Exception {
        Path classes = Path.of(Gerador.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Path driver = Path.of(oracle.jdbc.OracleDriver.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        Process p = new ProcessBuilder(
                        "bash",
                        "-c",
                        "set -o pipefail; tar -c --transform 's|^|app/|' -C '" + classes.getParent()
                                + "' classes -C '" + driver.getParent() + "' '" + driver.getFileName()
                                + "' | ssh -o BatchMode=yes " + HOST + " 'docker cp - " + nome + ":/'")
                .redirectErrorStream(true)
                .start();
        String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException("docker cp falhou: " + saida);
    }

    private record Saida(int codigo, String texto) {}

    private static String exigir(Saida s, String comando) {
        if (s.codigo != 0) throw new IllegalStateException(comando + " falhou: " + s.texto);
        return s.texto;
    }

    private static Saida ssh(String comando) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("ssh", "-o", "BatchMode=yes", HOST, comando)
                .redirectErrorStream(true)
                .start();
        String texto;
        try (InputStream in = p.getInputStream()) {
            texto = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        if (!p.waitFor(30, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("timeout: " + comando);
        }
        return new Saida(p.exitValue(), texto);
    }

    private static String aspas(String valor) {
        return "'" + valor.replace("'", "'\\''") + "'";
    }
}
