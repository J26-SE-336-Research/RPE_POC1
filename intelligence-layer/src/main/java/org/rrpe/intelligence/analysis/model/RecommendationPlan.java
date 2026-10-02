package org.rrpe.intelligence.analysis.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RecommendationPlan(
        String schemaVersion,
        String mode,
        String serviceName,
        HealthStatus status,
        Instant sourceCollectedAt,
        Instant generatedAt,
        Instant expiresAt,
        List<String> evidence,
        List<Recommendation> recommendations
) {
    public RecommendationPlan {
        evidence = List.copyOf(evidence);
        recommendations = List.copyOf(recommendations);
    }

    public enum Action {
        RATE_LIMIT, CONCURRENCY_LIMIT, DISABLE_RETRIES, ENABLE_CIRCUIT_BREAKER
    }

    public record Recommendation(Action action, Map<String, Number> parameters, String reason) {
        public Recommendation {
            parameters = Map.copyOf(parameters);
        }
    }
}
