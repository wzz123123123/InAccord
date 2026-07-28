package com.inforvans.accord.controlplane.worker.reconciliation;

import java.time.Duration;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("accord.reconciliation")
public record ReconciliationRuntimeProperties(String instanceId, Duration claimLease) {
    private static final Pattern INSTANCE_ID =
        Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");

    public ReconciliationRuntimeProperties {
        if (instanceId == null || !INSTANCE_ID.matcher(instanceId).matches()) {
            throw new IllegalArgumentException("invalid reconciliation instance ID");
        }
        if (!Duration.ofSeconds(8).equals(claimLease)) {
            throw new IllegalArgumentException("reconciliation claim lease must be PT8S");
        }
    }
}
