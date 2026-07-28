package com.inforvans.accord.controlplane.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.inforvans.accord.controlplane.worker.temporal.TemporalWorkerLifecycle;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ReadinessState;

class WorkerDrainCoordinatorTest {
    @Test
    void drainRejectsAcquisitionAndReadinessBeforeWaiting() {
        List<String> order = new ArrayList<>();
        RecordingDrain scheduling = new RecordingDrain(order, true);
        WorkerDrainCoordinator coordinator = new WorkerDrainCoordinator(
            scheduling,
            Duration.ofSeconds(20),
            readiness -> order.add("readiness:" + readiness));

        assertThat(coordinator.state())
            .isEqualTo(WorkerDrainCoordinator.DrainState.DRAINED);
        coordinator.start();
        assertThat(coordinator.state())
            .isEqualTo(WorkerDrainCoordinator.DrainState.RUNNING);

        coordinator.stop();

        assertThat(order).containsExactly(
            "start",
            "readiness:" + ReadinessState.ACCEPTING_TRAFFIC,
            "quiesce",
            "readiness:" + ReadinessState.REFUSING_TRAFFIC,
            "await:PT20S");
        assertThat(coordinator.state())
            .isEqualTo(WorkerDrainCoordinator.DrainState.DRAINED);
        assertThat(coordinator.isRunning()).isFalse();
        assertThat(scheduling.forced).isFalse();
    }

    @Test
    void timeoutStopsLocalWaitingWithoutAcknowledgingWork() {
        List<String> order = new ArrayList<>();
        RecordingDrain scheduling = new RecordingDrain(order, false);
        WorkerDrainCoordinator coordinator = new WorkerDrainCoordinator(
            scheduling,
            Duration.ofSeconds(20),
            readiness -> order.add("readiness:" + readiness));

        coordinator.start();
        coordinator.stop();

        assertThat(order).endsWith("await:PT20S", "force-stop");
        assertThat(scheduling.forced).isTrue();
        assertThat(coordinator.state())
            .isEqualTo(WorkerDrainCoordinator.DrainState.DRAINED);
    }

    @Test
    void callbackRunsAfterDrainAndPhasePrecedesTemporalShutdown() {
        List<String> order = new ArrayList<>();
        RecordingDrain scheduling = new RecordingDrain(order, true);
        WorkerDrainCoordinator coordinator = new WorkerDrainCoordinator(
            scheduling,
            Duration.ofSeconds(20),
            readiness -> order.add("readiness:" + readiness));
        coordinator.start();

        coordinator.stop(() -> order.add("callback"));

        assertThat(order.get(order.size() - 1)).isEqualTo("callback");
        assertThat(coordinator.getPhase()).isEqualTo(200);
        assertThat(coordinator.getPhase()).isGreaterThan(TemporalWorkerLifecycle.PHASE);
        assertThat(coordinator.isAutoStartup()).isTrue();
    }

    private static final class RecordingDrain
            implements WorkerDrainCoordinator.DrainControl {
        private final List<String> order;
        private final boolean drains;
        private boolean forced;

        private RecordingDrain(List<String> order, boolean drains) {
            this.order = order;
            this.drains = drains;
        }

        @Override
        public void startScheduling() {
            order.add("start");
        }

        @Override
        public void quiesce() {
            order.add("quiesce");
        }

        @Override
        public boolean awaitIdle(Duration timeout) {
            order.add("await:" + timeout);
            return drains;
        }

        @Override
        public void forceStop() {
            forced = true;
            order.add("force-stop");
        }
    }
}
