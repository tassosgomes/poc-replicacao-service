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
}
