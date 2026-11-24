package br.org.ecad.poc.replicacao.bootstrap.kafka;

import br.org.ecad.poc.replicacao.bootstrap.kafka.config.Config;
import br.org.ecad.poc.replicacao.bootstrap.kafka.config.ConfigException;
import br.org.ecad.poc.replicacao.bootstrap.kafka.execucao.Worker;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Entry point of the Kafka alternative: single {@code worker} subcommand, like the lab Java. An unknown
 * subcommand or an invalid {@link Config} prints one line and exits non-zero.
 */
public final class Main {

    private static final System.Logger LOG = System.getLogger(Main.class.getName());

    static final List<String> COMMANDS = List.of("worker");

    /** Strong reference: java.util.logging keeps loggers weakly and would drop the level. */
    private static final java.util.logging.Logger KAFKA_LOG = java.util.logging.Logger.getLogger("org.apache.kafka");

    private Main() {}

    public static void main(String[] args) {
        Clock clock = Clock.systemUTC();
        // The Kafka client logs every consumer open/close at INFO (one per partition and lease change).
        KAFKA_LOG.setLevel(java.util.logging.Level.WARNING);
        Result result = dispatch(args == null ? new String[0] : args, System.getenv(), clock);
        if (result.config() != null) {
            System.exit(runWorker(result.config(), clock));
        }
        LOG.log(result.exitCode() == 0 ? System.Logger.Level.INFO : System.Logger.Level.ERROR, result.message());
        System.exit(result.exitCode());
    }

    /** Outcome of a dispatch: process exit code plus the single message to log. */
    public record Result(int exitCode, String message, Config config) {

        public Result(int exitCode, String message) {
            this(exitCode, message, null);
        }
    }

    /** Runs the worker until the process is terminated; the shutdown hook stops it in order. */
    public static int runWorker(Config config, Clock clock) {
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

    public static Result dispatch(String[] args, Map<String, String> env, Clock clock) {
        return dispatch(args, key -> firstNonBlank(System.getProperty(key), env.get(key)), env, clock);
    }

    public static Result dispatch(
            String[] args, Function<String, String> lookup, Map<String, String> env, Clock clock) {
        if (args.length == 0) {
            return new Result(2, "missing subcommand. " + usage());
        }
        String command = args[0];
        if (!COMMANDS.contains(command)) {
            return new Result(2, "unknown subcommand '" + command + "'. " + usage());
        }
        Config config;
        try {
            config = Config.load(lookup, env, clock);
        } catch (ConfigException e) {
            return new Result(1, e.getMessage());
        }
        return new Result(0, "starting kafka worker " + config.workerId(), config);
    }

    static String usage() {
        return "usage: replicacao-worker-kafka worker";
    }

    static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
