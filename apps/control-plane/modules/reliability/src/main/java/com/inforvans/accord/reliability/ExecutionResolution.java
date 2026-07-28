package com.inforvans.accord.reliability;

public sealed interface ExecutionResolution
        permits ExecutionResolution.Terminal,
                ExecutionResolution.OutcomeUnknown {
    sealed interface Terminal extends ExecutionResolution
            permits Succeeded, ConfirmedNoEffect {}

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

    record OutcomeUnknown(String errorCode, String providerRequestId)
            implements ExecutionResolution {
        public OutcomeUnknown {
            errorCode = ReliabilityValues.errorCode(errorCode);
            providerRequestId = ReliabilityValues.optionalSafeIdentifier(
                providerRequestId, "providerRequestId");
        }
    }
}
