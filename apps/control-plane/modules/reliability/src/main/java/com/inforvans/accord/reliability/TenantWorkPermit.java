package com.inforvans.accord.reliability;

import java.util.Objects;
import java.util.UUID;

public record TenantWorkPermit(UUID tenantId, MessageFence fence) {
    public TenantWorkPermit {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(fence, "fence");
    }
}
