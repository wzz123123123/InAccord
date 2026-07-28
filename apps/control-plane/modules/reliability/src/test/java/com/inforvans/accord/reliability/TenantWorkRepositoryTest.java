package com.inforvans.accord.reliability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TenantWorkRepositoryTest extends PostgreSqlReliabilityTestSupport {
    private final TenantWorkRepository repository = new TenantWorkRepository();

    @Test
    void readyTenantsAreGrantedByOldestGrantBeforeRepeatedSignals() throws Exception {
        List<UUID> tenants = List.of(TENANT_ID, OTHER_TENANT_ID,
            UUID.fromString("10000000-0000-0000-0000-000000000003"));
        for (UUID tenant : tenants) {
            signal(tenant);
        }
        for (UUID expected : tenants) {
            TenantWorkPermit permit = inUnscopedWorker(tx -> repository.acquire(
                tx, "worker-a", Duration.ofSeconds(30)).orElseThrow());
            assertEquals(expected, permit.tenantId());
            inUnscopedWorker(tx -> {
                repository.finishNoKnownWork(tx, permit);
                return null;
            });
            signal(TENANT_ID);
        }
    }

    @Test
    void reusedOwnerCannotReuseAReleasedFence() throws Exception {
        signal(TENANT_ID);
        TenantWorkPermit first = inUnscopedWorker(tx -> repository.acquire(
            tx, "same-owner", Duration.ofSeconds(30)).orElseThrow());
        inUnscopedWorker(tx -> {
            repository.finishNoKnownWork(tx, first);
            return null;
        });
        signal(TENANT_ID);
        TenantWorkPermit second = inUnscopedWorker(tx -> repository.acquire(
            tx, "same-owner", Duration.ofSeconds(30)).orElseThrow());
        assertEquals(first.fence().generation() + 1, second.fence().generation());
        assertNotEquals(first.fence().token(), second.fence().token());
        assertThrows(Exception.class, () -> inWorker(TENANT_ID, tx -> {
            repository.lock(tx, first);
            return null;
        }));
    }

    private void signal(UUID tenantId) throws Exception {
        inApi(tenantId, tx -> {
            tx.execute("SELECT accord_security.signal_reliability_tenant_work()");
            return null;
        });
    }
}
