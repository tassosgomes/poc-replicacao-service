package br.org.ecad.poc.replicacao.oracle;

import br.org.ecad.poc.replicacao.Config;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Connections to the real PoC databases for the integration tests. When {@code LEGADO_URL} or
 * {@code NOVO_URL} are not defined, the endpoints of the {@code poc-legado} and {@code poc-novo}
 * containers are resolved from the ssh configuration of their hosts ({@code ssh -G}, the same
 * resolution as {@code scripts/testes/env-v03.sh}); credentials default to the throwaway PoC ones.
 * Nothing is read from {@code .env}.
 */
public final class BancosPoc {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final String LEGADO_HOST = "desenv-server";
    private static final String NOVO_HOST = "infra-server";
    private static final Config CONFIG = Config.load(lookup(), CLOCK);

    private BancosPoc() {}

    public static Config config() {
        return CONFIG;
    }

    public static Connection legado() {
        return Conexoes.abrir(CONFIG.legadoUrl(), CONFIG.legadoUsuario(), CONFIG.legadoSenha());
    }

    public static Connection novo() {
        return Conexoes.abrir(CONFIG.novoUrl(), CONFIG.novoUsuario(), CONFIG.novoSenha());
    }

    private static Function<String, String> lookup() {
        return key -> {
            String value = System.getProperty(key, System.getenv(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
            return switch (key) {
                case "LEGADO_URL" -> url(LEGADO_HOST, 1521);
                case "NOVO_URL" -> url(NOVO_HOST, 1522);
                default -> null;
            };
        };
    }

    private static String url(String sshHost, int porta) {
        return "jdbc:oracle:thin:@//" + enderecoDe(sshHost) + ":" + porta + "/FREEPDB1";
    }

    private static String enderecoDe(String sshHost) {
        try {
            Process p = new ProcessBuilder(List.of("ssh", "-G", sshHost))
                    .redirectErrorStream(false)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(10, TimeUnit.SECONDS);
            return saida.lines()
                    .filter(l -> l.startsWith("hostname "))
                    .map(l -> l.substring("hostname ".length()).trim())
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("ssh -G " + sshHost + " gave no hostname"));
        } catch (IOException e) {
            throw new IllegalStateException("cannot resolve " + sshHost + " with ssh -G", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted resolving " + sshHost, e);
        }
    }
}
