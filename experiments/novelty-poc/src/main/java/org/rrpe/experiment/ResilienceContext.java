package org.rrpe.experiment;

public record ResilienceContext(
        int version,
        String requestId,
        long deadlineEpochMillis,
        int retryBudget,
        int backpressure,
        
                boolean cancellationRequested) {
                

                        public ResilienceContext {
                if (version != 1) {
                        throw new IllegalArgumentException("Unsupported version");
                }

                if (requestId == null
                                || !requestId.matches("[0-9a-f]{32}")
                                || requestId.equals("00000000000000000000000000000000")) {
                        throw new IllegalArgumentException(
                                        "Request ID must be 32 lowercase hex characters and nonzero");
                }

                if (deadlineEpochMillis < 0
                                || deadlineEpochMillis > 9_007_199_254_740_991L) {
                        throw new IllegalArgumentException("Deadline outside experimental range");
                }

                if (retryBudget < 0 || retryBudget > 65_535) {
                        throw new IllegalArgumentException("Retry budget must be 0–65535");
                }

                if (backpressure != 0 && backpressure != 1) {
                        throw new IllegalArgumentException("Backpressure must be 0 or 1");
                }
        }
}

