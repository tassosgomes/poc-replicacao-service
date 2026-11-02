package br.org.ecad.poc.replicacao;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The production worker module never references PoC code. Compilation already forbids direct use
 * (the poc module is not on this module's classpath and the enforcer bans it as a dependency); this
 * test also catches indirect references by name, such as {@code Class.forName} or a hard-coded
 * main class, in main and test sources.
 */
class IsolamentoWorkerTest {

    private static final String RAIZ = "br.org.ecad.poc.replicacao";
    private static final Pattern PACOTE_POC =
            Pattern.compile(Pattern.quote(RAIZ) + "[./](carga|gerador|exportador|reconciliacao)\\b|\\bMainPoc\\b");
    private static final Pattern CAMINHO_POC =
            Pattern.compile("br/org/ecad/poc/replicacao/(carga|gerador|exportador|reconciliacao)/");

    @Test
    void workerSources_neverReferencePocPackages() throws IOException {
        List<Path> fontes = fontesDoModulo();

        assertThat(fontes).as("worker sources found").isNotEmpty();
        List<String> violacoes = fontes.stream()
                .filter(f -> !f.getFileName().toString().equals("IsolamentoWorkerTest.java"))
                .filter(f ->
                        CAMINHO_POC.matcher(f.toString().replace('\\', '/')).find()
                                || PACOTE_POC.matcher(ler(f)).find())
                .map(Path::toString)
                .toList();
        assertThat(violacoes).as("worker sources referencing PoC code").isEmpty();
    }

    @Test
    void workerPom_neverDependsOnThePocModule() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        assertThat(pom).contains("<artifactId>replicacao-worker</artifactId>");
        assertThat(pom.replace("<exclude>br.org.ecad:replicacao-poc</exclude>", ""))
                .doesNotContain("replicacao-poc");
    }

    private static List<Path> fontesDoModulo() throws IOException {
        try (Stream<Path> main = Files.walk(Path.of("src", "main"));
                Stream<Path> test = Files.walk(Path.of("src", "test"))) {
            return Stream.concat(main, test).filter(Files::isRegularFile).toList();
        }
    }

    private static String ler(Path arquivo) {
        try {
            return Files.readString(arquivo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
