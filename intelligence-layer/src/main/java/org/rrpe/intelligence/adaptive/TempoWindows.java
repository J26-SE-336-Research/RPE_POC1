package org.rrpe.intelligence.adaptive;

import static org.rrpe.intelligence.adaptive.AdaptiveModels.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Fetches gateway-root traces, validates spans and computes chain p99 from complete root durations. */
@Component
public class TempoWindows {
    private final TelemetryHttp http;
    private final AdaptiveConfig.Connections connections;
    private final AdaptiveConfig.Settings settings;
    public TempoWindows(TelemetryHttp http, AdaptiveConfig.Connections connections, AdaptiveConfig.Settings settings) {
        this.http = http; this.connections = connections; this.settings = settings;
    }
    private record Span(String id, String parent, String service, String route, String method, long start, long end, boolean server, String peer) {}
    public record TraceSample(String id, String route, String method, List<String> services, double durationMs, long start, long end, Double deadlineMs) {}
    public record Collection(ChainWindow window, Double currentDeadlineMs, List<String> problems) {}

    public List<Collection> collect(Instant end, Map<String, Health> health) {
        Instant start = end.minus(settings.window());
        String query = "{ resource.service.name = \"" + TelemetryHttp.escape(connections.gatewayService()) + "\" && span.rrpe.proxy.direction = \"inbound\" }";
        var search = http.get(connections.tempoUrl().replaceAll("/$", "") + "/api/search?q=" + TelemetryHttp.encode(query)
                + "&start=" + start.getEpochSecond() + "&end=" + end.getEpochSecond() + "&limit=" + connections.traceLimit());
        var traces = search.path("traces");
        if (!traces.isArray()) throw new IllegalStateException("Tempo search lacks traces array");
        boolean complete = traces.size() < connections.traceLimit()
                && search.path("metrics").path("completedJobs").asInt(0) >= search.path("metrics").path("totalJobs").asInt(0);
        var groups = new LinkedHashMap<String, List<TraceSample>>(); var seen = new HashSet<String>();
        for (var trace : traces) {
            String id = trace.path("traceID").asString();
            if (id == null || !id.matches("[a-fA-F0-9]{16,32}") || !seen.add(id)) { complete = false; continue; }
            try {
                var sample = parseTrace(id, http.get(connections.tempoUrl().replaceAll("/$", "") + "/api/v2/traces/" + id + "?span_pruning=false"));
                if (sample.start() < start.getEpochSecond() * 1_000_000_000L || sample.end() > end.getEpochSecond() * 1_000_000_000L) continue;
                String chain = sample.method() + " " + sample.route() + " | " + String.join(" > ", sample.services());
                groups.computeIfAbsent(chain, ignored -> new ArrayList<>()).add(sample);
            } catch (RuntimeException exception) { complete = false; }
            if (Thread.currentThread().isInterrupted()) break;
        }
        var windows = new ArrayList<Collection>();
        for (var group : groups.entrySet()) {
            var samples = group.getValue(); TraceSample first = samples.get(0);
            String labels = "{service=\"" + TelemetryHttp.escape(connections.gatewayService()) + "\",namespace=\"" + TelemetryHttp.escape(connections.namespace())
                    + "\",method=\"" + TelemetryHttp.escape(first.method()) + "\",route=\"" + TelemetryHttp.escape(first.route()) + "\"}";
            String range = "[" + settings.window().toSeconds() + "s]";
            Double count = http.prometheus("sum(increase(sidecar_gateway_request_chains_completed_total" + labels + range + "))", end);
            Double failures = http.prometheus("sum(increase(sidecar_gateway_request_chains_deadline_exceeded_total" + labels + range + "))", end);
            var problems = new ArrayList<String>();
            Double deadline = configuredDeadline(first.method(), first.route(), end);
            boolean consistentBudget = deadline != null && samples.stream().allMatch(sample -> Objects.equals(sample.deadlineMs(), deadline));
            if (deadline == null) problems.add("Current route deadline is missing, stale or inconsistent across gateway replicas");
            else if (!consistentBudget) problems.add("Trace deadline budgets do not match the current Prometheus configuration");
            // Route counters cannot distinguish alternative paths. A whole missing trace blocks the window.
            long paths = groups.values().stream().filter(list -> list.get(0).route().equals(first.route()) && list.get(0).method().equals(first.method())).count();
            boolean covered = complete && consistentBudget && paths == 1 && count != null && count > 0 && failures != null
                    && Math.abs(samples.size() - count) < .5;
            if (!covered) problems.add("Incomplete chain coverage, mixed paths or missing request/failure counts");
            Health chainHealth = Health.HEALTHY;
            for (String service : first.services()) {
                Health current = health.getOrDefault(service, Health.INSUFFICIENT_DATA);
                if (!List.of(Health.HEALTHY, Health.STRESSED, Health.OVERLOADED).contains(current)) { chainHealth = Health.INSUFFICIENT_DATA; break; }
                if (current == Health.OVERLOADED) chainHealth = Health.OVERLOADED;
                else if (current == Health.STRESSED && chainHealth == Health.HEALTHY) chainHealth = Health.STRESSED;
            }
            windows.add(new Collection(new ChainWindow(group.getKey(), first.services(), start, end, Source.TEMPO, covered,
                    count, failures, percentile99(samples.stream().mapToDouble(TraceSample::durationMs).toArray()), chainHealth,
                    samples.stream().map(TraceSample::id).limit(5).toList()), deadline, List.copyOf(problems)));
        }
        return windows;
    }
    public Double configuredDeadline(String method, String route, Instant end) {
        // The budget metric has method/route labels, but no service label. Namespace is attached by PodMonitor.
        String metric = "sidecar_gateway_deadline_budget_milliseconds{namespace=\"" + TelemetryHttp.escape(connections.namespace())
                + "\",method=\"" + TelemetryHttp.escape(method) + "\",route=\"" + TelemetryHttp.escape(route) + "\"}";
        Double minimum = http.prometheus("min(" + metric + ")", end);
        Double maximum = http.prometheus("max(" + metric + ")", end);
        Double age = http.prometheus("max(time()-timestamp(" + metric + "))", end);
        if (minimum == null || maximum == null || age == null || minimum <= 0 || minimum != Math.rint(minimum)
                || !minimum.equals(maximum) || age > connections.maximumScrapeAge().toSeconds()) return null;
        return minimum;
    }
    public TraceSample parseTrace(String id, JsonNode trace) {
        if ("PARTIAL".equals(trace.path("status").asString()) || trace.path("status").asInt(0) == 1) {
            throw new IllegalArgumentException("Tempo returned a partial trace");
        }
        if (trace.has("trace")) trace = trace.get("trace");
        var resourceSpans = trace.path("resourceSpans");
        if (!resourceSpans.isArray()) resourceSpans = trace.path("batches");
        if (!resourceSpans.isArray()) throw new IllegalArgumentException("Missing OTLP resource spans");
        var spans = new ArrayList<Span>();
        for (var resource : resourceSpans) {
            String service = attributes(resource.path("resource").path("attributes")).get("service.name");
            var scopes = resource.path("scopeSpans"); if (!scopes.isArray()) scopes = resource.path("instrumentationLibrarySpans");
            for (var scope : scopes) for (var raw : scope.path("spans")) {
                var attrs = attributes(raw.path("attributes"));
                String name = attrs.getOrDefault("service.name", service);
                long from = Long.parseLong(raw.path("startTimeUnixNano").asString()); long to = Long.parseLong(raw.path("endTimeUnixNano").asString());
                if (name == null || to <= from) throw new IllegalArgumentException("Incomplete span timing or service");
                boolean server = "SPAN_KIND_SERVER".equals(raw.path("kind").asString()) || raw.path("kind").asInt(0) == 2;
                spans.add(new Span(raw.path("spanId").asString(), raw.path("parentSpanId").asString(""), name,
                        attrs.get("rrpe.chain.route"), attrs.get("http.request.method"), from, to, server,
                        "outbound".equals(attrs.get("rrpe.proxy.direction")) ? attrs.get("peer.service") : null));
            }
        }
        var roots = spans.stream().filter(s -> s.server() && s.service().equals(connections.gatewayService())).toList();
        if (roots.size() != 1) throw new IllegalArgumentException("Trace requires exactly one gateway request span");
        Span root = roots.get(0);
        if (root.route() == null || root.method() == null || root.id() == null || root.id().isBlank()) throw new IllegalArgumentException("Gateway chain identity missing");
        Map<String, Span> byId = new HashMap<>();
        for (Span span : spans) if (span.id() == null || span.id().isBlank() || byId.put(span.id(), span) != null) throw new IllegalArgumentException("Invalid duplicate span ID");
        var ordered = spans.stream().sorted(Comparator.comparingLong(Span::start)).toList(); var services = new ArrayList<String>();
        for (Span span : ordered) {
            if (span == root) { if (!services.contains(span.service())) services.add(span.service()); continue; }
            var visited = new HashSet<String>(); Span cursor = span;
            while (cursor != root) {
                if (!visited.add(cursor.id()) || !byId.containsKey(cursor.parent())) throw new IllegalArgumentException("Orphaned/incomplete chain spans");
                cursor = byId.get(cursor.parent());
            }
            if (span.start() < root.start() || span.end() > root.end()) throw new IllegalArgumentException("Chain activity exceeds completed gateway span");
            if (span.server()) services.add(span.service());
        }
        for (Span span : spans) if (span.peer() != null && spans.stream().noneMatch(child -> child.server()
                && child.parent().equals(span.id()) && child.service().replace("-service", "").equals(span.peer().replace("-service", "")))) {
            throw new IllegalArgumentException("Outbound hop has no correlated downstream server span");
        }
        if (services.isEmpty() || !services.get(0).equals(connections.gatewayService())) throw new IllegalArgumentException("Ordered path must start at gateway");
        String budget = null;
        for (var resource : resourceSpans) {
            var scopes = resource.path("scopeSpans"); if (!scopes.isArray()) scopes = resource.path("instrumentationLibrarySpans");
            for (var scope : scopes) for (var span : scope.path("spans")) if (root.id().equals(span.path("spanId").asString())) {
                budget = attributes(span.path("attributes")).get("rrpe.deadline.budget_ms");
            }
        }
        return new TraceSample(id, root.route(), root.method(), List.copyOf(services), (root.end() - root.start()) / 1_000_000.0, root.start(), root.end(),
                budget == null ? null : Double.valueOf(budget));
    }
    private Map<String, String> attributes(JsonNode attributes) {
        var result = new HashMap<String, String>();
        for (var item : attributes) {
            var value = item.path("value");
            if (value.has("stringValue")) result.put(item.path("key").asString(), value.path("stringValue").asString());
            else if (value.has("intValue")) result.put(item.path("key").asString(), value.path("intValue").asString());
        }
        return result;
    }
    public static double percentile99(double[] durations) {
        if (durations.length == 0) throw new IllegalArgumentException("p99 requires durations");
        double[] values = durations.clone(); Arrays.sort(values); return values[(int) Math.ceil(values.length * .99) - 1];
    }
}
