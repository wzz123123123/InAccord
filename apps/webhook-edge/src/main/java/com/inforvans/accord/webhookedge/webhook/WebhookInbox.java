package com.inforvans.accord.webhookedge.webhook;

@FunctionalInterface
public interface WebhookInbox {
    WebhookRecordOutcome record(ProviderWebhookSignal signal);

    final class UnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnavailableException() {
            super("webhook inbox unavailable");
        }
    }
}
