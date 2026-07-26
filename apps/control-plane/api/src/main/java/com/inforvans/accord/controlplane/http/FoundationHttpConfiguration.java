package com.inforvans.accord.controlplane.http;

import com.inforvans.accord.reliability.JooqCommandGate;
import com.inforvans.accord.reliability.ReliableEventStore;
import com.inforvans.accord.reliability.StoredHttpResult;
import java.time.Duration;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class FoundationHttpConfiguration {
    @Bean
    FoundationProblemFactory foundationProblemFactory() {
        return new FoundationProblemFactory();
    }

    @Bean
    FoundationHttpRequestPolicy foundationHttpRequestPolicy() {
        return new FoundationHttpRequestPolicy();
    }

    @Bean
    ContractValidationJson contractValidationJson() {
        return new ContractValidationJson();
    }

    @Bean
    HttpIdempotencyFingerprint httpIdempotencyFingerprint(
            @Value("${accord.http.api-contract-version}") String apiContractVersion) {
        return new HttpIdempotencyFingerprint(apiContractVersion);
    }

    @Bean
    FoundationTenantTransactions foundationTenantTransactions(DSLContext dsl) {
        return new FoundationTenantTransactions(dsl);
    }

    @Bean
    JooqCommandGate jooqCommandGate() {
        return new JooqCommandGate();
    }

    @Bean
    ReliableEventStore reliableEventStore() {
        return new ReliableEventStore();
    }

    @Bean
    ContractValidationCommand contractValidationCommand(
            FoundationTenantTransactions transactions,
            ContractValidationJson json,
            JooqCommandGate gate,
            ReliableEventStore events,
            FoundationProblemFactory problems,
            @Value("${accord.process.instance-id}") String processInstanceId,
            @Value("${accord.http.command-result-ttl}") Duration resultTtl) {
        return new ContractValidationCommandService(
            transactions, json, gate, events, problems, processInstanceId, resultTtl);
    }

    @FunctionalInterface
    interface ContractValidationCommand {
        StoredHttpResult execute(
            FoundationVerifiedPrincipal principal,
            UUID validationId,
            FoundationHttpRequestPolicy.ParsedRequest request,
            ContractValidationJson.DecodedRequest body,
            String requestFingerprint);
    }
}
