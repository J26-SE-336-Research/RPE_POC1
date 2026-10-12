package org.rrpe.experiment;

import java.util.Objects;

public final class DelimitedContextCodec {

    private DelimitedContextCodec() {
    }

    public static String encode(ResilienceContext context) {
        Objects.requireNonNull(context, "context");

        return "v=" + context.version()
                + ";rid=" + context.requestId()
                + ";dl=" + context.deadlineEpochMillis()
                + ";rb=" + context.retryBudget()
                + ";bp=" + context.backpressure()
                + ";cs=" + (context.cancellationRequested() ? "1" : "0");
    }
}