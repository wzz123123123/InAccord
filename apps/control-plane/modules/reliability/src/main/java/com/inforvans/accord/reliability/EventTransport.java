package com.inforvans.accord.reliability;

import java.time.Duration;

@FunctionalInterface
public interface EventTransport {
    DeliveryReceipt deliver(LeasedEvent event, Duration timeout);
}
