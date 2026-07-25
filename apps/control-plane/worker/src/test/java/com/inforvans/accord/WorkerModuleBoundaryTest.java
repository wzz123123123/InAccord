package com.inforvans.accord;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class WorkerModuleBoundaryTest {
    private static final Set<String> REQUIRED_MODULES = Set.of("platformkernel", "reliability");

    @Test
    void workerLoadsTheSameControlPlaneModules() {
        ApplicationModules modules = ApplicationModules.of(ControlWorkerApplication.class);
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
            .containsExactlyInAnyOrderElementsOf(REQUIRED_MODULES);
        modules.verify();
    }
}
