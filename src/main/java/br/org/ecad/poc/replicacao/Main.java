package br.org.ecad.poc.replicacao;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Single executable entry point. Dispatches the {@code worker}, {@code carga-inicial},
 * {@code reconciliacao} and {@code gerador} subcommands.
 *
 * <p>EN-01 carries no replication behavior yet: a known subcommand with valid configuration
 * prints usage and exits non-zero. An unknown subcommand or an invalid {@link Config} prints a
 * clear one-line message and exits non-zero, never a bare stack trace.
 */
public final class Main {

    private static final System.Logger LOG = System.getLogger(Main.class.getName());

    static final List<String> COMMANDS = List.of("worker", "carga-inicial", "reconciliacao", "gerador");

    private Main() {}

    public static void main(String[] args) {
        Result result = dispatch(args == null ? new String[0] : args, System.getenv(), Clock.systemUTC());
        LOG.log(result.exitCode() == 0 ? System.Logger.Level.INFO : System.Logger.Level.ERROR, result.message());
        System.exit(result.exitCode());
    }

    /** Outcome of a dispatch: process exit code plus the single message to log. */
    record Result(int exitCode, String message) {}

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
        try {
            Config.load(lookup, clock);
        } catch (ConfigException e) {
            return new Result(1, e.getMessage());
        }
        return new Result(2, "'" + command + "' not implemented yet. " + usage());
    }

    static String usage() {
        return "usage: poc-replicacao-service <worker|carga-inicial|reconciliacao|gerador>";
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
