package com.inforvans.accord.webhookedge.binding;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface BindingResolver {
    Optional<WebhookBinding> resolve(UUID bindingId);

    final class UnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnavailableException() {
            super("binding projection unavailable");
        }
    }
}
