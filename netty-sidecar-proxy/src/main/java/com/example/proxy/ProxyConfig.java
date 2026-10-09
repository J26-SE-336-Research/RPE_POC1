package com.example.proxy;

public record ProxyConfig(
        String listenHost,
        int listenPort,
        String metricsListenHost,
        int metricsListenPort,
        long defaultDeadlineMillis,
        long requestContextTtlMillis,
        boolean detectInboundDeadline,
        boolean gatewaySidecar
) {
    public static ProxyConfig fromEnvironment() {
        return new ProxyConfig(
                env("PROXY_LISTEN_HOST", "0.0.0.0"),
                intEnv("PROXY_LISTEN_PORT", 8080),
                env("METRICS_LISTEN_HOST", "0.0.0.0"),
                intEnv("METRICS_LISTEN_PORT", 9090),
                longEnv("DEFAULT_DEADLINE_MILLIS", 5000),
                positiveLongEnv("REQUEST_CONTEXT_TTL_MILLIS", 20000),
                Boolean.parseBoolean(env("PROXY_DETECT_INBOUND_DEADLINE", "true")),
                "gateway".equalsIgnoreCase(env("PROXY_ROLE", "service"))
        );
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static int intEnv(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return defaultValue;
        return Integer.parseInt(value);
    }

    private static long longEnv(String key, long defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return defaultValue;
        return Long.parseLong(value);
    }

    private static long positiveLongEnv(String key, long defaultValue) {
        long value = longEnv(key, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException(key + " must be greater than 0");
        }
        return value;
    }
}
