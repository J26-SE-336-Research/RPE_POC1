package org.rrpe.intelligence;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"logging.level.root=WARN", "debug=false"})
class RecommendationEndpointTests {
    @LocalServerPort
    int port;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private ObjectNode fixture(String name) throws Exception {
        return (ObjectNode) mapper.readTree(Files.readString(Path.of("examples/member3/" + name + ".request.json"))
                .replace("\uFEFF", ""));
    }

    private HttpResponse<String> post(String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/recommendations"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode plan(String name) throws Exception {
        var response = post(mapper.writeValueAsString(fixture(name)));
        assertEquals(200, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    @Test
    void healthyLeavesExistingPoliciesAlone() throws Exception {
        var plan = plan("healthy");
        assertEquals("HEALTHY", plan.get("status").asString());
        assertEquals(0, plan.get("recommendations").size());
    }

    @Test
    void overloadedHasConsumableActionsAndExpiry() throws Exception {
        var plan = plan("overloaded");
        assertEquals("1.0", plan.get("schemaVersion").asString());
        assertEquals("ADVISORY", plan.get("mode").asString());
        assertEquals("order-service", plan.get("serviceName").asString());
        assertEquals("OVERLOADED", plan.get("status").asString());
        Set<String> actions = new HashSet<>();
        for (var item : plan.get("recommendations")) {
            actions.add(item.get("action").asString());
        }
        assertEquals(Set.of("RATE_LIMIT", "CONCURRENCY_LIMIT", "DISABLE_RETRIES", "ENABLE_CIRCUIT_BREAKER"), actions);
        assertEquals(75.0, plan.get("recommendations").get(0).get("parameters").get("maxRequestsPerSecond").asDouble());
        assertEquals(60, Duration.between(Instant.parse(plan.get("generatedAt").asString()),
                Instant.parse(plan.get("expiresAt").asString())).toSeconds());
    }

    @Test
    void stressedDoesNotEnableCircuitBreaker() throws Exception {
        var plan = plan("stressed");
        assertEquals("STRESSED", plan.get("status").asString());
        assertEquals(3, plan.get("recommendations").size());
        for (var item : plan.get("recommendations")) {
            assertNotEquals("ENABLE_CIRCUIT_BREAKER", item.get("action").asString());
        }
    }

    @Test
    void missingSnapshotsReturnBadRequest() throws Exception {
        assertEquals(400, post("{}").statusCode());
    }

    @Test
    void invalidMetricsAndIncompatibleSnapshotsReturnBadRequest() throws Exception {
        for (String field : new String[]{"requestRate", "p95LatencyMs", "errorRate", "retryCount",
                "rejectedRequestCount", "deadlineFailureCount", "cancellationCount", "inFlightRequests", "successfulThroughput"}) {
            var body = fixture("overloaded");
            ((ObjectNode) body.get("recent")).put(field, -1);
            assertEquals(400, post(mapper.writeValueAsString(body)).statusCode(), field);
        }
        var body = fixture("overloaded");
        ((ObjectNode) body.get("recent")).put("errorRate", 1.2);
        assertEquals(400, post(mapper.writeValueAsString(body)).statusCode());
        body = fixture("overloaded");
        ((ObjectNode) body.get("baseline")).put("serviceName", "payment-service");
        assertEquals(400, post(mapper.writeValueAsString(body)).statusCode());
        body = fixture("overloaded");
        ((ObjectNode) body.get("baseline")).put("collectedAt", "2026-10-02T04:00:00Z");
        assertEquals(400, post(mapper.writeValueAsString(body)).statusCode());
    }
}
