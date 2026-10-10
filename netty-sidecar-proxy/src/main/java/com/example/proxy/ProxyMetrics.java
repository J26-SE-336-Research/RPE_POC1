package com.example.proxy;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Metrics maintained by the sidecar proxy and exposed to Prometheus. */
public final class ProxyMetrics {
    private static final LongAdder INBOUND_REQUESTS = new LongAdder();
    private static final AtomicLong INBOUND_IN_FLIGHT = new AtomicLong();
    private static final LongAdder INBOUND_SUCCESSFUL_REQUESTS = new LongAdder();
    private static final LongAdder INBOUND_FAILED_REQUESTS = new LongAdder();
    private static final LongAdder INBOUND_DEADLINE_EXCEEDED_REQUESTS = new LongAdder();
    private static final LongAdder INBOUND_CANCELLATION_REQUESTS = new LongAdder();
    private static final double[] INBOUND_LATENCY_BUCKETS_SECONDS = {
            0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0
    };
    private static final AtomicLongArray INBOUND_LATENCY_BUCKET_COUNTS =
            new AtomicLongArray(INBOUND_LATENCY_BUCKETS_SECONDS.length);
    private static final LongAdder INBOUND_LATENCY_COUNT = new LongAdder();
    private static final LongAdder INBOUND_LATENCY_SUM_NANOS = new LongAdder();

    private ProxyMetrics() {}

    public static InboundRequestTracker beginInboundRequest() {
        INBOUND_REQUESTS.increment();
        INBOUND_IN_FLIGHT.incrementAndGet();
        return new InboundRequestTracker();
    }

    public static long inboundRequestsInFlight() {
        return INBOUND_IN_FLIGHT.get();
    }

    public static final class InboundRequestTracker {
        private final AtomicBoolean finished = new AtomicBoolean();

        public void finish() {
            if (finished.compareAndSet(false, true)) {
                INBOUND_IN_FLIGHT.decrementAndGet();
            }
        }
    }

    public static void recordInboundSuccessfulRequest(int responseStatus) {
        // For overload analysis, client-error responses still represent work
        // completed by the service. Exclude 429 because it is an overload or
        // rate-limiting signal, and exclude 5xx server failures.
        if (responseStatus >= 200 && responseStatus < 500 && responseStatus != 429) {
            INBOUND_SUCCESSFUL_REQUESTS.increment();
        }
    }

    public static void recordInboundFailure(int responseStatus) {
        if (responseStatus == 429 || responseStatus == 502
                || responseStatus == 503 || responseStatus == 504) {
            INBOUND_FAILED_REQUESTS.increment();
        }
    }

    public static void recordInboundDeadlineExceeded() {
        INBOUND_DEADLINE_EXCEEDED_REQUESTS.increment();
    }

    public static void recordInboundCancellation() {
        INBOUND_CANCELLATION_REQUESTS.increment();
    }

    public static void recordInboundRequestDuration(long durationNanos) {
        long nonNegativeDurationNanos = Math.max(0, durationNanos);
        double durationSeconds = nonNegativeDurationNanos / 1_000_000_000.0;
        for (int i = 0; i < INBOUND_LATENCY_BUCKETS_SECONDS.length; i++) {
            if (durationSeconds <= INBOUND_LATENCY_BUCKETS_SECONDS[i]) {
                INBOUND_LATENCY_BUCKET_COUNTS.incrementAndGet(i);
            }
        }
        INBOUND_LATENCY_COUNT.increment();
        INBOUND_LATENCY_SUM_NANOS.add(nonNegativeDurationNanos);
    }

    public static byte[] prometheusText() {
        String exposition = "# HELP sidecar_requests_total Total inbound HTTP requests observed by the sidecar proxy.\n"
                + "# TYPE sidecar_requests_total counter\n"
                + "sidecar_requests_total " + INBOUND_REQUESTS.sum() + "\n"
                + "# HELP sidecar_inbound_requests_in_flight Current number of inbound requests being processed by the sidecar.\n"
                + "# TYPE sidecar_inbound_requests_in_flight gauge\n"
                + "sidecar_inbound_requests_in_flight " + inboundRequestsInFlight() + "\n"
                + "# HELP sidecar_inbound_requests_successful_total Total inbound requests with a 2xx-4xx response successfully forwarded, excluding 429.\n"
                + "# TYPE sidecar_inbound_requests_successful_total counter\n"
                + "sidecar_inbound_requests_successful_total " + INBOUND_SUCCESSFUL_REQUESTS.sum() + "\n"
                + "# HELP sidecar_inbound_requests_failed_total Total inbound requests receiving HTTP status 429, 502, 503, or 504.\n"
                + "# TYPE sidecar_inbound_requests_failed_total counter\n"
                + "sidecar_inbound_requests_failed_total " + INBOUND_FAILED_REQUESTS.sum() + "\n"
                + "# HELP sidecar_deadline_exceeded_requests_total Total inbound request chains whose deadline was exceeded.\n"
                + "# TYPE sidecar_deadline_exceeded_requests_total counter\n"
                + "sidecar_deadline_exceeded_requests_total " + INBOUND_DEADLINE_EXCEEDED_REQUESTS.sum() + "\n"
                + "# HELP sidecar_cancellation_requests_total Total inbound request chains marked for cancellation.\n"
                + "# TYPE sidecar_cancellation_requests_total counter\n"
                + "sidecar_cancellation_requests_total " + INBOUND_CANCELLATION_REQUESTS.sum() + "\n"
                + "# HELP sidecar_inbound_request_duration_seconds Time from inbound interception until the backend response is received.\n"
                + "# TYPE sidecar_inbound_request_duration_seconds histogram\n"
                + inboundDurationHistogramText();
        return exposition.getBytes(StandardCharsets.UTF_8);
    }

    private static String inboundDurationHistogramText() {
        StringBuilder exposition = new StringBuilder();
        for (int i = 0; i < INBOUND_LATENCY_BUCKETS_SECONDS.length; i++) {
            exposition.append("sidecar_inbound_request_duration_seconds_bucket{le=\"")
                    .append(INBOUND_LATENCY_BUCKETS_SECONDS[i])
                    .append("\"} ")
                    .append(INBOUND_LATENCY_BUCKET_COUNTS.get(i))
                    .append('\n');
        }
        exposition.append("sidecar_inbound_request_duration_seconds_bucket{le=\"+Inf\"} ")
                .append(INBOUND_LATENCY_COUNT.sum())
                .append('\n')
                .append("sidecar_inbound_request_duration_seconds_count ")
                .append(INBOUND_LATENCY_COUNT.sum())
                .append('\n')
                .append("sidecar_inbound_request_duration_seconds_sum ")
                .append(INBOUND_LATENCY_SUM_NANOS.sum() / 1_000_000_000.0)
                .append('\n');
        return exposition.toString();
    }
}
