package br.org.ecad.poc.replicacao;

import br.org.ecad.poc.replicacao.worker.Worker;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Entry point of the production replication service. Dispatches the single {@code worker}
 * subcommand.
 *
 * <p>An unknown or missing subcommand, or an invalid {@link Config}, prints a clear one-line message
 * and exits non-zero, never a bare stack trace. The PoC tools (carga-inicial, reconciliacao, gerador,
 * exportador) live in the {@code poc} module, behind their own entry point.
 */
public final class Main {

    private static final System.Logger LOG = System.getLogger(Main.class.getName());

    static final List<String> COMMANDS = List.of("worker");

    private Main() {}

    public static void main(String[] args) {
        Clock clock = Clock.systemUTC();
        Result result = dispatch(args == null ? new String[0] : args, System.getenv(), clock);
        if (result.config() != null) {
            System.exit(runWorker(result.config(), clock));
        }
        LOG.log(result.exitCode() == 0 ? System.Logger.Level.INFO : System.Logger.Level.ERROR, result.message());
        System.exit(result.exitCode());
    }

    /** Outcome of a dispatch: process exit code plus the single message to log. */
    record Result(int exitCode, String message, Config config) {

        Result(int exitCode, String message) {
            this(exitCode, message, null);
        }
    }

    /** Runs the worker until the process is terminated; the shutdown hook stops it in order. */
    static int runWorker(Config config, Clock clock) {
        try (Worker worker = Worker.iniciar(config, clock)) {
            Runtime.getRuntime().addShutdownHook(new Thread(worker::close));
            worker.aguardar();
            return 0;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "worker failed: " + e.getMessage(), e);
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    static Result dispatch(String[] args, Map<String, String> env, Clock clock) {
        return dispatch(args, key -> firstNonBlank(System.getProperty(key), env.get(key)), clock);
    }

    static Result dispatch(String[] args, Function<String, String> lookup, Clock clock) {
        if (args.length == 0) {
            return new Result(2, "missing subcommand. " + usage());
        }
        String command = args[0];
        if (!COMMANDS.contains(command)) {
            return new Result(2, "unknown subcommand '" + command + "'. " + usage());
        }
        Config config;
        try {
            config = Config.load(lookup, clock);
        } catch (ConfigException e) {
            return new Result(1, e.getMessage());
        }
        return new Result(0, "starting worker " + config.workerId(), config);
    }

    static String usage() {
        return "usage: poc-replicacao-service worker";
    }

    static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
