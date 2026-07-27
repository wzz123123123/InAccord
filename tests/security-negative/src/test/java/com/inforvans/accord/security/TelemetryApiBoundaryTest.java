package com.inforvans.accord.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.inforvans.accord.observability.AccordOpenTelemetryConfiguration;
import com.inforvans.accord.observability.AccordWorkflowTelemetry;
import com.inforvans.accord.observability.SensitiveTelemetryCandidate;
import com.inforvans.accord.observability.TelemetryAttributes;
import com.inforvans.accord.observability.TelemetryIdentifiers;
import com.inforvans.accord.observability.TelemetryOperation;
import com.inforvans.accord.observability.TelemetryProvider;
import com.inforvans.accord.observability.TelemetryResultCode;
import java.io.IOException;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class TelemetryApiBoundaryTest {
    private static final List<String> DIRECT_OTEL_TOKENS = List.of(
            "import io.opentelemetry.",
            "io.opentelemetry.api.",
            "io.opentelemetry.exporter.",
            "io.opentelemetry.sdk.",
            "io.opentelemetry.sdk.trace.export",
            "io.opentelemetry.sdk.metrics.export",
            "io.opentelemetry.sdk.logs.export",
            "import io.micrometer.core.instrument.Tag",
            "io.micrometer.core.instrument.Tag.");
    private static final List<Class<?>> PUBLIC_TYPES = List.of(
            AccordOpenTelemetryConfiguration.class,
            AccordOpenTelemetryConfiguration.Settings.class,
            AccordWorkflowTelemetry.class,
            SensitiveTelemetryCandidate.class,
            TelemetryAttributes.class,
            TelemetryAttributes.Http.class,
            TelemetryAttributes.Workflow.class,
            TelemetryAttributes.DomainEvent.class,
            TelemetryAttributes.Metric.class,
            TelemetryIdentifiers.class,
            TelemetryOperation.class,
            TelemetryProvider.class,
            TelemetryResultCode.class);

    @Test
    void applicationMainSourcesCannotConstructOrExportTelemetryAttributesDirectly()
            throws Exception {
        Path root = repositoryRoot();
        List<String> violations = new ArrayList<>();
        for (Path sourceRoot : List.of(
                root.resolve("apps/control-plane/api/src/main/java"),
                root.resolve("apps/control-plane/worker/src/main/java"),
                root.resolve("apps/webhook-edge/src/main/java"))) {
            for (Path source : javaSources(sourceRoot)) {
                String content = Files.readString(source);
                for (String token : DIRECT_OTEL_TOKENS) {
                    if (content.contains(token)) {
                        violations.add(root.relativize(source) + ":" + token);
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void publicTypedApiAcceptsNoGenericOrRequestShapedValues() {
        Predicate<Class<?>> forbidden = type -> type == Object.class
                || Map.class.isAssignableFrom(type)
                || Throwable.class.isAssignableFrom(type)
                || containsAny(
                        type.getName(),
                        "HttpServlet",
                        "HttpHeaders",
                        "Cookie",
                        "Request",
                        "Response");
        List<String> violations = new ArrayList<>();
        for (Class<?> type : PUBLIC_TYPES) {
            Stream.concat(
                            Arrays.stream(type.getDeclaredConstructors()),
                            Arrays.stream(type.getDeclaredMethods())
                                    .filter(TelemetryApiBoundaryTest::isPublicContractMethod))
                    .filter(executable -> Modifier.isPublic(executable.getModifiers()))
                    .forEach(executable -> Arrays.stream(executable.getParameterTypes())
                            .filter(forbidden)
                            .forEach(parameter -> violations.add(
                                    signature(executable) + " -> " + parameter.getName())));
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void metricVariantStructurallyCannotCarryHighCardinalityIdentifiers() {
        assertThat(Arrays.stream(TelemetryAttributes.Metric.class.getRecordComponents())
                .map(component -> component.getType().getName()))
                .containsExactly(
                        TelemetryOperation.class.getName(),
                        TelemetryProvider.class.getName(),
                        TelemetryResultCode.class.getName())
                .doesNotContain(TelemetryIdentifiers.class.getName());
    }

    private static boolean isPublicContractMethod(Method method) {
        return !method.isSynthetic()
                && !method.getName().equals("equals")
                && !method.getName().equals("hashCode")
                && !method.getName().equals("toString");
    }

    private static boolean containsAny(String candidate, String... fragments) {
        return Arrays.stream(fragments).anyMatch(candidate::contains);
    }

    private static String signature(Executable executable) {
        return executable.getDeclaringClass().getName() + "#" + executable.getName();
    }

    private static List<Path> javaSources(Path sourceRoot) throws IOException {
        if (!Files.isDirectory(sourceRoot)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("repository root is unavailable");
        }
        return candidate;
    }
}
