package com.example.proxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProxyConfigTest {
    @Test
    void recordStoresConfiguration() {
        ProxyConfig config = new ProxyConfig("0.0.0.0", 8080, "localhost", 9000);

        assertEquals("0.0.0.0", config.listenHost());
        assertEquals(8080, config.listenPort());
        assertEquals("localhost", config.backendHost());
        assertEquals(9000, config.backendPort());
    }
}
