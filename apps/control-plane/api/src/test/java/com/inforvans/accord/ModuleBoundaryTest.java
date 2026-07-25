package com.inforvans.accord;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleBoundaryTest {
    private static final Set<String> REQUIRED_MODULES = Set.of("platformkernel", "reliability");

    @Test
    void controlPlaneModulesHaveNoCyclesOrUndeclaredAccess() {
        ApplicationModules modules = ApplicationModules.of(ControlApiApplication.class);
        assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
            .containsExactlyInAnyOrderElementsOf(REQUIRED_MODULES);
        modules.verify();
    }
}
