package com.inforvans.accord.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TelemetryLeakTest {
    @Test
    void everyExecutableInstallsTheSameGuardedAndNonCapturingTelemetryBoundary()
            throws Exception {
        Path root = repositoryRoot();
        for (String path : List.of(
                "apps/control-plane/api",
                "apps/control-plane/worker",
                "apps/webhook-edge")) {
            String build = Files.readString(root.resolve(path).resolve("build.gradle"));
            String yaml = Files.readString(
                    root.resolve(path).resolve("src/main/resources/application.yml"));
            assertThat(build)
                    .contains("project(':libs:java:observability')")
                    .contains("libs.micrometer.tracing.bridge.otel")
                    .contains("libs.micrometer.registry.prometheus");
            assertThat(yaml)
                    .contains("endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318}")
                    .contains("trust-certificate: ${OTEL_EXPORTER_OTLP_CERTIFICATE:}")
                    .contains("server-name: ${ACCORD_OTLP_SERVER_NAME:}")
                    .contains("baggage:")
                    .contains("enabled: false")
                    .contains("capture-request-headers: \"\"")
                    .contains("capture-response-headers: \"\"")
                    .contains("capture-mdc-attributes: \"\"")
                    .contains("export-timeout: PT2S")
                    .contains("shutdown-timeout: PT5S");
        }
    }

    @Test
    void collectorUsesBoundedFilesPrometheusAndAnAttributeAllowlistWithoutDebugExport()
            throws Exception {
        Path root = repositoryRoot();
        String collector = Files.readString(root.resolve("infra/local/otel-collector.yaml"));
        String compose = Files.readString(root.resolve("infra/local/compose.yaml"));

        assertThat(collector)
                .contains("transform/allowlist:")
                .contains("keep_keys(attributes")
                .contains("path: /var/lib/otel/accord-telemetry.jsonl")
                .contains("max_megabytes: 16")
                .contains("max_backups: 5")
                .contains("prometheus:")
                .doesNotContain("debug:", "exporters: [debug]");
        assertThat(compose)
                .contains("./state/otel:/var/lib/otel")
                .contains("127.0.0.1:9464:9464");
    }

    @Test
    void productionConfigurationRejectsPlaintextCollectorTransport() throws Exception {
        String source = Files.readString(repositoryRoot().resolve(
                "libs/java/observability/src/main/java/com/inforvans/accord/observability/"
                        + "AccordOpenTelemetryConfiguration.java"));
        assertThat(source)
                .contains("environment == DeploymentEnvironment.PRODUCTION")
                .contains("production telemetry endpoint requires TLS")
                .contains("production telemetry server name must match endpoint")
                .contains("production telemetry trust certificate is required")
                .contains("setTrustedCertificates(trustedCertificates)");
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
