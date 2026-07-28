package com.inforvans.accord.reliability;

public final class LostExternalIntentFence extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public LostExternalIntentFence(String message) {
        super(message);
    }
}
