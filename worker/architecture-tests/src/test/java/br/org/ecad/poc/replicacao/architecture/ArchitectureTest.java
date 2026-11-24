package br.org.ecad.poc.replicacao.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "br.org.ecad.poc.replicacao", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule domain_isIndependent = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application..", "..infra..", "..bootstrap..", "java.sql..", "javax.sql..");

    @ArchTest
    static final ArchRule application_usesPorts = noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..infra..", "..bootstrap..", "java.sql..", "javax.sql..");

    @ArchTest
    static final ArchRule infrastructure_doesNotDependOnBootstrap = noClasses()
            .that()
            .resideInAPackage("..infra..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..bootstrap..");

    @ArchTest
    static final ArchRule infrastructure_doesNotCallUseCases = noClasses()
            .that()
            .resideInAPackage("..infra..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application.replicacao..", "..application.coordenacao..");

    @ArchTest
    static final ArchRule worker_doesNotDependOnPoc = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..carga..", "..gerador..", "..exportador..", "..reconciliacao..");

    // Kafka alternative (infra-kafka, bootstrap-kafka). The rules above already apply to its packages
    // (..infra.kafka.. is an ..infra.. package, ..bootstrap.kafka.. a ..bootstrap.. one); these add what is
    // specific to it.

    @ArchTest
    static final ArchRule coreDoesNotKnowKafkaOrJson = noClasses()
            .that()
            .resideInAnyPackage("..domain..", "..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.apache.kafka..", "tools.jackson..", "com.fasterxml.jackson..");

    @ArchTest
    static final ArchRule infraKafka_doesNotKnowOracle = noClasses()
            .that()
            .resideInAPackage("..infra.kafka..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..infra.oracle..", "java.sql..", "javax.sql..", "oracle..");

    @ArchTest
    static final ArchRule infraOracle_doesNotKnowKafka = noClasses()
            .that()
            .resideInAPackage("..infra.oracle..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..infra.kafka..", "org.apache.kafka..");

    @ArchTest
    static final ArchRule kafkaWorker_hasNoLegacyConnection = noClasses()
            .that()
            .resideInAPackage("..bootstrap.kafka..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("br.org.ecad.poc.replicacao.infra.oracle.replicacao.OutboxSource")
            .orShould()
            .dependOnClassesThat()
            .haveFullyQualifiedName("br.org.ecad.poc.replicacao.infra.oracle.replicacao.StateProviderAdapter")
            .orShould()
            .dependOnClassesThat()
            .resideInAPackage("br.org.ecad.poc.replicacao.bootstrap.execucao..");

    // Phase 4: the two processors are alternatives chosen by the bootstrap; neither knows the other.
    @ArchTest
    static final ArchRule processors_areIndependentAlternatives = noClasses()
            .that()
            .haveSimpleName("ProcessadorParticao")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("ProcessadorTransacional")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleName("FonteTransacional");

    @ArchTest
    static final ArchRule transactionalProcessor_doesNotUseThePartitionPath = noClasses()
            .that()
            .haveSimpleName("ProcessadorTransacional")
            .should()
            .dependOnClassesThat()
            .haveSimpleName("ProcessadorParticao")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleName("ChangeSource")
            .orShould()
            .dependOnClassesThat()
            .haveSimpleName("StateProvider");
}
