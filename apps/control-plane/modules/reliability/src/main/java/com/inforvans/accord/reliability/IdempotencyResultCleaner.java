package com.inforvans.accord.reliability;

import java.util.Objects;
import org.jooq.DSLContext;

public final class IdempotencyResultCleaner {
    public int clean(DSLContext tx, int batchSize) {
        Objects.requireNonNull(tx, "tx");
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalArgumentException("batchSize must be between 1 and 500");
        }
        org.jooq.Record row = tx.fetchOne("""
            SELECT accord_security.delete_expired_completed_idempotency_results(
              CAST(? AS integer)) AS deleted_count
            """, batchSize);
        if (row == null) {
            throw new IllegalStateException("cleanup returned no row");
        }
        return Objects.requireNonNull(row.get("deleted_count", Integer.class));
    }
}
