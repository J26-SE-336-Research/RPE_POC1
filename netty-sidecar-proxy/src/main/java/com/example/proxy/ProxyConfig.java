package com.example.proxy;

public record ProxyConfig(
        String listenHost,
        int listenPort,
        String backendHost,
        int backendPort,
        long defaultDeadlineMillis,
        boolean detectInboundDeadline
) {
    public ProxyConfig(String listenHost, int listenPort, String backendHost, int backendPort) {
        this(listenHost, listenPort, backendHost, backendPort, 5000, true);
    }

    public static ProxyConfig fromEnvironment() {
        return new ProxyConfig(
                env("PROXY_LISTEN_HOST", "0.0.0.0"),
                intEnv("PROXY_LISTEN_PORT", 8080),
                env("BACKEND_HOST", "localhost"),
                intEnv("BACKEND_PORT", 9000),
                longEnv("DEFAULT_DEADLINE_MILLIS", 5000),
                Boolean.parseBoolean(env("PROXY_DETECT_INBOUND_DEADLINE", "true"))
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
}
