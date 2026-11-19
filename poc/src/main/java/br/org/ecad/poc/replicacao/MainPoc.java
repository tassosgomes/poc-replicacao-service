package br.org.ecad.poc.replicacao;

import br.org.ecad.poc.replicacao.bootstrap.Main;
import br.org.ecad.poc.replicacao.bootstrap.config.Config;
import br.org.ecad.poc.replicacao.bootstrap.config.ConfigException;
import br.org.ecad.poc.replicacao.carga.CargaInicial;
import br.org.ecad.poc.replicacao.carga.ConfigCarga;
import br.org.ecad.poc.replicacao.exportador.Exportador;
import br.org.ecad.poc.replicacao.gerador.ConfigGerador;
import br.org.ecad.poc.replicacao.gerador.Gerador;
import br.org.ecad.poc.replicacao.reconciliacao.ConfigReconciliacao;
import br.org.ecad.poc.replicacao.reconciliacao.Reconciliacao;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Entry point of the PoC jar. Dispatches the {@code carga-inicial}, {@code reconciliacao},
 * {@code gerador} and {@code exportador} subcommands, and keeps {@code worker} by delegating to the
 * worker module ({@link Main}), so scripts and scenarios run every role from one jar.
 *
 * <p>An unknown subcommand or an invalid {@link Config} prints a clear one-line message and exits
 * non-zero, never a bare stack trace.
 */
public final class MainPoc {

    private static final System.Logger LOG = System.getLogger(MainPoc.class.getName());

    static final List<String> COMMANDS = List.of("worker", "carga-inicial", "reconciliacao", "gerador", "exportador");

    private MainPoc() {}

    public static void main(String[] args) {
        if (args != null && args.length == 1 && args[0].equals("exportador")) {
            System.exit(Exportador.run(System::getenv));
            return;
        }
        Clock clock = Clock.systemUTC();
        Main.Result result = dispatch(args == null ? new String[0] : args, System.getenv(), clock);
        if (result.config() != null) {
            if (args[0].equals("carga-inicial")) {
                try {
                    new CargaInicial(
                                    result.config(),
                                    ConfigCarga.load(
                                            key -> Main.firstNonBlank(System.getProperty(key), System.getenv(key))),
                                    clock,
                                    new PrintStream(
                                            new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8))
                            .executar();
                    System.exit(0);
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.ERROR, "carga-inicial failed: " + e.getMessage(), e);
                    System.exit(1);
                }
            }
            if (args[0].equals("gerador")) {
                try {
                    new Gerador(
                                    result.config(),
                                    ConfigGerador.load(
                                            key -> Main.firstNonBlank(System.getProperty(key), System.getenv(key))),
                                    clock)
                            .executar();
                    System.exit(0);
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.ERROR, "gerador failed: " + e.getMessage(), e);
                    System.exit(1);
                }
            }
            if (args[0].equals("reconciliacao")) {
                try {
                    Reconciliacao.executar(
                            result.config(),
                            ConfigReconciliacao.load(
                                    key -> Main.firstNonBlank(System.getProperty(key), System.getenv(key))),
                            new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
                    System.exit(0);
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.ERROR, "reconciliacao failed: " + e.getMessage(), e);
                    System.exit(1);
                }
            }
            System.exit(Main.runWorker(result.config(), clock));
        }
        LOG.log(result.exitCode() == 0 ? System.Logger.Level.INFO : System.Logger.Level.ERROR, result.message());
        System.exit(result.exitCode());
    }

    static Main.Result dispatch(String[] args, Map<String, String> env, Clock clock) {
        return dispatch(args, key -> Main.firstNonBlank(System.getProperty(key), env.get(key)), clock);
    }

    static Main.Result dispatch(String[] args, Function<String, String> lookup, Clock clock) {
        if (args.length == 0) {
            return new Main.Result(2, "missing subcommand. " + usage());
        }
        String command = args[0];
        if (!COMMANDS.contains(command)) {
            return new Main.Result(2, "unknown subcommand '" + command + "'. " + usage());
        }
        if (command.equals("worker")) {
            return Main.dispatch(args, lookup, clock);
        }
        if (command.equals("exportador")) {
            return new Main.Result(args.length == 1 ? 0 : 2, usage());
        }
        Config config;
        try {
            config = Config.load(lookup, clock);
        } catch (ConfigException e) {
            return new Main.Result(1, e.getMessage());
        }
        if (command.equals("carga-inicial")) {
            try {
                ConfigCarga.load(lookup);
            } catch (ConfigException e) {
                return new Main.Result(1, e.getMessage());
            }
            return new Main.Result(0, "starting carga-inicial", config);
        }
        if (command.equals("gerador")) {
            try {
                ConfigGerador.load(lookup);
            } catch (ConfigException e) {
                return new Main.Result(1, e.getMessage());
            }
            return new Main.Result(0, "starting gerador", config);
        }
        try {
            ConfigReconciliacao.load(lookup);
        } catch (ConfigException e) {
            return new Main.Result(1, e.getMessage());
        }
        return new Main.Result(0, "starting reconciliacao", config);
    }

    static String usage() {
        return "usage: poc-replicacao-service <worker|carga-inicial|reconciliacao|gerador|exportador>";
    }
}
