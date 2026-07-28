package com.inforvans.accord.controlplane.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.inforvans.accord.platformkernel.CanonicalJson;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

final class HttpIdempotencyFingerprint {
    private static final Pattern DIGEST = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern CONTRACT_VERSION = Pattern.compile("^[0-9]+[.][0-9]+[.][0-9]+$");

    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiContractVersion;

    HttpIdempotencyFingerprint(String apiContractVersion) {
        if (apiContractVersion == null
                || !CONTRACT_VERSION.matcher(apiContractVersion).matches()) {
            throw new IllegalArgumentException("apiContractVersion is invalid");
        }
        this.apiContractVersion = apiContractVersion;
    }

    String contractValidation(
            FoundationVerifiedPrincipal principal,
            UUID validationId,
            FoundationHttpRequestPolicy.ParsedRequest request,
            String bodyDigest) {
        return fingerprint(contractValidationEnvelope(principal, validationId, request, bodyDigest));
    }

    Envelope contractValidationEnvelope(
            FoundationVerifiedPrincipal principal,
            UUID validationId,
            FoundationHttpRequestPolicy.ParsedRequest request,
            String bodyDigest) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(validationId, "validationId");
        Objects.requireNonNull(request, "request");
        requireDigest(bodyDigest);
        return new Envelope(
            request.normalizedAccept(),
            principal.actorId(),
            apiContractVersion,
            bodyDigest,
            Long.toString(request.expectedVersion().value()),
            "POST",
            "/v1/contract-validations/" + validationId,
            "",
            request.requestMediaType(),
            validationId.toString(),
            "contract-validations.create",
            principal.tenantId().toString());
    }

    String fingerprint(Envelope envelope) {
        return CanonicalJson.sha256(canonicalEnvelope(envelope));
    }

    byte[] canonicalEnvelope(Envelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        ObjectNode node = mapper.createObjectNode();
        node.put("accept", envelope.accept());
        node.put("actor_id", envelope.actorId());
        node.put("api_contract_version", envelope.apiContractVersion());
        node.put("body_digest", envelope.bodyDigest());
        node.put("expected_version", envelope.expectedVersion());
        node.put("http_method", envelope.httpMethod());
        node.put("normalized_path", envelope.normalizedPath());
        node.put("normalized_query", envelope.normalizedQuery());
        node.put("request_media_type", envelope.requestMediaType());
        node.put("resource_id", envelope.resourceId());
        node.put("route_key", envelope.routeKey());
        node.put("tenant_id", envelope.tenantId());
        try {
            return CanonicalJson.canonicalize(mapper.writeValueAsBytes(node));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("fingerprint envelope could not be serialized", error);
        }
    }

    private static void requireDigest(String digest) {
        if (digest == null || !DIGEST.matcher(digest).matches()) {
            throw new IllegalArgumentException("bodyDigest must be a canonical SHA-256 digest");
        }
    }

    record Envelope(
            String accept,
            String actorId,
            String apiContractVersion,
            String bodyDigest,
            String expectedVersion,
            String httpMethod,
            String normalizedPath,
            String normalizedQuery,
            String requestMediaType,
            String resourceId,
            String routeKey,
            String tenantId) {
        Envelope {
            Objects.requireNonNull(accept, "accept");
            Objects.requireNonNull(actorId, "actorId");
            Objects.requireNonNull(apiContractVersion, "apiContractVersion");
            requireDigest(bodyDigest);
            Objects.requireNonNull(expectedVersion, "expectedVersion");
            Objects.requireNonNull(httpMethod, "httpMethod");
            Objects.requireNonNull(normalizedPath, "normalizedPath");
            Objects.requireNonNull(normalizedQuery, "normalizedQuery");
            Objects.requireNonNull(requestMediaType, "requestMediaType");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(routeKey, "routeKey");
            Objects.requireNonNull(tenantId, "tenantId");
        }
    }
}
