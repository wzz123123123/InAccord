package com.inforvans.accord.reliability;

public sealed interface ReconciliationResolution
        permits ReconciliationResolution.Terminal,
                ReconciliationResolution.StillUnknown {
    sealed interface Terminal extends ReconciliationResolution
            permits Succeeded, ConfirmedNoEffect, Diverged {}

    record Succeeded(String outcomeDigest, String providerRequestId)
            implements Terminal {
        public Succeeded {
            outcomeDigest = ReliabilityValues.digest(outcomeDigest, "outcomeDigest");
            providerRequestId = ReliabilityValues.optionalSafeIdentifier(
                providerRequestId, "providerRequestId");
        }
    }

    record ConfirmedNoEffect(String evidenceDigest, String providerRequestId)
            implements Terminal {
        public ConfirmedNoEffect {
            evidenceDigest = ReliabilityValues.digest(evidenceDigest, "evidenceDigest");
            providerRequestId = ReliabilityValues.optionalSafeIdentifier(
                providerRequestId, "providerRequestId");
        }
    }

    record Diverged(String evidenceDigest, String errorCode, String providerRequestId)
            implements Terminal {
        public Diverged {
            evidenceDigest = ReliabilityValues.digest(evidenceDigest, "evidenceDigest");
            errorCode = ReliabilityValues.errorCode(errorCode);
            providerRequestId = ReliabilityValues.optionalSafeIdentifier(
                providerRequestId, "providerRequestId");
        }
    }

    record StillUnknown(String errorCode, String providerRequestId)
            implements ReconciliationResolution {
        public StillUnknown {
            errorCode = ReliabilityValues.errorCode(errorCode);
            providerRequestId = ReliabilityValues.optionalSafeIdentifier(
                providerRequestId, "providerRequestId");
        }
    }
}
