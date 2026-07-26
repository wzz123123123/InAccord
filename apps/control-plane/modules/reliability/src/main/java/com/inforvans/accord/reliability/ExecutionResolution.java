package com.inforvans.accord.reliability;

public sealed interface ExecutionResolution
        permits ExecutionResolution.Succeeded,
                ExecutionResolution.ConfirmedNoEffect,
                ExecutionResolution.OutcomeUnknown {
    record Succeeded(String outcomeDigest, String providerRequestId)
            implements ExecutionResolution {
        public Succeeded {
            outcomeDigest = ReliabilityValues.digest(outcomeDigest, "outcomeDigest");
            providerRequestId = ReliabilityValues.optionalSafeIdentifier(
                providerRequestId, "providerRequestId");
        }
    }

    record ConfirmedNoEffect(String evidenceDigest, String providerRequestId)
            implements ExecutionResolution {
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
