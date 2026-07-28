package com.inforvans.accord.webhookedge;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.inforvans.accord.webhookedge.binding.BindingResolver;
import com.inforvans.accord.webhookedge.inbox.PostgresWebhookInbox;
import com.inforvans.accord.webhookedge.inbox.WebhookDatabaseFixture;
import com.inforvans.accord.webhookedge.security.GitLabWebhookVerifier;
import com.inforvans.accord.webhookedge.webhook.WebhookInbox;
import com.inforvans.accord.webhookedge.webhook.WebhookHandler;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

class WebhookEdgeBoundaryTest {
    private static final int PACKAGED_OUTPUT_TAIL_BYTES = 65_536;
    private static final String APPLICATION_CLASSES_ROOT = "BOOT-INF/classes/";
    private static final String APPLICATION_PACKAGE_ROOT =
            "BOOT-INF/classes/com/inforvans/accord/webhookedge/";
    private static final String APPLICATION_CONFIGURATION = "BOOT-INF/classes/application.yml";
    private static final String EXPECTED_START_CLASS =
            "com.inforvans.accord.webhookedge.WebhookEdgeApplication";
    private static final String EXPECTED_MAIN_CLASS =
            "org.springframework.boot.loader.launch.JarLauncher";
    private static final String LOADER_SERVICE =
            "META-INF/services/java.nio.file.spi.FileSystemProvider";
    private static final String TRUSTED_OBSERVABILITY_PROJECT = ":libs:java:observability";
    private static final String ACCORD_PACKAGE_ROOT = "com/inforvans/accord/";
    private static final String OBSERVABILITY_PACKAGE_ROOT =
            "com/inforvans/accord/observability/";
    private static final byte[] LOADER_SERVICE_CONTENTS =
            "org.springframework.boot.loader.nio.file.NestedFileSystemProvider\n"
                    .getBytes(StandardCharsets.UTF_8);
    private static final List<String> FORBIDDEN_MANIFEST_ATTRIBUTES = List.of(
            "Class-Path", "Premain-Class", "Agent-Class", "Launcher-Agent-Class");
    private static final Pattern JAVA_IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+(?:static\\s+)?([^;]+);");
    private static final List<String> FORBIDDEN_IMPORT_PREFIXES = List.of(
            "com.inforvans.accord.controlplane.",
            "com.inforvans.accord.reliability.",
            "com.inforvans.accord.database.",
            "org.eclipse.jgit.",
            "org.gitlab4j.",
            "org.kohsuke.github.");
    private static final List<String> FORBIDDEN_OUTER_PACKAGE_PATHS = List.of(
            "com/inforvans/accord/controlplane/",
            "com/inforvans/accord/reliability/",
            "com/inforvans/accord/database/",
            "org/eclipse/jgit/",
            "org/gitlab4j/",
            "org/kohsuke/github/");
    private static final List<String> FORBIDDEN_NESTED_PACKAGE_PATHS = List.of(
            "com/inforvans/accord/controlplane/",
            "com/inforvans/accord/reliability/",
            "com/inforvans/accord/database/",
            "org/eclipse/jgit/",
            "org/gitlab4j/",
            "org/kohsuke/github/");
    private static final List<String> FORBIDDEN_OUTER_FRAGMENTS = List.of(
            "control-plane",
            "platform-kernel",
            "reliability-",
            "jgit-",
            "gitlab4j-",
            "github-api-",
            "raw-body",
            "raw_body");
    private static final List<String> FORBIDDEN_ARTIFACT_FRAGMENTS = List.of(
            "apps/control-plane",
            "database/control-plane",
            "platform-kernel",
            "modules/reliability",
            "com.inforvans.accord.controlplane",
            "com.inforvans.accord.reliability",
            "com.inforvans.accord.database",
            "org.eclipse.jgit",
            "gitlab4j",
            "github-api");

    @Test
    void applicationAndHandlerExposeOnlyTheFrozenRawServletEndpoint() throws Exception {
        assertThat(WebhookEdgeApplication.class).hasAnnotation(SpringBootApplication.class);
        assertThat(WebhookHandler.class).hasAnnotation(RestController.class);
        Method receive = WebhookHandler.class.getDeclaredMethod(
                "receive", String.class, HttpServletRequest.class, HttpServletResponse.class);
        PostMapping mapping = receive.getAnnotation(PostMapping.class);
        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly("/webhooks/gitlab/{bindingId}");
        assertThat(mapping.consumes()).isEmpty();
        assertThat(Arrays.stream(receive.getParameterAnnotations())
                        .flatMap(Arrays::stream)
                        .map(Annotation::annotationType))
                .doesNotContain(RequestBody.class, RequestHeader.class);
    }

    @Test
    void productionClassesStayInsideTheIsolatedEdgePackageAndDependencies() {
        var imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.inforvans.accord.webhookedge");
        classes().should()
                .resideInAPackage("com.inforvans.accord.webhookedge..")
                .check(imported);
        noClasses().should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "com.inforvans.accord.controlplane..",
                        "com.inforvans.accord.reliability..",
                        "com.inforvans.accord.database..",
                        "org.eclipse.jgit..",
                        "org.gitlab4j..",
                        "org.kohsuke.github..")
                .check(imported);
    }

    @Test
    void mainAndTestSourcesContainNoForbiddenImports() throws Exception {
        Path root = repositoryRoot().resolve("apps/webhook-edge/src");
        Set<String> imports = new LinkedHashSet<>();
        for (Path sourceRoot : List.of(root.resolve("main/java"), root.resolve("test/java"))) {
            assertThat(sourceRoot).isDirectory();
            try (var paths = Files.walk(sourceRoot)) {
                for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                    Matcher matcher = JAVA_IMPORT.matcher(Files.readString(source, StandardCharsets.UTF_8));
                    while (matcher.find()) {
                        imports.add(matcher.group(1));
                    }
                }
            }
        }

        assertThat(imports)
                .isNotEmpty()
                .noneMatch(imported -> FORBIDDEN_IMPORT_PREFIXES.stream().anyMatch(imported::startsWith));
    }

    @Test
    void resolvedRuntimeCoordinatesContainNoForbiddenComponents() {
        String serialized = System.getProperty("accord.webhook-edge.runtime-coordinates");

        assertThat(serialized).isNotBlank();
        List<String> coordinates = serialized.lines().filter(value -> !value.isBlank()).toList();
        assertThat(coordinates)
                .isNotEmpty()
                .anyMatch(value -> value.contains("org.springframework.boot:spring-boot"))
                .anyMatch(value -> value.contains("org.postgresql:postgresql"))
                .anyMatch(value -> value.startsWith(
                        "PROJECT|" + TRUSTED_OBSERVABILITY_PROJECT + "|"))
                .noneMatch(value -> FORBIDDEN_ARTIFACT_FRAGMENTS.stream().anyMatch(value::contains));
    }

    @Test
    void bootJarContainsOnlyTheIsolatedEdgeArtifact() throws Exception {
        assertBootJarBoundary(bootJar());
    }

    @Test
    void bootJarGateRejectsForbiddenClassInsideNeutrallyNamedNestedJar(@TempDir Path temporary)
            throws Exception {
        Map<String, byte[]> entries = validSyntheticBootJarEntries();
        entries.put(
                "BOOT-INF/lib/library-1.0.jar",
                zipBytes(Map.of(
                        "com/inforvans/accord/ControlApiApplication.class", new byte[] {0})));
        Path artifact = temporary.resolve("neutral-nested-dependency.jar");
        writeZip(artifact, entries);

        assertThatThrownBy(() -> assertBootJarBoundary(artifact))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("com/inforvans/accord/ControlApiApplication.class");
    }

    @ParameterizedTest(name = "library={0}")
    @ValueSource(strings = {"library.BIN", "library.JAR", "library"})
    void bootJarGateScansEveryNestedLibraryRegardlessOfFilename(
            String libraryName, @TempDir Path temporary)
            throws Exception {
        byte[] forbiddenLibrary = zipBytes(Map.of(
                "com/inforvans/accord/ControlApiApplication.class", new byte[] {0}));
        Map<String, byte[]> entries = validSyntheticBootJarEntries();
        entries.put("BOOT-INF/lib/" + libraryName, forbiddenLibrary);
        Path artifact = temporary.resolve(libraryName + "-nested-dependency.jar");
        writeZip(artifact, entries);

        assertThatThrownBy(() -> assertBootJarBoundary(artifact))
                .as(libraryName)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("com/inforvans/accord/ControlApiApplication.class");
    }

    @Test
    void bootJarGateRejectsInvalidOrTruncatedNestedArchives(@TempDir Path temporary)
            throws Exception {
        byte[] validArchive = zipBytes(Map.of("safe/Library.class", new byte[] {0}));
        List<byte[]> invalidArchives = List.of(
                new byte[0],
                "not a zip".getBytes(StandardCharsets.US_ASCII),
                Arrays.copyOf(validArchive, validArchive.length - 12));
        List<Executable> assertions = new ArrayList<>();
        for (int index = 0; index < invalidArchives.size(); index++) {
            Map<String, byte[]> entries = validSyntheticBootJarEntries();
            entries.put("BOOT-INF/lib/library-" + index, invalidArchives.get(index));
            Path artifact = temporary.resolve("invalid-nested-" + index + ".jar");
            writeZip(artifact, entries);
            int fixtureIndex = index;
            assertions.add(() -> assertThatThrownBy(() -> assertBootJarBoundary(artifact))
                    .as("invalid nested archive %s", fixtureIndex)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("BOOT-INF/lib/library-" + fixtureIndex));
        }
        assertAll(assertions);
    }

    @Test
    void bootJarGateRejectsChangedTrustedObservabilityArtifact(@TempDir Path temporary)
            throws Exception {
        Map<String, byte[]> entries = validSyntheticBootJarEntries();
        String trustedLibrary = trustedObservabilityLibraries().keySet().iterator().next();
        entries.put(trustedLibrary, mutateFirstByte(entries.get(trustedLibrary)));
        Path artifact = temporary.resolve("changed-observability.jar");
        writeZip(artifact, entries);

        assertThatThrownBy(() -> assertBootJarBoundary(artifact))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("trusted observability library SHA-256 mismatch");
    }

    @Test
    void bootJarGateRequiresPackagedApplicationConfiguration(@TempDir Path temporary)
            throws Exception {
        Map<String, byte[]> entries = validSyntheticBootJarEntries();
        entries.remove("BOOT-INF/classes/application.yml");
        Path artifact = temporary.resolve("missing-application-yml.jar");
        writeZip(artifact, entries);

        assertThatThrownBy(() -> assertBootJarBoundary(artifact))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("BOOT-INF/classes/application.yml");
    }

    @Test
    void bootJarGateRequiresWebhookEdgeStartClass(@TempDir Path temporary) throws Exception {
        Map<String, byte[]> entries = validSyntheticBootJarEntries();
        entries.put(
                "META-INF/MANIFEST.MF",
                manifest("com.inforvans.accord.controlplane.ControlApiApplication"));
        Path artifact = temporary.resolve("wrong-start-class.jar");
        writeZip(artifact, entries);

        assertThatThrownBy(() -> assertBootJarBoundary(artifact))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Start-Class")
                .hasMessageContaining("com.inforvans.accord.webhookedge.WebhookEdgeApplication");
    }

    @Test
    void bootJarGateLocksLauncherAndRejectsAgentManifestAttributes(@TempDir Path temporary)
            throws Exception {
        List<Executable> assertions = new ArrayList<>();
        for (Map<String, String> manifestMutation : List.of(
                Map.of("Main-Class", "example.UntrustedLauncher"),
                Map.of("Class-Path", "external.jar"),
                Map.of("Premain-Class", "example.Agent"),
                Map.of("Agent-Class", "example.Agent"),
                Map.of("Launcher-Agent-Class", "example.Agent"))) {
            Map<String, byte[]> entries = validSyntheticBootJarEntries();
            entries.put("META-INF/MANIFEST.MF", manifest(EXPECTED_START_CLASS, manifestMutation));
            Path artifact = temporary.resolve(
                    "manifest-" + manifestMutation.keySet().iterator().next() + ".jar");
            writeZip(artifact, entries);
            assertions.add(() -> assertThatThrownBy(() -> assertBootJarBoundary(artifact))
                    .as(manifestMutation.toString())
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining(manifestMutation.keySet().iterator().next()));
        }
        assertAll(assertions);
    }

    @ParameterizedTest(name = "class={0}")
    @ValueSource(strings = {
        "Agent.class",
        "org/springframework/boot/loader/InjectedAgent.class",
        "BOOT-INF/classes/com/inforvans/accord/webhookedge/Injected.class"
    })
    void bootJarGateRejectsRootAgentAndUncompiledApplicationClasses(
            String injectedClass, @TempDir Path temporary)
            throws Exception {
        Map<String, byte[]> entries = validSyntheticBootJarEntries();
        entries.put(injectedClass, new byte[] {0});
        Path artifact = temporary.resolve(injectedClass.replace('/', '-') + ".jar");
        writeZip(artifact, entries);

        assertThatThrownBy(() -> assertBootJarBoundary(artifact))
                .as(injectedClass)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(injectedClass);
    }

    @Test
    void bootJarGateRejectsSameNameApplicationResourceAndLoaderByteMutations(
            @TempDir Path temporary) throws Exception {
        Map<String, byte[]> canonical = validSyntheticBootJarEntries();
        String applicationClass = expectedApplicationEntries().keySet().stream()
                .filter(name -> name.endsWith(".class"))
                .findFirst()
                .orElseThrow();
        String loaderClass = loaderReferenceContents().keySet().stream()
                .filter(name -> name.endsWith(".class"))
                .sorted()
                .findFirst()
                .orElseThrow();
        List<String> targets = List.of(
                applicationClass, APPLICATION_CONFIGURATION, loaderClass);
        List<Executable> assertions = new ArrayList<>();
        for (int index = 0; index < targets.size(); index++) {
            String target = targets.get(index);
            Map<String, byte[]> entries = new LinkedHashMap<>(canonical);
            entries.put(target, mutateFirstByte(entries.get(target)));
            Path artifact = temporary.resolve("same-name-byte-mutation-" + index + ".jar");
            writeZip(artifact, entries);
            assertions.add(() -> assertThatThrownBy(() -> assertBootJarBoundary(artifact))
                    .as(target)
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("SHA-256 mismatch")
                    .hasMessageContaining(target));
        }
        assertAll(assertions);
    }

    @Test
    void bootJarGateRejectsSameNameLoaderServiceByteMutation(@TempDir Path temporary)
            throws Exception {
        Map<String, byte[]> entries = validSyntheticBootJarEntries();
        entries.put(LOADER_SERVICE, mutateFirstByte(entries.get(LOADER_SERVICE)));
        Path artifact = temporary.resolve("loader-service-byte-mutation.jar");
        writeZip(artifact, entries);

        assertThatThrownBy(() -> assertBootJarBoundary(artifact))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SHA-256 mismatch")
                .hasMessageContaining(LOADER_SERVICE);
    }

    @Test
    void bootJarGateRequiresLoaderService(@TempDir Path temporary) throws Exception {
        Map<String, byte[]> entries = validSyntheticBootJarEntries();
        entries.remove(LOADER_SERVICE);
        Path artifact = temporary.resolve("missing-loader-service.jar");
        writeZip(artifact, entries);

        assertThatThrownBy(() -> assertBootJarBoundary(artifact))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(LOADER_SERVICE);
    }

    @Test
    void bootJarGateRejectsInjectedLoaderService(@TempDir Path temporary) throws Exception {
        String injectedService = "META-INF/services/example.InjectedProvider";
        Map<String, byte[]> entries = validSyntheticBootJarEntries();
        entries.put(injectedService, "example.InjectedProvider\n".getBytes(StandardCharsets.UTF_8));
        Path artifact = temporary.resolve("injected-loader-service.jar");
        writeZip(artifact, entries);

        assertThatThrownBy(() -> assertBootJarBoundary(artifact))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(injectedService);
    }

    @ParameterizedTest(name = "duplicate={0}")
    @ValueSource(strings = {
        "META-INF/MANIFEST.MF",
        APPLICATION_CONFIGURATION,
        "BOOT-INF/classes/com/inforvans/accord/webhookedge/WebhookEdgeApplication.class"
    })
    void bootJarGateRejectsDuplicateCriticalEntries(
            String duplicate, @TempDir Path temporary) throws Exception {
        Path artifact = temporary.resolve(duplicate.replace('/', '-') + ".jar");
        writeZipWithDuplicate(artifact, validSyntheticBootJarEntries(), duplicate);

        assertThatThrownBy(() -> assertBootJarBoundary(artifact))
                .as(duplicate)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("duplicate")
                .hasMessageContaining(duplicate);
    }

    @Test
    void bootJarGateRejectsEveryArchiveBudgetAndRecursivePathViolation(@TempDir Path temporary)
            throws Exception {
        byte[] oversizedLibraryPayload = new byte[(8 * 1_024 * 1_024) + 1];
        new java.util.Random(7L).nextBytes(oversizedLibraryPayload);
        Map<String, byte[]> tooManyEntries = new LinkedHashMap<>();
        for (int index = 0; index < 4_097; index++) {
            tooManyEntries.put("safe/entry-" + index, new byte[] {0});
        }
        Map<String, byte[]> libraries = new LinkedHashMap<>();
        libraries.put("library-bytes", zipBytes(Map.of("safe/random.bin", oversizedLibraryPayload)));
        libraries.put(
                "expanded-entry",
                zipBytes(Map.of("safe/expanded.bin", new byte[(4 * 1_024 * 1_024) + 1])));
        libraries.put("entry-count", zipBytes(tooManyEntries));
        libraries.put("entry-name", zipBytes(Map.of("safe/" + "x".repeat(508), new byte[] {0})));
        libraries.put("unsafe-path", zipBytes(Map.of("../safe.class", new byte[] {0})));
        libraries.put(
                "recursive-depth",
                zipBytes(Map.of(
                        "safe/level-two",
                        zipBytes(Map.of(
                                "safe/level-three",
                                zipBytes(Map.of("safe/leaf.class", new byte[] {0})))))));

        List<Executable> assertions = new ArrayList<>();
        for (Map.Entry<String, byte[]> library : libraries.entrySet()) {
            Map<String, byte[]> entries = validSyntheticBootJarEntries();
            entries.put("BOOT-INF/lib/" + library.getKey(), library.getValue());
            Path artifact = temporary.resolve(library.getKey() + ".jar");
            writeZip(artifact, entries);
            assertions.add(() -> assertThatThrownBy(() -> assertBootJarBoundary(artifact))
                    .as(library.getKey())
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("BOOT-INF/lib/" + library.getKey()));
        }
        assertAll(assertions);
    }

    @Test
    void bootJarGateRejectsEveryUnexpectedApplicationResource(@TempDir Path temporary)
            throws Exception {
        Map<String, byte[]> entries = validSyntheticBootJarEntries();
        entries.put("BOOT-INF/classes/innocent-name.bin", new byte[] {0});
        Path artifact = temporary.resolve("unexpected-resource.jar");
        writeZip(artifact, entries);

        assertThatThrownBy(() -> assertBootJarBoundary(artifact))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("BOOT-INF/classes/innocent-name.bin");
    }

    @Test
    void runtimeClasspathContainsNoControlPlaneGeneratedOrGitClientClasses() {
        for (String className : List.of(
                "com.inforvans.accord.ControlApiApplication",
                "com.inforvans.accord.reliability.ReliableEventStore",
                "com.inforvans.accord.database.jooq.tables.AggregateHead",
                "org.eclipse.jgit.api.Git",
                "org.gitlab4j.api.GitLabApi",
                "org.kohsuke.github.GitHub")) {
            assertThatThrownBy(() -> Class.forName(className, false, getClass().getClassLoader()))
                    .isInstanceOf(ClassNotFoundException.class);
        }
        String classpath = System.getProperty("java.class.path").replace('\\', '/');
        assertThat(classpath)
                .doesNotContain("/apps/control-plane/")
                .doesNotContain("/database/control-plane/")
                .doesNotContain("/modules/reliability/");
    }

    @Test
    void buildDeclaresAnIndependentBootRuntimeWithOnlyTheTelemetryFoundationProject()
            throws Exception {
        Path root = repositoryRoot();
        String build = Files.readString(
                root.resolve("apps/webhook-edge/build.gradle"), StandardCharsets.UTF_8);
        String dependencies = build.substring(
                build.indexOf("dependencies {"), build.indexOf("tasks.named('processTestResources')"));

        assertThat(build)
                .contains("alias(libs.plugins.spring.boot)")
                .contains("implementation libs.spring.boot.web")
                .contains("implementation libs.spring.boot.actuator")
                .contains("implementation libs.spring.boot.jooq")
                .contains("runtimeOnly libs.postgresql")
                .contains("testImplementation libs.flyway.core")
                .contains("testImplementation libs.testcontainers.postgresql");
        assertThat(dependencies)
                .contains("implementation project(':libs:java:observability')")
                .doesNotContain("control-plane")
                .doesNotContain("reliability");
        assertThat(dependencies.replace(
                        "implementation project(':libs:java:observability')", ""))
                .doesNotContain("project(");
    }

    @Test
    void buildUsesLazyTypedBoundaryInputsAndRejectsDeclaredFileDependencies() throws Exception {
        String build = Files.readString(
                repositoryRoot().resolve("apps/webhook-edge/build.gradle"),
                StandardCharsets.UTF_8);

        assertThat(build)
                .contains("CommandLineArgumentProvider")
                .contains("FileCollectionDependency")
                .contains("getApplicationOutputDirectories()")
                .contains("applicationOutputDirectories.from(sourceSets.main.output)")
                .contains("getDeclaredFileDependencies()")
                .doesNotContain("bootJarTask.archiveFile.get()")
                .doesNotContain("configurations.runtimeClasspath.incoming.resolutionResult\n");
    }

    @Test
    void runtimeConfigurationRequiresProjectionAndAssumesTheRestrictedSessionRole() throws Exception {
        Path configurationFile =
                repositoryRoot().resolve("apps/webhook-edge/src/main/resources/application.yml");
        String configuration = Files.readString(configurationFile, StandardCharsets.UTF_8);
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(configurationFile));
        Properties properties = yaml.getObject();

        assertThat(configuration)
                .contains("username: ${ACCORD_WEBHOOK_EDGE_DB_USER:accord_webhook_runtime_login}")
                .contains("connection-init-sql: SET ROLE accord_webhook_runtime")
                .contains("bindings-file: ${ACCORD_WEBHOOK_EDGE_BINDINGS_FILE}")
                .doesNotContain("bindings-file: ${ACCORD_WEBHOOK_EDGE_BINDINGS_FILE:");
        assertThat(properties)
                .containsEntry("management.endpoint.health.probes.enabled", true);
    }

    @Test
    void runtimeConfigurationBoundsDatabaseFailureAndDefinesClosedHealthGroups() {
        Path configurationFile =
                repositoryRoot().resolve("apps/webhook-edge/src/main/resources/application.yml");
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(configurationFile));
        Properties properties = yaml.getObject();

        assertThat(properties)
                .containsEntry("spring.datasource.hikari.connection-timeout", 5_000)
                .containsEntry("spring.datasource.hikari.validation-timeout", 1_000)
                .containsEntry("spring.datasource.hikari.data-source-properties.connectTimeout", 5)
                .containsEntry("spring.datasource.hikari.data-source-properties.socketTimeout", 10)
                .containsEntry("spring.datasource.hikari.data-source-properties.cancelSignalTimeout", 5)
                .containsEntry("spring.datasource.hikari.data-source-properties.tcpKeepAlive", true)
                .containsEntry(
                        "spring.datasource.hikari.data-source-properties.options",
                        "-c statement_timeout=5s -c lock_timeout=2s -c idle_in_transaction_session_timeout=10s")
                .containsEntry("management.endpoint.health.group.liveness.include", "livenessState")
                .containsEntry(
                        "management.endpoint.health.group.readiness.include",
                        "readinessState,db,bindingProjection");
    }

    @Test
    void packagedApplicationKeepsLivenessUpButReadinessDownWhenDatabaseFails(
            @TempDir Path temporary) throws Exception {
        Path projection = temporary.resolve("bindings.json");
        Files.writeString(projection, validProjectionJson(), StandardCharsets.UTF_8);

        PackagedApplicationRunner runner = PackagedApplicationRunner.start(
                bootJar(),
                projection,
                "--spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable");
        long childPid = runner.processId();
        try (runner) {
            runner.awaitPort(Duration.ofSeconds(20));
            runner.assertHttpStatusEventually("/actuator/health/liveness", 200);
            runner.assertHttpStatusEventually("/actuator/health/readiness", 503);
        }
        assertThat(runner.isAlive()).isFalse();
        assertThat(runner.isOutputReaderAlive()).isFalse();
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @ParameterizedTest(name = "projection={0}")
    @ValueSource(strings = {"missing", "invalid"})
    void packagedApplicationKeepsLivenessUpButReadinessDownWhenProjectionFails(
            String projectionState, @TempDir Path temporary) throws Exception {
        Path projection = temporary.resolve("bindings.json");
        if (projectionState.equals("invalid")) {
            Files.writeString(projection, "{\"bindings\":[}", StandardCharsets.UTF_8);
        }

        try (DatabaseFixtureProbe database = new DatabaseFixtureProbe()) {
            database.start();
            PackagedApplicationRunner runner = PackagedApplicationRunner.start(
                    bootJar(),
                    projection,
                    "--spring.datasource.url=" + database.jdbcUrl(),
                    "--spring.datasource.username=" + database.runtimeUsername(),
                    "--spring.datasource.password=" + database.runtimePassword());
            long childPid = runner.processId();
            try (runner) {
                runner.awaitPort(Duration.ofSeconds(20));
                runner.assertHttpStatusEventually("/actuator/health/liveness", 200);
                runner.assertHttpStatusEventually("/actuator/health/readiness", 503);
            }
            assertThat(runner.isAlive()).isFalse();
            assertThat(runner.isOutputReaderAlive()).isFalse();
            assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        }
    }

    @Test
    void packagedApplicationReportsReadyWithValidProjectionAndRealEdgeDatabase(
            @TempDir Path temporary) throws Exception {
        Path projection = temporary.resolve("bindings.json");
        Files.writeString(projection, validProjectionJson(), StandardCharsets.UTF_8);
        try (DatabaseFixtureProbe database = new DatabaseFixtureProbe()) {
            database.start();
            PackagedApplicationRunner runner = PackagedApplicationRunner.start(
                    bootJar(),
                    projection,
                    "--spring.datasource.url=" + database.jdbcUrl(),
                    "--spring.datasource.username=" + database.runtimeUsername(),
                    "--spring.datasource.password=" + database.runtimePassword());
            long childPid = runner.processId();
            try (runner) {
                runner.awaitPort(Duration.ofSeconds(20));
                runner.assertHttpStatusEventually("/actuator/health/liveness", 200);
                runner.assertHttpStatusEventually("/actuator/health/readiness", 200);
                assertThat(runner.totalOutputBytes()).isPositive();
                assertThat(runner.outputTail().length).isLessThanOrEqualTo(PACKAGED_OUTPUT_TAIL_BYTES);
            }
            assertThat(runner.isAlive()).isFalse();
            assertThat(runner.isOutputReaderAlive()).isFalse();
            assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        }
    }

    @Test
    void boundedOutputTailCountsAllBytesAndRetainsOnlyItsCapacity() throws Exception {
        BoundedByteTail output = new BoundedByteTail(16);
        byte[] bytes = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);

        output.write(bytes);

        assertThat(output.totalBytes()).isEqualTo(bytes.length);
        assertThat(new String(output.snapshot(), StandardCharsets.US_ASCII))
                .isEqualTo("klmnopqrstuvwxyz");
    }

    @Test
    void tomcatPortParserAcceptsOnlyTheStartedLineForItsChildPid() {
        TomcatPortParser parser = new TomcatPortParser(42_424L);

        parser.accept("2026-07-27T04:02:02.728+08:00  INFO 111 --- [accord-webhook-edge] "
                + "[           main] o.s.b.w.e.tomcat.TomcatWebServer : "
                + "Tomcat started on port 49151 (http) with context path '/'\r\n");
        assertThat(parser.port()).isEmpty();
        parser.accept("2026-07-27T04:02:02.728+08:00  INFO 424");
        parser.accept("24 --- [accord-webhook-edge] [           main] "
                + "o.s.b.w.e.tomcat.TomcatWebServer : Tomcat started on port 49");
        assertThat(parser.port()).isEmpty();
        parser.accept("152 (http) with context path '/'\r\n");

        assertThat(parser.port()).hasValue(49_152);
    }

    @Test
    void packagedCleanupEscalatesToForcedTermination() {
        EscalatingProcess process = new EscalatingProcess(false);

        PackagedApplicationRunner.terminate(process);

        assertThat(process.destroyCalls).isEqualTo(1);
        assertThat(process.forcedDestroyCalls).isEqualTo(1);
        assertThat(process.waitCalls).isEqualTo(2);
        assertThat(process.isAlive()).isFalse();
    }

    @Test
    void packagedCleanupDoesNotTrustPrematureWaitCompletion() {
        EscalatingProcess process = new EscalatingProcess(true);

        PackagedApplicationRunner.terminate(process);

        assertThat(process.destroyCalls).isEqualTo(1);
        assertThat(process.forcedDestroyCalls).isEqualTo(1);
        assertThat(process.waitCalls).isEqualTo(2);
        assertThat(process.isAlive()).isFalse();
    }

    @Test
    void productionContextStartsWithAllConstructorWiring() {
        try (var context = new SpringApplicationBuilder(WebhookEdgeApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "spring.main.banner-mode=off",
                        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable",
                        "spring.datasource.hikari.initialization-fail-timeout=-1",
                        "ACCORD_WEBHOOK_EDGE_BINDINGS_FILE=build/nonexistent-bindings.json")
                .run()) {
            assertThat(context.getBean(BindingResolver.class)).isNotNull();
            assertThat(context.getBean(GitLabWebhookVerifier.class)).isNotNull();
            assertThat(context.getBean(WebhookInbox.class)).isInstanceOf(PostgresWebhookInbox.class);
            assertThat(context.getBean(WebhookHandler.class)).isNotNull();
            assertThat(context.getBean(HealthEndpoint.class)).isNotNull();
        }
    }

    @Test
    void packagedTestResourcesContainOnlyTheEdgeDatabaseAndTestcontainersConfig()
            throws Exception {
        Path resources = repositoryRoot().resolve("apps/webhook-edge/build/resources/test");
        List<String> relative = new ArrayList<>();
        try (var paths = Files.walk(resources)) {
            paths.filter(Files::isRegularFile)
                    .map(resources::relativize)
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted()
                    .forEach(relative::add);
        }
        assertThat(relative).containsExactly(
                "testcontainers.properties",
                "webhook-edge/bootstrap/00-pre-flyway-roles.sql",
                "webhook-edge/migration/V001__webhook_inbox_outbox.sql");
        assertThat(getClass().getResource("/db/migration/V001__platform_command_state.sql")).isNull();
        assertThat(getClass().getResource("/00-pre-flyway-roles.sql")).isNull();
    }

    @Test
    void productionAndPackagedResourcesContainNoRawBodyFixtures() throws Exception {
        Path root = repositoryRoot();
        Set<String> forbiddenSuffixes = Set.of(".http", ".har", ".patch", ".diff");
        for (Path directory : List.of(
                root.resolve("apps/webhook-edge/src/main"),
                root.resolve("apps/webhook-edge/build/resources/main"))) {
            if (!Files.exists(directory)) {
                continue;
            }
            try (var paths = Files.walk(directory)) {
                assertThat(paths.filter(Files::isRegularFile).map(path -> path.getFileName().toString()))
                        .allMatch(name -> forbiddenSuffixes.stream().noneMatch(name::endsWith))
                        .allMatch(name -> !name.contains("raw-body") && !name.contains("raw_body"));
            }
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("repository root unavailable");
        }
        return candidate;
    }

    private static Path bootJar() {
        String location = System.getProperty("accord.webhook-edge.boot-jar");
        assertThat(location).isNotBlank();
        Path artifact = Path.of(location);
        assertThat(artifact).isRegularFile();
        return artifact;
    }

    private static Path loaderToolsJar() {
        String location = System.getProperty("accord.webhook-edge.loader-tools-jar");
        if (location == null || location.isBlank()) {
            throw expectedBoundary("Spring Boot loader tools input is unavailable");
        }
        Path artifact = Path.of(location).toAbsolutePath().normalize();
        if (!Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)
                || !artifact.getFileName().toString().equals(
                        "spring-boot-loader-tools-3.5.3.jar")) {
            throw expectedBoundary("Spring Boot loader tools input is not the fixed regular JAR");
        }
        return artifact;
    }

    private static void assertBootJarBoundary(Path artifact) throws Exception {
        Path scratch = Files.createTempDirectory("webhook-edge-boundary-");
        try {
            new BootJarBoundaryScanner(
                            scratch,
                            expectedApplicationEntries(),
                            expectedLoaderEntries(),
                            trustedObservabilityLibraries())
                    .verify(artifact);
        } finally {
            deleteRecursively(scratch);
        }
    }

    private static Map<String, ExpectedEntry> expectedApplicationEntries() throws IOException {
        return expectedEntries(applicationOutputContents());
    }

    private static Map<String, byte[]> applicationOutputContents() throws IOException {
        long startedAtNanos = System.nanoTime();
        int walkedEntries = 0;
        long totalBytes = 0;
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (Path output : applicationOutputDirectories()) {
            if (!Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
                throw expectedBoundary("application output input is not a directory");
            }
            try (var paths = Files.walk(output)) {
                var iterator = paths.iterator();
                while (iterator.hasNext()) {
                    checkExpectedElapsed(startedAtNanos);
                    Path path = iterator.next();
                    walkedEntries++;
                    if (walkedEntries > BootJarBoundaryScanner.MAX_OUTER_ENTRIES) {
                        throw expectedBoundary("application output entry budget exceeded");
                    }
                    if (Files.isSymbolicLink(path)) {
                        throw expectedBoundary("symbolic links are forbidden in application output");
                    }
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    String packaged = APPLICATION_CLASSES_ROOT
                            + output.relativize(path).toString().replace('\\', '/');
                    validateExpectedApplicationPath(packaged);
                    if (contents.containsKey(packaged)) {
                        throw expectedBoundary("duplicate application output: " + packaged);
                    }
                    byte[] bytes = readExpectedBytes(
                            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS),
                            Files.size(path),
                            startedAtNanos,
                            "application output entry byte budget exceeded: " + packaged);
                    totalBytes = checkedExpectedAdd(
                            totalBytes,
                            bytes.length,
                            BootJarBoundaryScanner.MAX_OUTER_BYTES,
                            "application output aggregate byte budget exceeded");
                    contents.put(packaged, bytes);
                }
            }
        }
        if (contents.isEmpty()) {
            throw expectedBoundary("application output is empty");
        }
        return contents;
    }

    private static List<Path> applicationOutputDirectories() {
        String countValue = System.getProperty("accord.webhook-edge.application-output-count");
        if (countValue == null || !countValue.matches("[1-9][0-9]*")) {
            throw expectedBoundary("application output directory count is unavailable");
        }
        int count = Integer.parseInt(countValue);
        if (count > 32) {
            throw expectedBoundary("application output directory count budget exceeded");
        }
        List<Path> outputs = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String value = System.getProperty("accord.webhook-edge.application-output-" + index);
            if (value == null || value.isBlank()) {
                throw expectedBoundary("application output directory is unavailable");
            }
            outputs.add(Path.of(value).toAbsolutePath().normalize());
        }
        return outputs;
    }

    private static Map<String, byte[]> loaderReferenceContents() throws IOException {
        long startedAtNanos = System.nanoTime();
        byte[] loaderArchive;
        try (ZipFile loaderTools = new ZipFile(loaderToolsJar().toFile())) {
            Set<String> names = new LinkedHashSet<>();
            ZipEntry loaderEntry = null;
            int outerEntries = 0;
            var entries = loaderTools.entries();
            while (entries.hasMoreElements()) {
                checkExpectedElapsed(startedAtNanos);
                ZipEntry entry = entries.nextElement();
                outerEntries++;
                if (outerEntries > BootJarBoundaryScanner.MAX_OUTER_ENTRIES) {
                    throw expectedBoundary("Spring Boot loader tools entry budget exceeded");
                }
                if (!names.add(entry.getName())) {
                    throw expectedBoundary(
                            "duplicate Spring Boot loader tools entry: " + entry.getName());
                }
                if (entry.getName().equals("META-INF/loader/spring-boot-loader.jar")) {
                    if (entry.isDirectory() || loaderEntry != null) {
                        throw expectedBoundary(
                                "Spring Boot loader reference archive must occur exactly once");
                    }
                    loaderEntry = entry;
                }
            }
            if (loaderEntry == null) {
                throw expectedBoundary("Spring Boot loader reference archive is unavailable");
            }
            loaderArchive = readExpectedBytes(
                    loaderTools.getInputStream(loaderEntry),
                    loaderEntry.getSize(),
                    startedAtNanos,
                    "Spring Boot loader reference archive byte budget exceeded");
        }

        int entries = 0;
        long totalBytes = 0;
        Map<String, byte[]> contents = new LinkedHashMap<>();
        try (ZipInputStream loader = new ZipInputStream(new ByteArrayInputStream(loaderArchive))) {
            Set<String> names = new LinkedHashSet<>();
            ZipEntry entry;
            while ((entry = loader.getNextEntry()) != null) {
                checkExpectedElapsed(startedAtNanos);
                entries++;
                if (entries > BootJarBoundaryScanner.MAX_ARCHIVE_ENTRIES) {
                    throw expectedBoundary("Boot loader reference entry budget exceeded");
                }
                String name = entry.getName();
                if (name == null || name.isEmpty()
                        || name.getBytes(StandardCharsets.UTF_8).length
                                > BootJarBoundaryScanner.MAX_ENTRY_NAME_BYTES) {
                    throw expectedBoundary("Boot loader reference entry name budget exceeded");
                }
                if (!names.add(name)) {
                    throw expectedBoundary("duplicate Boot loader reference entry: " + name);
                }
                if (entry.isDirectory()) {
                    continue;
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                long entryBytes = 0;
                byte[] buffer = new byte[8_192];
                int count;
                while ((count = loader.read(buffer)) != -1) {
                    checkExpectedElapsed(startedAtNanos);
                    entryBytes = checkedExpectedAdd(
                            entryBytes,
                            count,
                            BootJarBoundaryScanner.MAX_EXPANDED_ENTRY_BYTES,
                            "Boot loader reference entry byte budget exceeded: " + name);
                    checkedExpectedAdd(
                            totalBytes,
                            entryBytes,
                            BootJarBoundaryScanner.MAX_EXPANDED_ARCHIVE_BYTES,
                            "Boot loader reference aggregate byte budget exceeded");
                    output.write(buffer, 0, count);
                }
                if (entry.getSize() >= 0 && entryBytes != entry.getSize()) {
                    throw expectedBoundary(
                            "Boot loader reference entry byte length mismatch: " + name);
                }
                byte[] bytes = output.toByteArray();
                totalBytes = checkedExpectedAdd(
                        totalBytes,
                        bytes.length,
                        BootJarBoundaryScanner.MAX_EXPANDED_ARCHIVE_BYTES,
                        "Boot loader reference aggregate byte budget exceeded");
                if (isEmittedLoaderEntry(name)) {
                    if (name.endsWith(".class")
                            && (!name.startsWith("org/springframework/boot/loader/")
                                    || name.toLowerCase(Locale.ROOT).contains("agent"))) {
                        throw expectedBoundary("unexpected Boot loader reference class: " + name);
                    }
                    contents.put(name, bytes);
                }
            }
        }
        if (contents.isEmpty()) {
            throw expectedBoundary("Boot loader reference contains no emitted entries");
        }
        return contents;
    }

    private static Map<String, ExpectedEntry> expectedLoaderEntries() throws IOException {
        return expectedEntries(loaderReferenceContents());
    }

    private static Map<String, ExpectedEntry> trustedObservabilityLibraries() throws IOException {
        String serialized = System.getProperty("accord.webhook-edge.runtime-coordinates");
        if (serialized == null) {
            throw expectedBoundary("runtime coordinates are unavailable");
        }
        List<String> projects = serialized.lines()
                .filter(value -> value.startsWith("PROJECT|"))
                .toList();
        if (projects.size() != 1) {
            throw expectedBoundary("trusted observability project artifact is not unique");
        }
        String[] fields = projects.get(0).split("\\|", -1);
        if (fields.length != 5
                || !TRUSTED_OBSERVABILITY_PROJECT.equals(fields[1])
                || !fields[2].matches("observability-[A-Za-z0-9.+_-]+[.]jar")
                || !fields[3].matches("[1-9][0-9]*")
                || !fields[4].matches("[0-9a-f]{64}")) {
            throw expectedBoundary("trusted observability project artifact is malformed");
        }
        return Map.of(
                "BOOT-INF/lib/" + fields[2],
                new ExpectedEntry(Long.parseLong(fields[3]), fields[4]));
    }

    private static Map<String, ExpectedEntry> expectedEntries(Map<String, byte[]> contents) {
        Map<String, ExpectedEntry> expected = new LinkedHashMap<>();
        contents.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> expected.put(
                entry.getKey(),
                new ExpectedEntry(entry.getValue().length, sha256(entry.getValue()))));
        return expected;
    }

    private static byte[] readExpectedBytes(
            InputStream input, long declaredBytes, long startedAtNanos, String budgetMessage)
            throws IOException {
        if (declaredBytes < 0 || declaredBytes > BootJarBoundaryScanner.MAX_EXPANDED_ENTRY_BYTES) {
            try (input) {
                throw expectedBoundary(budgetMessage);
            }
        }
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            long bytes = 0;
            byte[] buffer = new byte[8_192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkExpectedElapsed(startedAtNanos);
                bytes = checkedExpectedAdd(
                        bytes,
                        count,
                        BootJarBoundaryScanner.MAX_EXPANDED_ENTRY_BYTES,
                        budgetMessage);
                output.write(buffer, 0, count);
            }
            if (bytes != declaredBytes) {
                throw expectedBoundary("expected archive entry byte length changed while reading");
            }
            return output.toByteArray();
        }
    }

    private static void validateExpectedApplicationPath(String packaged) {
        if (packaged.endsWith(".class")) {
            if (!packaged.startsWith(APPLICATION_PACKAGE_ROOT)) {
                throw expectedBoundary("unexpected application class output: " + packaged);
            }
        } else if (!packaged.equals(APPLICATION_CONFIGURATION)) {
            throw expectedBoundary("unexpected application resource output: " + packaged);
        }
    }

    private static boolean isEmittedLoaderEntry(String name) {
        return name.endsWith(".class") || name.startsWith("META-INF/services/");
    }

    private static long checkedExpectedAdd(
            long current, long increment, long maximum, String message) {
        if (increment < 0 || current > maximum - increment) {
            throw expectedBoundary(message);
        }
        return current + increment;
    }

    private static void checkExpectedElapsed(long startedAtNanos) {
        long elapsed = System.nanoTime() - startedAtNanos;
        if (elapsed < 0 || elapsed > BootJarBoundaryScanner.MAX_ELAPSED_NANOS) {
            throw expectedBoundary("expected archive entry elapsed time budget exceeded");
        }
    }

    private static String sha256(byte[] contents) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    private static AssertionError expectedBoundary(String violation) {
        return new AssertionError("Webhook edge artifact boundary violated: " + violation);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class BootJarBoundaryScanner {
        private static final long MAX_OUTER_BYTES = 64L * 1_024 * 1_024;
        private static final int MAX_OUTER_ENTRIES = 1_024;
        private static final int MAX_ARCHIVES = 128;
        private static final long MAX_LIBRARY_BYTES = 8L * 1_024 * 1_024;
        private static final long MAX_TOTAL_LIBRARY_BYTES = 64L * 1_024 * 1_024;
        private static final int MAX_ARCHIVE_ENTRIES = 4_096;
        private static final int MAX_TOTAL_ENTRIES = 32_768;
        private static final int MAX_ENTRY_NAME_BYTES = 512;
        private static final long MAX_EXPANDED_ENTRY_BYTES = 4L * 1_024 * 1_024;
        private static final long MAX_EXPANDED_ARCHIVE_BYTES = 32L * 1_024 * 1_024;
        private static final long MAX_TOTAL_EXPANDED_BYTES = 128L * 1_024 * 1_024;
        private static final int MAX_DEPTH = 2;
        private static final long MAX_ELAPSED_NANOS = Duration.ofSeconds(30).toNanos();
        private static final List<String> REQUIRED_ENTRIES = List.of(
                APPLICATION_CONFIGURATION,
                "BOOT-INF/classes/com/inforvans/accord/webhookedge/WebhookEdgeApplication.class",
                "BOOT-INF/classes/com/inforvans/accord/webhookedge/webhook/WebhookHandler.class");

        private final Path scratch;
        private final Map<String, ExpectedEntry> expectedApplicationEntries;
        private final Map<String, ExpectedEntry> expectedLoaderEntries;
        private final Map<String, ExpectedEntry> trustedObservabilityLibraries;
        private long startedAtNanos;
        private int nextArchive;
        private int outerEntries;
        private int archives;
        private int nestedEntries;
        private long libraryBytes;
        private long expandedBytes;

        private BootJarBoundaryScanner(
                Path scratch,
                Map<String, ExpectedEntry> expectedApplicationEntries,
                Map<String, ExpectedEntry> expectedLoaderEntries,
                Map<String, ExpectedEntry> trustedObservabilityLibraries) {
            this.scratch = scratch;
            this.expectedApplicationEntries = Map.copyOf(expectedApplicationEntries);
            this.expectedLoaderEntries = Map.copyOf(expectedLoaderEntries);
            this.trustedObservabilityLibraries = Map.copyOf(trustedObservabilityLibraries);
        }

        private void verify(Path artifact) throws IOException {
            startedAtNanos = System.nanoTime();
            checkElapsed();
            if (!Files.isRegularFile(artifact)) {
                throw boundary("artifact is not a regular file");
            }
            long outerBytes = Files.size(artifact);
            if (outerBytes > MAX_OUTER_BYTES) {
                throw boundary("outer archive byte budget exceeded: " + outerBytes);
            }

            Set<String> entries = new LinkedHashSet<>();
            Set<String> applicationEntries = new LinkedHashSet<>();
            Set<String> loaderEntries = new LinkedHashSet<>();
            Set<String> trustedLibraries = new LinkedHashSet<>();
            ZipFile jar;
            try {
                jar = new ZipFile(artifact.toFile());
            } catch (IOException exception) {
                throw boundary("outer artifact is not a valid ZIP archive");
            }
            try (jar) {
                var jarEntries = jar.entries();
                while (jarEntries.hasMoreElements()) {
                    checkElapsed();
                    ZipEntry entry = jarEntries.nextElement();
                    String name = entry.getName();
                    validateEntryName(name, "outer archive");
                    if (!entries.add(name)) {
                        throw boundary("duplicate entry: " + name);
                    }
                    outerEntries++;
                    if (outerEntries > MAX_OUTER_ENTRIES) {
                        throw boundary("outer entry budget exceeded at " + name);
                    }
                    if (entry.isDirectory()) {
                        continue;
                    }
                    if (containsAny(name, FORBIDDEN_OUTER_FRAGMENTS)
                            || containsAny(name, FORBIDDEN_OUTER_PACKAGE_PATHS)) {
                        throw boundary("forbidden outer entry: " + name);
                    }
                    if (name.startsWith(APPLICATION_CLASSES_ROOT)) {
                        if (name.endsWith(".class")) {
                            if (!name.startsWith(APPLICATION_PACKAGE_ROOT)) {
                                throw boundary("unexpected application class: " + name);
                            }
                        } else if (!name.equals(APPLICATION_CONFIGURATION)) {
                            throw boundary("unexpected application entry: " + name);
                        }
                        verifyExpectedOuterEntry(
                                jar,
                                entry,
                                expectedApplicationEntries,
                                "application output");
                        applicationEntries.add(name);
                    }
                    if (isEmittedLoaderEntry(name)
                            && !name.startsWith(APPLICATION_CLASSES_ROOT)) {
                        if (name.endsWith(".class")
                                && (!name.startsWith("org/springframework/boot/loader/")
                                        || name.toLowerCase(Locale.ROOT).contains("agent"))) {
                            throw boundary("unexpected root class: " + name);
                        }
                        verifyExpectedOuterEntry(
                                jar, entry, expectedLoaderEntries, "Boot loader");
                        loaderEntries.add(name);
                    }
                    if (name.startsWith("BOOT-INF/lib/")) {
                        copyAndScanLibrary(jar, entry, trustedLibraries);
                    }
                }
                verifyManifest(jar);
            } catch (IOException exception) {
                throw boundary("outer archive payload is corrupt");
            }

            for (String required : REQUIRED_ENTRIES) {
                checkElapsed();
                if (!entries.contains(required)) {
                    throw boundary("missing required entry: " + required);
                }
            }
            if (!applicationEntries.equals(expectedApplicationEntries.keySet())) {
                throw boundary(describeSetDifference(
                        "application outputs",
                        expectedApplicationEntries.keySet(),
                        applicationEntries));
            }
            if (!loaderEntries.equals(expectedLoaderEntries.keySet())) {
                throw boundary(describeSetDifference(
                        "Boot loader entries", expectedLoaderEntries.keySet(), loaderEntries));
            }
            if (!trustedLibraries.equals(trustedObservabilityLibraries.keySet())) {
                throw boundary(describeSetDifference(
                        "trusted observability libraries",
                        trustedObservabilityLibraries.keySet(),
                        trustedLibraries));
            }
            checkElapsed();
        }

        private void verifyExpectedOuterEntry(
                ZipFile archive,
                ZipEntry entry,
                Map<String, ExpectedEntry> expectedEntries,
                String label) throws IOException {
            ExpectedEntry expected = expectedEntries.get(entry.getName());
            if (expected == null) {
                throw boundary("unexpected " + label + " entry: " + entry.getName());
            }
            if (entry.getSize() != expected.bytes()) {
                throw boundary(label + " entry byte length mismatch: " + entry.getName());
            }
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new AssertionError("SHA-256 is unavailable", exception);
            }
            long actualBytes = 0;
            try (InputStream input = archive.getInputStream(entry)) {
                byte[] buffer = new byte[8_192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkElapsed();
                    actualBytes = checkedAdd(
                            actualBytes,
                            count,
                            expected.bytes(),
                            label + " entry byte length mismatch: " + entry.getName());
                    digest.update(buffer, 0, count);
                }
            }
            if (actualBytes != expected.bytes()) {
                throw boundary(label + " entry byte length mismatch: " + entry.getName());
            }
            if (!HexFormat.of().formatHex(digest.digest()).equals(expected.sha256())) {
                throw boundary(label + " entry SHA-256 mismatch: " + entry.getName());
            }
        }

        private void verifyManifest(ZipFile jar) throws IOException {
            ZipEntry manifestEntry = jar.getEntry("META-INF/MANIFEST.MF");
            if (manifestEntry == null) {
                throw boundary("missing META-INF/MANIFEST.MF");
            }
            checkElapsed();
            try (InputStream input = jar.getInputStream(manifestEntry)) {
                Manifest manifest = new Manifest(input);
                String mainClass = manifest.getMainAttributes().getValue("Main-Class");
                if (!EXPECTED_MAIN_CLASS.equals(mainClass)) {
                    throw boundary("Manifest Main-Class must be " + EXPECTED_MAIN_CLASS
                            + ", was " + mainClass);
                }
                String startClass = manifest.getMainAttributes().getValue("Start-Class");
                if (!EXPECTED_START_CLASS.equals(startClass)) {
                    throw boundary("Manifest Start-Class must be " + EXPECTED_START_CLASS
                            + ", was " + startClass);
                }
                for (String attribute : FORBIDDEN_MANIFEST_ATTRIBUTES) {
                    if (manifest.getMainAttributes().getValue(attribute) != null) {
                        throw boundary("Manifest " + attribute + " is forbidden");
                    }
                }
            }
        }

        private void copyAndScanLibrary(
                ZipFile outer, ZipEntry library, Set<String> trustedLibraries) throws IOException {
            checkElapsed();
            if (library.getSize() <= 0) {
                throw boundary(library.getName() + ": empty nested archive");
            }
            if (library.getSize() > MAX_LIBRARY_BYTES) {
                throw boundary(library.getName() + ": nested archive byte budget exceeded");
            }
            Path copy = nextScratchArchive();
            long copied = 0;
            try (InputStream input = outer.getInputStream(library);
                    OutputStream output = Files.newOutputStream(copy)) {
                byte[] buffer = new byte[8_192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkElapsed();
                    copied = checkedAdd(
                            copied,
                            count,
                            MAX_LIBRARY_BYTES,
                            library.getName() + ": nested archive byte budget exceeded");
                    libraryBytes = checkedAdd(
                            libraryBytes,
                            count,
                            MAX_TOTAL_LIBRARY_BYTES,
                            library.getName() + ": aggregate nested archive byte budget exceeded");
                    output.write(buffer, 0, count);
                }
            }
            if (library.getSize() >= 0 && copied != library.getSize()) {
                throw boundary(library.getName() + ": nested archive size mismatch");
            }
            ExpectedEntry trusted = trustedObservabilityLibraries.get(library.getName());
            if (trusted != null) {
                if (copied != trusted.bytes()
                        || !sha256(Files.readAllBytes(copy)).equals(trusted.sha256())) {
                    throw boundary(library.getName()
                            + ": trusted observability library SHA-256 mismatch");
                }
                trustedLibraries.add(library.getName());
            }
            scanArchive(copy, library.getName(), 1, trusted != null);
        }

        private void scanArchive(
                Path archive, String displayName, int depth, boolean trustedObservability)
                throws IOException {
            checkElapsed();
            if (depth > MAX_DEPTH) {
                throw boundary(displayName + ": nested archive depth budget exceeded");
            }
            archives++;
            if (archives > MAX_ARCHIVES) {
                throw boundary(displayName + ": nested archive count budget exceeded");
            }

            ZipFile nested;
            try {
                nested = new ZipFile(archive.toFile());
            } catch (IOException exception) {
                throw boundary(displayName + ": invalid or truncated ZIP archive");
            }
            try (nested) {
                Set<String> names = new LinkedHashSet<>();
                int archiveEntries = 0;
                int regularEntries = 0;
                long archiveExpandedBytes = 0;
                var entries = nested.entries();
                while (entries.hasMoreElements()) {
                    checkElapsed();
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    validateEntryName(name, displayName);
                    if (!names.add(name)) {
                        throw boundary(displayName + "!/" + name + ": duplicate entry");
                    }
                    archiveEntries++;
                    nestedEntries++;
                    if (archiveEntries > MAX_ARCHIVE_ENTRIES) {
                        throw boundary(displayName + "!/" + name
                                + ": per-archive entry budget exceeded");
                    }
                    if (nestedEntries > MAX_TOTAL_ENTRIES) {
                        throw boundary(displayName + "!/" + name
                                + ": aggregate entry budget exceeded");
                    }
                    if (entry.isDirectory()) {
                        continue;
                    }
                    regularEntries++;
                    if (containsAny(name, FORBIDDEN_NESTED_PACKAGE_PATHS)
                            || name.startsWith(ACCORD_PACKAGE_ROOT)
                                    && !(trustedObservability
                                            && name.startsWith(OBSERVABILITY_PACKAGE_ROOT))) {
                        throw boundary(displayName + "!/" + name
                                + ": forbidden nested class path");
                    }
                    EntryRead read = readEntry(nested, entry, displayName);
                    archiveExpandedBytes = checkedAdd(
                            archiveExpandedBytes,
                            read.bytes(),
                            MAX_EXPANDED_ARCHIVE_BYTES,
                            displayName + "!/" + name
                                    + ": per-archive expanded byte budget exceeded");
                    if (read.archive() != null) {
                        scanArchive(read.archive(), displayName + "!/" + name, depth + 1, false);
                    }
                }
                if (regularEntries == 0) {
                    throw boundary(displayName + ": empty nested archive");
                }
            } catch (IOException exception) {
                throw boundary(displayName + ": corrupt nested archive payload");
            }
        }

        private EntryRead readEntry(ZipFile archive, ZipEntry entry, String displayName)
                throws IOException {
            checkElapsed();
            if (entry.getSize() > MAX_EXPANDED_ENTRY_BYTES) {
                throw boundary(displayName + "!/" + entry.getName()
                        + ": expanded entry byte budget exceeded");
            }
            Path candidate = null;
            OutputStream candidateOutput = null;
            long actual = 0;
            byte[] prefix = new byte[4];
            int prefixSize = 0;
            boolean detectionComplete = archiveName(entry.getName());
            try (InputStream input = archive.getInputStream(entry)) {
                if (detectionComplete) {
                    candidate = nextScratchArchive();
                    candidateOutput = Files.newOutputStream(candidate);
                }
                try {
                    byte[] buffer = new byte[8_192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkElapsed();
                        actual = checkedAdd(
                                actual,
                                count,
                                MAX_EXPANDED_ENTRY_BYTES,
                                displayName + "!/" + entry.getName()
                                        + ": expanded entry byte budget exceeded");
                        expandedBytes = checkedAdd(
                                expandedBytes,
                                count,
                                MAX_TOTAL_EXPANDED_BYTES,
                                displayName + "!/" + entry.getName()
                                        + ": aggregate expanded byte budget exceeded");
                        int outputOffset = 0;
                        if (!detectionComplete) {
                            int prefixCount = Math.min(prefix.length - prefixSize, count);
                            System.arraycopy(buffer, 0, prefix, prefixSize, prefixCount);
                            prefixSize += prefixCount;
                            outputOffset = prefixCount;
                            if (prefixSize == prefix.length) {
                                detectionComplete = true;
                                if (zipMagic(prefix)) {
                                    candidate = nextScratchArchive();
                                    candidateOutput = Files.newOutputStream(candidate);
                                    candidateOutput.write(prefix);
                                }
                            }
                        }
                        if (candidateOutput != null && outputOffset < count) {
                            candidateOutput.write(buffer, outputOffset, count - outputOffset);
                        }
                    }
                } finally {
                    if (candidateOutput != null) {
                        candidateOutput.close();
                    }
                }
            }
            if (entry.getSize() >= 0 && actual != entry.getSize()) {
                throw boundary(displayName + "!/" + entry.getName()
                        + ": expanded entry size mismatch");
            }
            return new EntryRead(actual, candidate);
        }

        private void validateEntryName(String name, String displayName) {
            if (name == null || name.isEmpty()
                    || name.getBytes(StandardCharsets.UTF_8).length > MAX_ENTRY_NAME_BYTES) {
                throw boundary(displayName + ": entry name budget exceeded");
            }
            String normalized = name.endsWith("/")
                    ? name.substring(0, name.length() - 1)
                    : name;
            if (normalized.isEmpty()
                    || name.contains("\\")
                    || name.startsWith("/")
                    || name.matches("^[A-Za-z]:.*")
                    || Arrays.stream(normalized.split("/", -1))
                            .anyMatch(segment -> segment.isEmpty()
                                    || segment.equals(".")
                                    || segment.equals(".."))) {
                throw boundary(displayName + "!/" + name + ": unsafe entry path");
            }
        }

        private long checkedAdd(long current, long increment, long maximum, String message) {
            if (increment < 0 || current > maximum - increment) {
                throw boundary(message);
            }
            return current + increment;
        }

        private void checkElapsed() {
            long elapsed = System.nanoTime() - startedAtNanos;
            if (elapsed < 0 || elapsed > MAX_ELAPSED_NANOS) {
                throw boundary("elapsed time budget exceeded");
            }
        }

        private Path nextScratchArchive() {
            return scratch.resolve(String.format(
                    Locale.ROOT, "nested-%05d.zip", nextArchive++));
        }

        private static boolean archiveName(String name) {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".jar")
                    || lower.endsWith(".zip")
                    || lower.endsWith(".war")
                    || lower.endsWith(".ear");
        }

        private static boolean zipMagic(byte[] prefix) {
            return prefix[0] == (byte) 'P'
                    && prefix[1] == (byte) 'K'
                    && ((prefix[2] == 3 && prefix[3] == 4)
                            || (prefix[2] == 5 && prefix[3] == 6)
                            || (prefix[2] == 7 && prefix[3] == 8));
        }

        private static boolean containsAny(String value, List<String> fragments) {
            return fragments.stream().anyMatch(value::contains);
        }

        private static String describeSetDifference(
                String label, Set<String> expected, Set<String> actual) {
            List<String> missing = expected.stream()
                    .filter(value -> !actual.contains(value))
                    .sorted()
                    .limit(20)
                    .toList();
            List<String> unexpected = actual.stream()
                    .filter(value -> !expected.contains(value))
                    .sorted()
                    .limit(20)
                    .toList();
            return label + " differ; missing=" + missing + ", unexpected=" + unexpected;
        }

        private static AssertionError boundary(String violation) {
            return new AssertionError("Webhook edge artifact boundary violated: " + violation);
        }

        private record EntryRead(long bytes, Path archive) {}
    }

    private record ExpectedEntry(long bytes, String sha256) {
        private ExpectedEntry {
            if (bytes < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("invalid expected archive entry metadata");
            }
        }
    }

    private static Map<String, byte[]> validSyntheticBootJarEntries() {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(
                "META-INF/MANIFEST.MF",
                manifest(EXPECTED_START_CLASS));
        try {
            entries.putAll(applicationOutputContents());
            entries.putAll(loaderReferenceContents());
            entries.put(LOADER_SERVICE, LOADER_SERVICE_CONTENTS);
            try (ZipFile packaged = new ZipFile(bootJar().toFile())) {
                for (String name : trustedObservabilityLibraries().keySet()) {
                    ZipEntry library = packaged.getEntry(name);
                    if (library == null || library.isDirectory()) {
                        throw expectedBoundary("trusted observability library is absent");
                    }
                    entries.put(name, packaged.getInputStream(library).readAllBytes());
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("authoritative archive output unavailable", exception);
        }
        return entries;
    }

    private static byte[] mutateFirstByte(byte[] source) {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("cannot mutate an empty archive entry");
        }
        byte[] mutated = source.clone();
        mutated[0] ^= 0x01;
        return mutated;
    }

    private static byte[] manifest(String startClass) {
        return manifest(startClass, Map.of());
    }

    private static byte[] manifest(String startClass, Map<String, String> mutations) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("Manifest-Version", "1.0");
        attributes.put("Main-Class", "org.springframework.boot.loader.launch.JarLauncher");
        attributes.put("Start-Class", startClass);
        attributes.putAll(mutations);
        StringBuilder content = new StringBuilder();
        attributes.forEach((name, value) -> content.append(name).append(": ").append(value).append("\r\n"));
        return content.append("\r\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zipBytes(Map<String, byte[]> entries) throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(bytes)) {
            writeZipEntries(zip, entries);
            zip.finish();
            return bytes.toByteArray();
        }
    }

    private static void writeZip(Path artifact, Map<String, byte[]> entries) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(artifact))) {
            writeZipEntries(zip, entries);
        }
    }

    private static void writeZipWithDuplicate(
            Path artifact, Map<String, byte[]> entries, String duplicate) throws Exception {
        String placeholder = "z".repeat(duplicate.length());
        if (entries.containsKey(placeholder)) {
            throw new IllegalArgumentException("duplicate ZIP placeholder collides with an entry");
        }
        Map<String, byte[]> withPlaceholder = new LinkedHashMap<>(entries);
        withPlaceholder.put(placeholder, entries.get(duplicate));
        writeZip(artifact, withPlaceholder);
        byte[] bytes = Files.readAllBytes(artifact);
        replaceAll(
                bytes,
                placeholder.getBytes(StandardCharsets.US_ASCII),
                duplicate.getBytes(StandardCharsets.US_ASCII));
        Files.write(artifact, bytes);
    }

    private static void replaceAll(byte[] bytes, byte[] target, byte[] replacement) {
        if (target.length != replacement.length) {
            throw new IllegalArgumentException("ZIP name replacement must preserve length");
        }
        int replacements = 0;
        for (int offset = 0; offset <= bytes.length - target.length; offset++) {
            if (!Arrays.equals(
                    bytes, offset, offset + target.length, target, 0, target.length)) {
                continue;
            }
            System.arraycopy(replacement, 0, bytes, offset, replacement.length);
            replacements++;
            offset += target.length - 1;
        }
        if (replacements != 2) {
            throw new IllegalStateException(
                    "expected two ZIP name replacements, found " + replacements);
        }
    }

    private static void writeZipEntries(ZipOutputStream zip, Map<String, byte[]> entries)
            throws Exception {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            zip.putNextEntry(new ZipEntry(entry.getKey()));
            zip.write(entry.getValue());
            zip.closeEntry();
        }
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("windows")
                ? "java.exe"
                : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        assertThat(java).isExecutable();
        return java;
    }

    private static String validProjectionJson() {
        return """
                {"bindings":[{
                  "binding_id":"019adf8e-04e8-7000-8000-000000000011",
                  "tenant_id":"019adf8e-04e8-7000-8000-000000000022",
                  "immutable_repository_id":4294967296,
                  "signing_token":"whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
                }]}
                """;
    }

    private static final class DatabaseFixtureProbe implements AutoCloseable {
        private final WebhookDatabaseFixture database = new WebhookDatabaseFixture();

        void start() {
            database.start();
        }

        String jdbcUrl() {
            return database.jdbcUrl();
        }

        String runtimeUsername() {
            return database.runtimeUsername();
        }

        String runtimePassword() {
            return database.runtimePassword();
        }

        @Override
        public void close() {
            database.close();
        }
    }

    private static final class PackagedApplicationRunner implements AutoCloseable {
        private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(20);

        private final Process process;
        private final BoundedByteTail output;
        private final TomcatPortParser portParser;
        private final AtomicReference<IOException> outputFailure;
        private final Thread outputReader;
        private final AtomicBoolean closed = new AtomicBoolean();

        private PackagedApplicationRunner(
                Process process,
                BoundedByteTail output,
                TomcatPortParser portParser,
                AtomicReference<IOException> outputFailure,
                Thread outputReader) {
            this.process = process;
            this.output = output;
            this.portParser = portParser;
            this.outputFailure = outputFailure;
            this.outputReader = outputReader;
        }

        static PackagedApplicationRunner start(
                Path artifact, Path projection, String... additionalArguments) throws IOException {
            List<String> command = new ArrayList<>(List.of(
                    javaExecutable().toString(),
                    "-jar",
                    artifact.toString(),
                    "--server.address=127.0.0.1",
                    "--server.port=0",
                    "--spring.main.banner-mode=off",
                    "--spring.output.ansi.enabled=never",
                    "--spring.datasource.hikari.initialization-fail-timeout=-1"));
            command.addAll(List.of(additionalArguments));
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            builder.environment().put("ACCORD_WEBHOOK_EDGE_BINDINGS_FILE", projection.toString());
            builder.environment().remove("KUBERNETES_SERVICE_HOST");
            builder.environment().remove("KUBERNETES_SERVICE_PORT");

            Process process = builder.start();
            BoundedByteTail output = new BoundedByteTail(PACKAGED_OUTPUT_TAIL_BYTES);
            TomcatPortParser parser = new TomcatPortParser(process.pid());
            AtomicReference<IOException> outputFailure = new AtomicReference<>();
            Thread reader = Thread.ofVirtual()
                    .name("webhook-edge-output-" + process.pid())
                    .start(() -> {
                        try (var stream = process.getInputStream()) {
                            byte[] buffer = new byte[8_192];
                            int count;
                            while ((count = stream.read(buffer)) != -1) {
                                output.write(buffer, 0, count);
                                parser.accept(new String(
                                        buffer, 0, count, StandardCharsets.UTF_8));
                            }
                        } catch (IOException failure) {
                            outputFailure.set(failure);
                        }
                    });
            return new PackagedApplicationRunner(process, output, parser, outputFailure, reader);
        }

        void awaitPort(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() - deadline < 0) {
                OptionalInt port = portParser.port();
                if (port.isPresent()) {
                    return;
                }
                assertProcessAndReaderHealthy("before publishing its HTTP port");
                sleepBriefly();
            }
            throw new AssertionError(
                    "Packaged webhook edge did not publish an HTTP port within " + timeout
                            + diagnostics());
        }

        void assertHttpStatusEventually(String path, int expectedStatus) {
            if (!path.startsWith("/") || path.startsWith("//")) {
                throw new IllegalArgumentException("health path must be absolute and local");
            }
            int port = portParser.port().orElseThrow(
                    () -> new IllegalStateException("packaged HTTP port unavailable"));
            URI endpoint = URI.create("http://127.0.0.1:" + port + path);
            long deadline = System.nanoTime() + HTTP_TIMEOUT.toNanos();
            int lastStatus = -1;
            String lastFailure = "none";
            while (System.nanoTime() - deadline < 0) {
                assertProcessAndReaderHealthy("while polling " + path);
                try {
                    HttpURLConnection connection =
                            (HttpURLConnection) endpoint.toURL().openConnection();
                    connection.setConnectTimeout(500);
                    connection.setReadTimeout(7_000);
                    try {
                        lastStatus = connection.getResponseCode();
                        if (lastStatus == expectedStatus) {
                            return;
                        }
                    } finally {
                        connection.disconnect();
                    }
                } catch (IOException failure) {
                    lastFailure = failure.getClass().getSimpleName();
                }
                sleepBriefly();
            }
            throw new AssertionError(
                    "Expected " + path + " to return " + expectedStatus
                            + " but last status was " + lastStatus
                            + " and last connection failure was " + lastFailure
                            + diagnostics());
        }

        boolean isAlive() {
            return process.isAlive();
        }

        long processId() {
            return process.pid();
        }

        boolean isOutputReaderAlive() {
            return outputReader.isAlive();
        }

        long totalOutputBytes() {
            return output.totalBytes();
        }

        byte[] outputTail() {
            return output.snapshot();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            terminate(process);
            try {
                outputReader.join(5_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while joining packaged output reader", exception);
            }
            if (outputReader.isAlive()) {
                outputReader.interrupt();
                throw new AssertionError("Packaged output reader did not terminate" + diagnostics());
            }
            IOException failure = outputFailure.get();
            if (failure != null) {
                throw new AssertionError(
                        "Packaged output reader failed: " + failure.getClass().getSimpleName());
            }
        }

        private void assertProcessAndReaderHealthy(String phase) {
            if (!process.isAlive()) {
                throw new AssertionError("Packaged webhook edge exited " + phase + diagnostics());
            }
            IOException failure = outputFailure.get();
            if (failure != null) {
                throw new AssertionError(
                        "Packaged output reader failed " + phase + ": "
                                + failure.getClass().getSimpleName());
            }
        }

        private static void terminate(Process process) {
            process.destroy();
            if (!waitForProcess(process, 5) || process.isAlive()) {
                process.destroyForcibly();
                if (!waitForProcess(process, 5) || process.isAlive()) {
                    throw new AssertionError("Packaged webhook edge survived forced termination");
                }
            }
        }

        private static boolean waitForProcess(Process process, int seconds) {
            try {
                return process.waitFor(seconds, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while terminating packaged webhook edge", exception);
            }
        }

        private String diagnostics() {
            return "; expected child PID=" + process.pid()
                    + ", observed Tomcat PID=" + portParser.observedProcessId()
                    + "; output tail:\n" + new String(output.snapshot(), StandardCharsets.UTF_8);
        }

        private static void sleepBriefly() {
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while polling packaged webhook edge", exception);
            }
        }
    }

    private static final class BoundedByteTail extends OutputStream {
        private final byte[] ring;
        private int first;
        private int size;
        private long totalBytes;

        BoundedByteTail(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("capacity must be positive");
            }
            ring = new byte[capacity];
        }

        @Override
        public synchronized void write(int value) {
            writeByte((byte) value);
            totalBytes = Math.addExact(totalBytes, 1);
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            for (int index = offset; index < offset + length; index++) {
                writeByte(bytes[index]);
            }
            totalBytes = Math.addExact(totalBytes, length);
        }

        synchronized long totalBytes() {
            return totalBytes;
        }

        synchronized byte[] snapshot() {
            byte[] snapshot = new byte[size];
            int firstPart = Math.min(size, ring.length - first);
            System.arraycopy(ring, first, snapshot, 0, firstPart);
            System.arraycopy(ring, 0, snapshot, firstPart, size - firstPart);
            return snapshot;
        }

        private void writeByte(byte value) {
            if (size < ring.length) {
                ring[(first + size) % ring.length] = value;
                size++;
                return;
            }
            ring[first] = value;
            first = (first + 1) % ring.length;
        }
    }

    private static final class EscalatingProcess extends Process {
        private final boolean gracefulWaitReportsCompletion;
        private int destroyCalls;
        private int forcedDestroyCalls;
        private int waitCalls;
        private boolean alive = true;

        private EscalatingProcess(boolean gracefulWaitReportsCompletion) {
            this.gracefulWaitReportsCompletion = gracefulWaitReportsCompletion;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            if (alive) {
                throw new IllegalThreadStateException("process still alive");
            }
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            waitCalls++;
            if (waitCalls == 1 && gracefulWaitReportsCompletion) {
                return true;
            }
            return !alive;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("process still alive");
            }
            return 0;
        }

        @Override
        public void destroy() {
            destroyCalls++;
        }

        @Override
        public Process destroyForcibly() {
            forcedDestroyCalls++;
            alive = false;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }

    private static final class TomcatPortParser {
        private static final int MAX_PENDING_CHARACTERS = 16_384;
        private static final Pattern TOMCAT_STARTED = Pattern.compile(
                "\\b(\\d+)\\s+---\\s+.*?Tomcat started on port (\\d{1,5}) \\(http\\)");

        private final long processId;
        private final StringBuilder pending = new StringBuilder();
        private long observedProcessId = -1;
        private int port = -1;

        TomcatPortParser(long processId) {
            if (processId <= 0) {
                throw new IllegalArgumentException("processId must be positive");
            }
            this.processId = processId;
        }

        synchronized void accept(String text) {
            if (port >= 0) {
                return;
            }
            pending.append(Objects.requireNonNull(text, "text"));
            int newline;
            while ((newline = pending.indexOf("\n")) >= 0) {
                parseLine(pending.substring(0, newline));
                pending.delete(0, newline + 1);
                if (port >= 0) {
                    return;
                }
            }
            parseLine(pending.toString());
            if (pending.length() > MAX_PENDING_CHARACTERS) {
                pending.delete(0, pending.length() - MAX_PENDING_CHARACTERS);
            }
        }

        synchronized OptionalInt port() {
            return port < 0 ? OptionalInt.empty() : OptionalInt.of(port);
        }

        synchronized long observedProcessId() {
            return observedProcessId;
        }

        private void parseLine(String line) {
            Matcher matcher = TOMCAT_STARTED.matcher(line);
            if (!matcher.find()) {
                return;
            }
            observedProcessId = Long.parseLong(matcher.group(1));
            if (observedProcessId != processId) {
                return;
            }
            int candidate = Integer.parseInt(matcher.group(2));
            if (candidate > 0 && candidate <= 65_535) {
                port = candidate;
            }
        }
    }
}
