package com.inforvans.accord.reliability;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;

final class IdempotencyResultCleanerTest {
    private final IdempotencyResultCleaner cleaner = new IdempotencyResultCleaner();

    @Test
    void rejectsEveryOutOfRangeBatchBeforeIssuingSql() {
        DSLContext sqlMustNotRun = (DSLContext) Proxy.newProxyInstance(
            DSLContext.class.getClassLoader(), new Class<?>[] {DSLContext.class},
            (proxy, method, arguments) -> {
                throw new AssertionError("invalid batch attempted SQL");
            });
        assertThrows(IllegalArgumentException.class, () -> cleaner.clean(sqlMustNotRun, 0));
        assertThrows(IllegalArgumentException.class, () -> cleaner.clean(sqlMustNotRun, -1));
        assertThrows(IllegalArgumentException.class, () -> cleaner.clean(sqlMustNotRun, 501));
    }
}
