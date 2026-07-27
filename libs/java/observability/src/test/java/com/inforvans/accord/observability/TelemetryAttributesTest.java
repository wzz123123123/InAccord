package com.inforvans.accord.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.common.Attributes;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TelemetryAttributesTest {
    private static final String TENANT_ID = "018f928e-4e89-71a2-999e-abb50daf6237";
    private static final String CORRELATION_ID = "019f928e-4e89-71a2-999e-abb50daf6237";

    @Test
    void createsDeterministicTypedHttpAttributes() {
        TelemetryIdentifiers identifiers = new TelemetryIdentifiers(
                Optional.of(new TelemetryIdentifiers.TenantId(TENANT_ID)),
                Optional.of(new TelemetryIdentifiers.Scope(
                        TelemetryIdentifiers.ScopeType.PROJECT,
                        new TelemetryIdentifiers.ScopeId("project:77831"))),
                Optional.of(new TelemetryIdentifiers.CorrelationId(CORRELATION_ID)),
                Optional.empty());
        TelemetryAttributes attributes = new TelemetryAttributes.Http(
                identifiers,
                TelemetryOperation.HTTP_REQUEST,
                TelemetryProvider.NOT_APPLICABLE,
                TelemetryResultCode.SUCCESS,
                TelemetryAttributes.HttpMethod.POST,
                new TelemetryAttributes.RouteTemplate(
                        "/v1/contract-validations/{validationId}"),
                new TelemetryAttributes.HttpStatus(202));

        Attributes first = attributes.toOtelAttributes();
        Attributes second = attributes.toOtelAttributes();

        assertThat(first).isEqualTo(second);
        assertThat(first.asMap().entrySet())
                .extracting(entry -> entry.getKey().getKey())
                .containsExactly(
                        "accord.correlation.id",
                        "accord.operation",
                        "accord.provider",
                        "accord.result_code",
                        "accord.scope.id",
                        "accord.scope.type",
                        "accord.tenant.id",
                        "http.request.method",
                        "http.response.status_code",
                        "http.route");
        assertThat(first.get(TelemetryAttributeKey.HTTP_STATUS.longKey())).isEqualTo(202L);
    }

    @Test
    void metricVariantCannotCarryHighCardinalityIdentifiers() {
        Attributes attributes = new TelemetryAttributes.Metric(
                TelemetryOperation.RELIABILITY_DELIVERY,
                TelemetryProvider.NOT_APPLICABLE,
                TelemetryResultCode.SUCCESS)
                .toOtelAttributes();

        assertThat(attributes.asMap().keySet())
                .extracting(key -> key.getKey())
                .containsExactly(
                        "accord.operation",
                        "accord.provider",
                        "accord.result_code");
    }

    @Test
    void rejectsNonCanonicalAndUnregisteredValuesWithoutTruncation() {
        assertThatThrownBy(() -> new TelemetryIdentifiers.TenantId(
                "018F928E-4E89-71A2-999E-ABB50DAF6237"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid telemetry identifier");
        assertThatThrownBy(() -> new TelemetryAttributes.RouteTemplate("/raw/123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unregistered telemetry route");
        assertThatThrownBy(() -> new TelemetryAttributes.Workflow(
                TelemetryIdentifiers.none(),
                TelemetryOperation.PROVIDER_RECONCILIATION,
                TelemetryProvider.NOT_APPLICABLE,
                TelemetryResultCode.SUCCESS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid telemetry provider context");
        assertThatThrownBy(() -> new TelemetryIdentifiers.ScopeId("x".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid telemetry identifier");
    }

    @Test
    void classifiesSensitiveTokensAndUrlsAtPunctuationBoundaries() {
        SensitiveTelemetryCandidate classifier = new SensitiveTelemetryCandidate(List.of());

        assertThat(classifier.classifyValue(
                        "token=abcdefgh.abcdefgh.abcdefgh"))
                .contains(TelemetryDropReason.SENSITIVE_VALUE);
        assertThat(classifier.classifyValue(
                        "collector=(https://example.invalid/path)"))
                .contains(TelemetryDropReason.RAW_URL);
        assertThat(classifier.classifyValue("Cookie: opaque-value"))
                .contains(TelemetryDropReason.SENSITIVE_VALUE);
    }
}
