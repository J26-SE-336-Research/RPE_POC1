package com.example.proxy;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.LongAdder;

/** Metrics maintained by the sidecar proxy and exposed to Prometheus. */
public final class ProxyMetrics {
    private static final LongAdder INBOUND_REQUESTS = new LongAdder();

    private ProxyMetrics() {}

    public static void recordInboundRequest() {
        INBOUND_REQUESTS.increment();
    }

    public static byte[] prometheusText() {
        String exposition = "# HELP sidecar_requests_total Total inbound HTTP requests observed by the sidecar proxy.\n"
                + "# TYPE sidecar_requests_total counter\n"
                + "sidecar_requests_total " + INBOUND_REQUESTS.sum() + "\n";
        return exposition.getBytes(StandardCharsets.UTF_8);
    }
}
