package com.inforvans.accord.controlplane.http;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.inforvans.accord.controlplane.http.FoundationHttpConfiguration.ContractValidationCommand;
import com.inforvans.accord.reliability.StoredHttpResult;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class ContractValidationController {
    private final FoundationHttpRequestPolicy requestPolicy;
    private final ContractValidationJson json;
    private final HttpIdempotencyFingerprint fingerprint;
    private final ContractValidationCommand command;

    ContractValidationController(
            FoundationHttpRequestPolicy requestPolicy,
            ContractValidationJson json,
            HttpIdempotencyFingerprint fingerprint,
            ContractValidationCommand command) {
        this.requestPolicy = requestPolicy;
        this.json = json;
        this.fingerprint = fingerprint;
        this.command = command;
    }

    @PostMapping("/v1/contract-validations/{validationId}")
    ResponseEntity<byte[]> validate(
            @AuthenticationPrincipal FoundationVerifiedPrincipal principal,
            @PathVariable("validationId") String rawValidationId,
            HttpServletRequest servletRequest) throws IOException {
        UUID validationId = FoundationHttpRequestPolicy.canonicalValidationId(rawValidationId);
        FoundationHttpRequestPolicy.ParsedRequest parsed = requestPolicy.parse(servletRequest);
        byte[] rawBody = servletRequest.getInputStream().readNBytes(
            FoundationHttpRequestPolicy.MAX_RAW_BODY_BYTES + 1);
        ContractValidationJson.DecodedRequest decoded = json.decode(rawBody);
        String requestFingerprint = fingerprint.contractValidation(
            principal, validationId, parsed, decoded.bodyDigest());
        StoredHttpResult result = command.execute(
            principal, validationId, parsed, decoded, requestFingerprint);
        return ProblemAdvice.response(result);
    }
}

record ContractValidationResponse(
    @JsonProperty("validation_id") UUID validationId,
    boolean valid,
    @JsonProperty("document_digest") String documentDigest,
    long version
) {}
