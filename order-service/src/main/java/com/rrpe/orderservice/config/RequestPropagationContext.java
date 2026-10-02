package com.rrpe.orderservice.config;

/** Request-scoped propagation data for synchronous service-to-service calls. */
public final class RequestPropagationContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private RequestPropagationContext() {}

    static void set(String requestId) { CURRENT.set(requestId); }
    public static String currentRequestId() { return CURRENT.get(); }
    static void clear() { CURRENT.remove(); }
}
