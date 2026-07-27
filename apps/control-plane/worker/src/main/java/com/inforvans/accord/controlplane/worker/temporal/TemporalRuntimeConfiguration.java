package com.inforvans.accord.controlplane.worker.temporal;

import com.inforvans.accord.controlplane.worker.reconciliation.FencedReconciliationObservation;
import com.inforvans.accord.controlplane.worker.reconciliation.ProviderObservationPort;
import com.inforvans.accord.controlplane.worker.reconciliation.ReconciliationRuntimeProperties;
import com.inforvans.accord.controlplane.worker.reconciliation.WorkerTenantTransactions;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReadOnlyReconciliationActivity;
import com.inforvans.accord.controlplane.worker.WorkerScheduling;
import com.inforvans.accord.observability.AccordWorkflowTelemetry;
import com.inforvans.accord.reliability.JooqExternalIntentStore;
import java.io.IOException;
import org.jooq.DSLContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    TemporalConnectionProperties.class,
    ReconciliationRuntimeProperties.class
})
public class TemporalRuntimeConfiguration {
    @Bean
    TemporalSecretAclPolicy temporalSecretAclPolicy() throws IOException {
        String osName = System.getProperty("os.name", "");
        if (osName.startsWith("Windows")) {
            return new WindowsTemporalSecretAclPolicy();
        }
        if (osName.startsWith("Linux") || osName.startsWith("Mac")
                || osName.startsWith("FreeBSD") || osName.startsWith("Unix")) {
            return new PosixTemporalSecretAclPolicy();
        }
        throw new IOException("unsupported Temporal secret ACL platform");
    }

    @Bean
    TemporalSecretFileLoader temporalSecretFileLoader(TemporalSecretAclPolicy aclPolicy) {
        return new TemporalSecretFileLoader(aclPolicy);
    }

    @Bean
    TemporalServiceStubsFactory temporalServiceStubsFactory(
            TemporalSecretFileLoader secretLoader) {
        return new TemporalServiceStubsFactory(secretLoader);
    }

    @Bean
    WorkerTenantTransactions workerTenantTransactions(DSLContext context) {
        return new WorkerTenantTransactions(context);
    }

    @Bean
    JooqExternalIntentStore externalIntentStore() {
        return new JooqExternalIntentStore();
    }

    @Bean
    FencedReconciliationObservation fencedReconciliationObservation(
            WorkerTenantTransactions transactions,
            JooqExternalIntentStore store,
            ProviderObservationPort provider,
            ReconciliationRuntimeProperties properties,
            AccordWorkflowTelemetry telemetry) {
        return new FencedReconciliationObservation(
            transactions, store, provider, properties, telemetry);
    }

    @Bean
    ReadOnlyReconciliationActivity readOnlyReconciliationActivity(
            FencedReconciliationObservation observation) {
        return new ReadOnlyReconciliationActivity(observation);
    }

    @Bean
    TemporalWorkerLifecycle temporalWorkerLifecycle(
            TemporalConnectionProperties properties,
            TemporalServiceStubsFactory stubsFactory,
            ReadOnlyReconciliationActivity activity) {
        return new TemporalWorkerLifecycle(properties, stubsFactory, activity);
    }

    @Bean
    WorkerScheduling.DestinationAdapter contractValidationReconciliationDestination(
            TemporalWorkerLifecycle lifecycle,
            TemporalConnectionProperties properties,
            WorkerTenantTransactions transactions,
            JooqExternalIntentStore intents,
            ReconciliationRuntimeProperties reconciliation) {
        return new WorkerScheduling.DestinationAdapter(
            TemporalReconciliationEventTransport.DESTINATION,
            new TemporalReconciliationEventTransport(
                lifecycle::workflowClient,
                properties.taskQueue(),
                transactions,
                intents,
                reconciliation));
    }
}
