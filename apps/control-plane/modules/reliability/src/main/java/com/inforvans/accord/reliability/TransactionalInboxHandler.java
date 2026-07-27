package com.inforvans.accord.reliability;

import org.jooq.DSLContext;

@FunctionalInterface
public interface TransactionalInboxHandler {
    HandlerReceipt handle(DSLContext tx, LeasedInboxMessage message);
}
