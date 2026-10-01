package com.example.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProxyConfigTest {
    @Test
    void recordStoresConfiguration() {
        ProxyConfig config = new ProxyConfig("0.0.0.0", 8080, 5000, true);

        assertEquals("0.0.0.0", config.listenHost());
        assertEquals(8080, config.listenPort());
        assertEquals(5000, config.defaultDeadlineMillis());
        assertTrue(config.detectInboundDeadline());
    }
}
