package com.inforvans.accord.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.TestcontainersConfiguration;

class TestcontainersConfigurationTest {
    private static final String RYUK_IMAGE =
        "testcontainers/ryuk:0.12.0"
            + "@sha256:dd3f023a6ed7015b3f95a49ccd65a2daf0c56e681422c12952b19a810dfa6298";
    private static final String TINY_IMAGE =
        "alpine:3.17"
            + "@sha256:8fc3dacfb6d69da8d44e42390de777e48577085db99aa4e4af35f483eb08b989";

    @Test
    void helperImagesAreDigestPinnedAndRyukRemainsEnabled() {
        TestcontainersConfiguration configuration =
            TestcontainersConfiguration.getInstance();

        assertEquals(RYUK_IMAGE,
            configuration.getEnvVarOrProperty("ryuk.container.image", ""));
        assertEquals(TINY_IMAGE,
            configuration.getEnvVarOrProperty("tinyimage.container.image", ""));
        assertFalse(Boolean.parseBoolean(
            System.getenv().getOrDefault("TESTCONTAINERS_RYUK_DISABLED", "false")));
    }
}
