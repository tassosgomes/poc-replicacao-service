package br.org.ecad.poc.replicacao.exportador;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Optional laptop process. It owns only readers and HTTP clients, never scenario processes/files. */
public final class Exportador {
    private Exportador() {}

    public static int run(Function<String, String> environment) {
        try {
            ConfigExportador config = ConfigExportador.laptop(Path.of(".env"));
            String action = environment.apply("EXPORTADOR_ACAO");
            String execution = environment.apply("EXPORTADOR_EXECUCAO");
            try (ElasticKibana client = new ElasticKibana(config, ConfigExportador.SPACE)) {
                client.preflight();
                if ("limpar".equals(action)) {
                    client.cleanup(ConfigExportador.index(execution));
                    return 0;
                }
                if ("painel".equals(action)) {
                    client.panel();
                    return 0;
                }
                if (action != null && !action.equals("seguir")) throw ElasticKibana.failure();
                String index = ConfigExportador.index(execution);
                String scenario = environment.apply("EXPORTADOR_CENARIO");
                String sources = environment.apply("EXPORTADOR_FONTES");
                if (sources == null || sources.isBlank()) throw ElasticKibana.failure();
                List<Source> inputs = sources.lines().map(Source::parse).toList();
                for (Source input : inputs) new CsvSample.ExportContext(scenario, execution, input.id());
                client.ensureIndex(index);
                client.panel();
                follow(client, new FollowInput(index, scenario, execution, inputs));
            }
            return 0;
        } catch (RuntimeException e) {
            System.getLogger(Exportador.class.getName())
                    .log(System.Logger.Level.ERROR, "PoC exporter stopped; scenario and CSV evidence are unaffected");
            return 1;
        }
    }

    private static void follow(ElasticKibana client, FollowInput input) {
        List<Process> processes = new ArrayList<>();
        Thread hook = new Thread(() -> stop(processes), "poc-exporter-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var completed = new ExecutorCompletionService<Void>(executor);
            try {
                for (Source source : input.sources()) {
                    ProcessBuilder builder =
                            new ProcessBuilder(source.command()).redirectError(ProcessBuilder.Redirect.DISCARD);
                    builder.environment()
                            .keySet()
                            .removeIf(key -> key.startsWith("ELASTIC_") || key.startsWith("KIBANA_"));
                    Process process = builder.start();
                    synchronized (processes) {
                        processes.add(process);
                    }
                    completed.submit(() -> {
                        CsvSample parser = new CsvSample();
                        var context = new CsvSample.ExportContext(input.scenario(), input.execution(), source.id());
                        try (var reader = new BufferedReader(
                                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                Map<String, Object> document = parser.parse(line, context);
                                if (!document.isEmpty())
                                    client.write(input.index(), CsvSample.id(context, line), document);
                            }
                        }
                        throw ElasticKibana.failure();
                    });
                }
                completed.take().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ElasticKibana.failure();
            } catch (Exception e) {
                throw ElasticKibana.failure();
            } finally {
                stop(processes);
                executor.shutdownNow();
            }
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException ignored) {
                /* JVM shutdown already owns the hook. */
            }
        }
    }

    private static void stop(List<Process> processes) {
        synchronized (processes) {
            for (Process process : processes) {
                process.destroy();
                try {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor();
                    }
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private record FollowInput(String index, String scenario, String execution, List<Source> sources) {}

    record Source(String id, List<String> command) {
        static Source parse(String specification) {
            String[] parts = specification.split(":", -1);
            if (parts.length < 2 || parts.length > 3) throw ElasticKibana.failure();
            String file = parts[parts.length - 1];
            if (!file.matches("[/A-Za-z0-9_.-]+\\.csv") || file.contains("..") || file.startsWith("-"))
                throw ElasticKibana.failure();
            if (parts[0].equals("local") && parts.length == 2)
                return new Source(specification, List.of("tail", "-s", "1", "-n", "+1", "-F", "--", file));
            if (!List.of("desenv-server", "infra-server").contains(parts[0])) throw ElasticKibana.failure();
            List<String> command = new ArrayList<>(
                    List.of("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-o", "SendEnv=-*", parts[0]));
            if (parts.length == 3) {
                if (!parts[1].matches("poc-[A-Za-z0-9_-]+") || !file.startsWith("/resultados/"))
                    throw ElasticKibana.failure();
                command.add("docker exec " + parts[1] + " tail -s 1 -n +1 -F -- " + file);
            } else command.add("tail -s 1 -n +1 -F -- " + file);
            return new Source(specification, List.copyOf(command));
        }
    }
}
