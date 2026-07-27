package com.inforvans.accord.controlplane.worker.temporal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.inforvans.accord.controlplane.worker.reconciliation.FencedReconciliationObservation;
import com.inforvans.accord.controlplane.worker.reconciliation.ProviderObservationPort;
import com.inforvans.accord.controlplane.worker.reconciliation.ReconciliationFailure;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationActivities;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationObservationPort;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationOutcome;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflow;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflowImpl;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflowRef;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReconciliationWorkflowTest {
    private static final String WORKFLOW_PACKAGE =
        "com.inforvans.accord.controlplane.worker.temporal.workflow";
    private static final String RECONCILIATION_PACKAGE =
        "com.inforvans.accord.controlplane.worker.reconciliation";
    private static final String PROVIDER_OBSERVATION_PORT =
        ProviderObservationPort.class.getName();
    private static final Set<String> WORKFLOW_CAPABILITY_TYPES = Set.of(
        "com.inforvans.accord.reliability.ExternalWritePermit",
        "com.inforvans.accord.reliability.ExecutionClaim",
        "com.inforvans.accord.reliability.JooqExternalIntentStore");
    private static final UUID TENANT_ID =
        UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID INTENT_ID =
        UUID.fromString("20000000-0000-0000-0000-000000000001");

    @Test
    void exposesOnlyTheIdentifierOnlyWorkflowBoundary() {
        assertSingleMethod(ReconciliationWorkflow.class, "reconcile");
        assertSingleMethod(ReconciliationActivities.class, "observe");
        assertSingleMethod(ReconciliationObservationPort.class, "observe");

        assertThat(ReconciliationWorkflowRef.class.isRecord()).isTrue();
        assertThat(Arrays.stream(ReconciliationWorkflowRef.class.getRecordComponents())
                .map(component -> component.getName() + ":" + component.getType().getName()))
            .containsExactly("tenantId:java.util.UUID", "intentId:java.util.UUID");
        assertThatThrownBy(() -> new ReconciliationWorkflowRef(null, INTENT_ID))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("tenantId");
        assertThatThrownBy(() -> new ReconciliationWorkflowRef(TENANT_ID, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("intentId");
    }

    @Test
    void observationActivityUsesTheExactBoundedRetryAndHeartbeatPolicy()
            throws Exception {
        Field optionsField = ReconciliationWorkflowImpl.class
            .getDeclaredField("OBSERVATION_OPTIONS");
        optionsField.setAccessible(true);
        ActivityOptions options = (ActivityOptions) optionsField.get(null);
        RetryOptions retry = options.getRetryOptions();

        assertThat(options.getScheduleToCloseTimeout()).isEqualTo(Duration.ofMinutes(2));
        assertThat(options.getStartToCloseTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(options.getHeartbeatTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(retry.getInitialInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(retry.getBackoffCoefficient()).isEqualTo(2.0);
        assertThat(retry.getMaximumInterval()).isEqualTo(Duration.ofSeconds(8));
        assertThat(retry.getMaximumAttempts()).isEqualTo(8);
        assertThat(retry.getDoNotRetry()).containsExactly(
            "RECONCILIATION_NOT_RECONCILABLE",
            "RECONCILIATION_MISSING");
    }

    @Test
    void workflowRetriesClosedActivityFailureThenReturnsClosedOutcome() {
        AtomicInteger attempts = new AtomicInteger();

        ReconciliationOutcome result = runWorkflow(ref -> {
            if (attempts.incrementAndGet() == 1) {
                throw ApplicationFailure.newFailure(
                    "reconciliation retry required",
                    ReconciliationFailure.Code.OBSERVATION_UNAVAILABLE.name());
            }
            return ReconciliationOutcome.CONVERGED;
        });

        assertThat(result).isEqualTo(ReconciliationOutcome.CONVERGED);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void workflowStopsAfterOneClosedNonRetryableActivityFailure() {
        for (ReconciliationFailure.Code code : Set.of(
                ReconciliationFailure.Code.RECONCILIATION_NOT_RECONCILABLE,
                ReconciliationFailure.Code.RECONCILIATION_MISSING)) {
            AtomicInteger attempts = new AtomicInteger();

            Throwable failure = catchThrowable(() -> runWorkflow(ref -> {
                attempts.incrementAndGet();
                throw ApplicationFailure.newNonRetryableFailure(
                    "reconciliation rejected", code.name());
            }));

            ApplicationFailure applicationFailure = findCause(
                failure, ApplicationFailure.class);
            assertThat(applicationFailure.getType()).isEqualTo(code.name());
            assertThat(applicationFailure.isNonRetryable()).isTrue();
            assertThat(attempts).hasValue(1);
        }
    }

    @Test
    void workflowAndReconciliationPackagesCannotDependOnForbiddenBoundaries() {
        JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(WORKFLOW_PACKAGE, RECONCILIATION_PACKAGE);
        ArchRule workflowBoundary = com.tngtech.archunit.lang.syntax.ArchRuleDefinition
            .noClasses()
            .that()
            .resideInAnyPackage(WORKFLOW_PACKAGE, WORKFLOW_PACKAGE + "..")
            .should()
            .dependOnClassesThat(DescribedPredicate.describe(
                "a capability, Provider mutation/client/SDK/port, HTTP/network, "
                    + "gRPC/Netty, or direct Temporal client/serviceclient",
                target -> WORKFLOW_CAPABILITY_TYPES.contains(target.getName())
                    || isProviderCapabilityOrIntegration(target)
                    || isHttpNetworkGrpcNettyOrTemporalClient(target)));
        ArchRule reconciliationBoundary =
            com.tngtech.archunit.lang.syntax.ArchRuleDefinition
                .noClasses()
                .that()
                .resideInAnyPackage(RECONCILIATION_PACKAGE, RECONCILIATION_PACKAGE + "..")
                .should()
                .dependOnClassesThat(DescribedPredicate.describe(
                    "a Provider capability other than ProviderObservationPort, "
                        + "Provider mutation/client/SDK, HTTP/network, gRPC/Netty, "
                        + "or direct Temporal client/serviceclient",
                    target -> (!target.getName().equals(PROVIDER_OBSERVATION_PORT)
                            && isProviderCapabilityOrIntegration(target))
                        || isHttpNetworkGrpcNettyOrTemporalClient(target)));

        workflowBoundary.check(classes);
        reconciliationBoundary.check(classes);
        assertThat(ReconciliationWorkflowImpl.class.getPackageName())
            .endsWith(".temporal.workflow");
        assertThat(FencedReconciliationObservation.class.getPackageName())
            .endsWith(".worker.reconciliation");
    }

    private static boolean isProviderCapabilityOrIntegration(JavaClass target) {
        String name = target.getName().toLowerCase(Locale.ROOT);
        String simpleName = target.getSimpleName().toLowerCase(Locale.ROOT);
        return name.contains("provider")
            || name.contains("mutation")
            || name.contains("sdk")
            || simpleName.endsWith("client");
    }

    private static boolean isHttpNetworkGrpcNettyOrTemporalClient(JavaClass target) {
        String name = target.getName().toLowerCase(Locale.ROOT);
        return name.startsWith("java.net.")
            || name.startsWith("javax.net.")
            || name.startsWith("jakarta.ws.rs.")
            || name.startsWith("javax.ws.rs.")
            || name.startsWith("io.grpc.")
            || name.startsWith("io.netty.")
            || name.contains(".netty.")
            || name.contains(".http.")
            || name.contains(".httpclient.")
            || name.startsWith("io.temporal.client.")
            || name.startsWith("io.temporal.serviceclient.")
            || name.startsWith("org.springframework.http.")
            || name.startsWith("org.springframework.web.client.")
            || name.startsWith("org.springframework.web.reactive.function.client.")
            || name.startsWith("org.apache.http.")
            || name.startsWith("org.apache.hc.client")
            || name.startsWith("okhttp3.");
    }

    private static void assertSingleMethod(Class<?> type, String expectedName) {
        Method[] declared = type.getDeclaredMethods();
        assertThat(declared).hasSize(1);
        assertThat(declared[0].getName()).isEqualTo(expectedName);
        assertThat(declared[0].getParameterTypes())
            .containsExactly(ReconciliationWorkflowRef.class);
    }

    private static ReconciliationOutcome runWorkflow(
            ReconciliationActivities activities) {
        String taskQueue = "reconciliation-workflow-policy-" + UUID.randomUUID();
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker(taskQueue);
            worker.registerWorkflowImplementationTypes(ReconciliationWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
            environment.start();
            WorkflowClient client = environment.getWorkflowClient();
            ReconciliationWorkflow workflow = client.newWorkflowStub(
                ReconciliationWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId("reconciliation-workflow-policy-" + UUID.randomUUID())
                    .setTaskQueue(taskQueue)
                    .setWorkflowExecutionTimeout(Duration.ofSeconds(30))
                    .build());
            return workflow.reconcile(
                new ReconciliationWorkflowRef(TENANT_ID, INTENT_ID));
        }
    }

    private static <T extends Throwable> T findCause(
            Throwable failure, Class<T> type) {
        Throwable cursor = failure;
        while (cursor != null && !type.isInstance(cursor)) {
            cursor = cursor.getCause();
        }
        assertThat(cursor).isInstanceOf(type);
        return type.cast(cursor);
    }

}
