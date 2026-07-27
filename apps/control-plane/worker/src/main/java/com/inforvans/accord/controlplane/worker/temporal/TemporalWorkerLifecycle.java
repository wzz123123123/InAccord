package com.inforvans.accord.controlplane.worker.temporal;

import com.inforvans.accord.controlplane.worker.temporal.workflow.ReadOnlyReconciliationActivity;
import com.inforvans.accord.controlplane.worker.temporal.workflow.ReconciliationWorkflowImpl;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.springframework.context.SmartLifecycle;

public final class TemporalWorkerLifecycle implements SmartLifecycle {
    public static final int PHASE = 100;

    private final TemporalConnectionProperties properties;
    private final TemporalServiceStubsFactory stubsFactory;
    private final ReadOnlyReconciliationActivity activity;

    private volatile boolean running;
    private WorkflowServiceStubs stubs;
    private WorkflowClient client;
    private WorkerFactory factory;
    private Worker worker;

    public TemporalWorkerLifecycle(
            TemporalConnectionProperties properties,
            TemporalServiceStubsFactory stubsFactory,
            ReadOnlyReconciliationActivity activity) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.stubsFactory = Objects.requireNonNull(stubsFactory, "stubsFactory");
        this.activity = Objects.requireNonNull(activity, "activity");
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        WorkflowServiceStubs startingStubs = null;
        WorkerFactory startingFactory = null;
        try {
            startingStubs = stubsFactory.create(properties);
            startingStubs.connect(properties.rpcTimeout());
            startingStubs.blockingStub()
                .withDeadlineAfter(properties.rpcTimeout().toNanos(), TimeUnit.NANOSECONDS)
                .describeNamespace(DescribeNamespaceRequest.newBuilder()
                    .setNamespace(properties.namespace())
                    .build());

            WorkflowClient startingClient = WorkflowClient.newInstance(
                startingStubs,
                WorkflowClientOptions.newBuilder()
                    .setNamespace(properties.namespace())
                    .validateAndBuildWithDefaults());
            startingFactory = WorkerFactory.newInstance(startingClient);
            Worker startingWorker = startingFactory.newWorker(properties.taskQueue());
            startingWorker.registerWorkflowImplementationTypes(
                ReconciliationWorkflowImpl.class);
            startingWorker.registerActivitiesImplementations(activity);
            startingFactory.start();

            stubs = startingStubs;
            client = startingClient;
            factory = startingFactory;
            worker = startingWorker;
            running = true;
        } catch (RuntimeException | Error failure) {
            unwind(startingFactory, startingStubs, properties.shutdownTimeout());
            throw failure;
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        Worker stoppingWorker = worker;
        WorkerFactory stoppingFactory = factory;
        WorkflowServiceStubs stoppingStubs = stubs;
        worker = null;
        factory = null;
        client = null;
        stubs = null;

        long deadline = System.nanoTime() + properties.shutdownTimeout().toNanos();
        if (stoppingWorker != null) {
            stoppingWorker.suspendPolling();
        }
        shutdownFactory(stoppingFactory, deadline);
        shutdownStubs(stoppingStubs, deadline);
    }

    @Override
    public void stop(Runnable callback) {
        try {
            stop();
        } finally {
            callback.run();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    WorkflowServiceStubs serviceStubs() {
        return stubs;
    }

    WorkerFactory workerFactory() {
        return factory;
    }

    WorkflowClient workflowClient() {
        WorkflowClient current = client;
        if (!running || current == null) {
            throw new IllegalStateException("Temporal worker lifecycle is not running");
        }
        return current;
    }

    private static void unwind(WorkerFactory factory, WorkflowServiceStubs stubs,
            Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        shutdownFactory(factory, deadline);
        shutdownStubs(stubs, deadline);
    }

    private static void shutdownFactory(WorkerFactory factory, long deadlineNanos) {
        if (factory == null) {
            return;
        }
        factory.shutdown();
        factory.awaitTermination(remaining(deadlineNanos), TimeUnit.NANOSECONDS);
        if (!factory.isTerminated()) {
            factory.shutdownNow();
            factory.awaitTermination(remaining(deadlineNanos), TimeUnit.NANOSECONDS);
        }
    }

    private static void shutdownStubs(WorkflowServiceStubs stubs, long deadlineNanos) {
        if (stubs == null) {
            return;
        }
        stubs.shutdown();
        if (!stubs.awaitTermination(remaining(deadlineNanos), TimeUnit.NANOSECONDS)) {
            stubs.shutdownNow();
            stubs.awaitTermination(remaining(deadlineNanos), TimeUnit.NANOSECONDS);
        }
    }

    private static long remaining(long deadlineNanos) {
        return Math.max(0, deadlineNanos - System.nanoTime());
    }
}
