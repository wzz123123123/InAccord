package com.inforvans.accord.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

class NoSecurityServiceCouplingTest {
    private static final DescribedPredicate<JavaClass> FORBIDDEN_DEPENDENCIES =
        JavaClass.Predicates.resideInAnyPackage(
            "..securityservices..",
            "..signing..",
            "..gitprovider..",
            "..gitcontent..",
            "..providerconnector..",
            "..credentialbroker..",
            "..mergecontroller..",
            "org.eclipse.jgit..",
            "org.gitlab4j..",
            "org.kohsuke.github..");

    private final JavaClasses classes = new ClassFileImporter()
        .importPackages("com.inforvans.accord");

    @Test
    void controlPlaneCannotDependOnSecurityDeploymentsOrGitContentClients() {
        assertThat(classes.stream()
            .anyMatch(type -> type.getPackageName().startsWith("com.inforvans.accord.platformkernel")))
            .as("platform-kernel classes must be imported")
            .isTrue();
        assertThat(classes.stream()
            .anyMatch(type -> type.getPackageName().startsWith("com.inforvans.accord.reliability")))
            .as("reliability classes must be imported")
            .isTrue();

        noClasses().that().resideInAPackage("com.inforvans.accord..")
            .should().dependOnClassesThat(FORBIDDEN_DEPENDENCIES)
            .check(classes);
    }

    @Test
    void forbiddenPredicateMatchesRealSecurityAndGitClientNamespaces() throws Exception {
        Class<?>[] fixtures = {
            Class.forName("com.inforvans.accord.signing.SigningBoundaryFixture"),
            Class.forName("com.inforvans.accord.gitprovider.GitProviderBoundaryFixture"),
            Class.forName("org.eclipse.jgit.JGitBoundaryFixture"),
            Class.forName("org.gitlab4j.api.GitLabBoundaryFixture"),
            Class.forName("org.kohsuke.github.GitHubBoundaryFixture")
        };
        JavaClasses fixtureClasses = new ClassFileImporter().importClasses(fixtures);

        assertThat(fixtureClasses).allSatisfy(type ->
            assertThat(FORBIDDEN_DEPENDENCIES.test(type))
                .as(type.getName())
                .isTrue());
    }
}
