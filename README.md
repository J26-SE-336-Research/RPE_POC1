# RRPE E-Commerce Microservices PoC

A small order-processing system used as a proof-of-concept for testing
resilience behaviour across real service-to-service HTTP calls. Five
services, one docker-compose file.

```
                     ┌──────────────┐
   client ──────────▶│ api-gateway  │
                     └──────┬───────┘
                            │ routes /api/**
        ┌───────────────────┼────────────────────┬──────────────────┐
        ▼                   ▼                    ▼                  ▼
┌───────────────┐  ┌──────────────────┐  ┌────────────────┐  ┌────────────────────┐
│ order-service │─▶│ inventory-service │  │ payment-service │  │ notification-service│
│  (port 8081)  │─▶│   (port 8082)     │  │   (port 8083)   │  │     (port 8084)     │
└───────────────┘  └──────────────────┘  └────────────────┘  └────────────────────┘
```

`order-service` is the only service that calls the other three. It
orchestrates order creation as a saga: reserve stock, charge payment,
then either **confirm** the reservation (success) or **release** it
(failure) — a real compensating-transaction pattern, not a stub.

## Why this shape

- **Plain HTTP calls, fixed addresses, no service discovery.** Every
  call `order-service` makes goes to a fixed URL (the docker-compose
  service name), configured via environment variables. This is
  deliberately the simplest thing that works — a clean, predictable
  surface for a network-layer interceptor to sit in front of later,
  without service-discovery machinery in the way.
- **A real compensating-transaction chain.** If stock reservation
  fails, nothing else is attempted. If payment fails after stock was
  reserved, the reservation is released. If everything succeeds,
  notification is sent best-effort — its failure does not undo an
  already-paid order. This mirrors exactly the kind of multi-hop
  chain (with a genuine reason to care about timeouts, retries and
  partial failure) that resilience work needs to test against.
- **Deterministic failure hooks.** `payment-service` accepts a
  `forceFail` flag, and also fails a configurable fraction of
  payments at random (`PAYMENT_SIMULATED_FAILURE_RATE` in `.env`) —
  so both scripted demos and organic retry/backoff scenarios are
  possible without needing an unreliable real payment gateway.

## Prerequisites

- Docker and Docker Compose
- Nothing else — Maven and Java are only used inside the build
  containers, not on your host machine.

## Running it

```bash
cp .env.example .env
# open .env and change POSTGRES_PASSWORD before doing anything beyond local testing

docker compose up -d --build
```

First build downloads Maven dependencies for five services and will
take a while. Subsequent builds are much faster due to layer caching.

Check everything came up:

```bash
docker compose ps
```

## Service endpoints (direct, bypassing the gateway)

| Service | Port | Base path |
|---|---|---|
| api-gateway | 8080 | `/api/orders`, `/api/inventory`, `/api/payments`, `/api/notifications` |
| order-service | 8081 | `/orders` |
| inventory-service | 8082 | `/inventory` |
| payment-service | 8083 | `/payments` |
| notification-service | 8084 | `/notifications` |

Everything below can be called either directly on its own port, or
through the gateway on `:8080/api/...`. Both are shown for the first
example.

## Walkthrough

**1. Check seeded stock** (inventory-service seeds four products on
first startup):

```bash
curl http://localhost:8082/inventory/products
# same thing through the gateway:
curl http://localhost:8080/api/inventory/products
```

**2. Place a successful order:**

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "cust-001",
    "items": [
      { "sku": "SKU-KEYBOARD-01", "quantity": 2 },
      { "sku": "SKU-MOUSE-01", "quantity": 1 }
    ],
    "forcePaymentFail": false
  }'
```

Expect `status: "CONFIRMED"`, a `paymentId`, and a computed
`totalAmount`. Check the notification was actually sent:

```bash
curl http://localhost:8084/notifications
```

**3. Force a payment failure and watch stock get released:**

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "cust-002",
    "items": [ { "sku": "SKU-MOUSE-01", "quantity": 1 } ],
    "forcePaymentFail": true
  }'
```

Expect HTTP 402 and `"PAYMENT_FAILED"`. Then re-check the product —
`availableQuantity` should be back to what it was before this call,
because the reservation was released.

**4. Trigger a real stock-reservation failure** (the headset only has
3 units seeded):

```bash
curl -X POST http://localhost:8080/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "cust-003",
    "items": [ { "sku": "SKU-HEADSET-01", "quantity": 999 } ],
    "forcePaymentFail": false
  }'
```

Expect HTTP 409 and `"STOCK_RESERVATION_FAILED"` — payment is never
attempted for this order.

**5. List all orders:**

```bash
curl http://localhost:8080/api/orders
```

## Compensating-transaction flow, precisely

```
reserve(item 1) ── ok ──▶ reserve(item 2) ── FAILS ──▶ release(item 1) ── order: FAILED
reserve(item 1) ── ok ──▶ reserve(item 2) ── ok ──▶ payment ── FAILS ──▶ release(item 1), release(item 2) ── order: FAILED
reserve(item 1) ── ok ──▶ reserve(item 2) ── ok ──▶ payment ── ok ──▶ confirm(item 1), confirm(item 2) ── order: CONFIRMED ──▶ notify (best-effort)
```

## Environment variables (`.env`)

All database credentials and inter-service URLs are injected via
environment variables rather than hardcoded in `application.yml` —
`.env` is the single place secrets and per-environment config live.
`.env` is not meant to be committed; `.env.example` is the template.

| Variable | Purpose |
|---|---|
| `POSTGRES_USER` / `POSTGRES_PASSWORD` | Shared Postgres credentials for all three databases |
| `ORDER_DB` / `INVENTORY_DB` / `PAYMENT_DB` | Per-service database names |
| `*_SERVICE_PORT` | Host-side port each service is published on |
| `PAYMENT_SIMULATED_FAILURE_RATE` | 0.0-1.0, random decline chance in payment-service |

## What's intentionally not here

- **No service discovery** (Eureka, Consul) — fixed URLs via env vars
  instead, on purpose (see "Why this shape" above).
- **No retry, circuit breaker, or timeout tuning inside the
  services.** `order-service` uses a single fixed connect/read
  timeout and nothing else. That gap is deliberate.
- **No authentication.** Out of scope for this proof-of-concept.

## Stopping and resetting

```bash
docker compose down          # stop, keep data
docker compose down -v       # stop and wipe the Postgres volume
```

## Intelligence recommendations for Component 3

The intelligence layer now exposes `POST /api/v1/recommendations` on port
8090. It accepts the same `recent` and `baseline` snapshots as
`/api/v1/classifications` and returns a versioned advisory plan for
Component 3 to consume. It does not contact Kubernetes or apply policies.
The existing `k8s` directory contains deployment resources; this checkout
has no recommendation consumer or resilience policy controller.

Start the updated intelligence layer separately from the other services:

```powershell
cd intelligence-layer
.\mvnw.cmd spring-boot:run
```

From the repository root, request the original overloaded scenario:

```powershell
$body = Get-Content .\intelligence-layer\examples\member3\overloaded.request.json -Raw
Invoke-RestMethod -Method Post -Uri 'http://localhost:8090/api/v1/recommendations' -ContentType 'application/json' -Body $body
```

The response contains `schemaVersion: "1.0"`, `mode: "ADVISORY"`,
`serviceName`, `status`, `sourceCollectedAt`, `generatedAt`, `expiresAt`,
`evidence`, and `recommendations`. Each recommendation contains `action`,
`parameters`, and `reason`. Plans expire 5 minutes after generation by default.
The review period can be changed using `rrpe.recommendation.recommendation-ttl`,
for example `10m`. The review workflow checks the original expiry before recording
approval and when retrieving approved plans. Component 3 must also check expiry
and telemetry freshness before distribution; policy delivery is not yet connected.
Captured responses for all three statuses are in
`intelligence-layer/examples/member3/*.recommendations.json`.

| Action | Parameters for the overloaded example | Component 3 interpretation |
|---|---|---|
| `RATE_LIMIT` | `maxRequestsPerSecond: 75` | Limit admitted traffic to the named service |
| `DISABLE_RETRIES` | `maxAttempts: 1` | Allow the initial attempt with zero additional retries |

For `STRESSED`, the default retry action is `RETRY_BUDGET` with
`retryBudgetRatio: 0.05`, allowing at most five extra retry attempts per 100
original, non-retry requests in the consumer's accounting window. The budget
applies across the same aggregate service scope as the metrics, rather than
granting each request additional attempts. Component 3 and its enforcement
layer must explicitly support this action and agree on accounting-window
semantics. A consumer must reject unsupported actions.

These values are deterministic PoC policy defaults, not tuned production
settings. Status uses the existing classifier: latency ratio at least 2
or error rate at least 0.10 yields `OVERLOADED`; otherwise a ratio at least
1.5 or error rate at least 0.05 yields `STRESSED`.

For degraded services, the rate limit is the smaller of recent request
rate and baseline request rate multiplied by 0.75 (`OVERLOADED`) or 0.90
(`STRESSED`). Configure these factors with `rrpe.recommendation.overloaded-rate-multiplier`
and `stressed-rate-multiplier`. The rate limit is omitted when either rate is zero.
During degradation, an increased retry count triggers a retry suggestion using
`overloaded-retry-budget-ratio` (default zero) or `stressed-retry-budget-ratio`
(default 0.05), under the same property prefix. Zero produces `DISABLE_RETRIES`;
a positive ratio produces `RETRY_BUDGET`. No retry suggestion is made when retry
counts have not increased. Original request counts and the active retry policy
are not supplied, so this is a configured advisory allowance, not evidence that
the existing budget was exceeded or that the proposed budget is lower than the
current policy. `HEALTHY` returns an empty
recommendation list; it does not request removal of existing policies.

The service-level prototype generates traffic-rate and retry-budget
recommendations. Leaky Bucket enforcement details remain to be agreed with
Component 3. Chain-specific deadline suggestions use a separate endpoint described
below. Cancellation diagnosis uses correlated evidence through the cancellation
analysis endpoint described below. Circuit-breaker and
concurrency-limit policies are outside the current recommendation scope. A
baseline request rate represents observed normal traffic, not measured maximum
service capacity.

Input rates are requests per second; error rates are fractions from 0
to 1. Snapshots must use matching service names and comparable aggregation
windows and scopes. Both timestamps are required and recent must be later
than baseline. Missing snapshots, invalid numeric ranges, and mismatched
services return HTTP 400 on the recommendation endpoint.

Component 3 must map the service name to its configured namespace and
traffic target, and map supported actions to its gateway, proxy, or
resilience implementation. These actions are not Kubernetes manifests.
Limits apply to the same aggregate scope as the supplied metrics; do not
multiply them across replicas accidentally. A consumer should reject
expired plans, unsupported schema versions, and stale telemetry, and
deduplicate by service name and source timestamp. Replaying the fixed
example timestamps is intended for integration tests. This endpoint does
not check telemetry age, and a newly generated plan can contain old input.
Recovery and removal of previously applied policies remain Component 3's
responsibility.

Test all three live responses from the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\intelligence-layer\examples\member3\Test-Recommendations.ps1
```

`-ExecutionPolicy Bypass` applies only to this process. The test compares
status, actions, parameter values, and validity period without applying
any recommendation. An alternate server can be supplied with
`-BaseUrl http://localhost:8091`.

## Chain deadline suggestions

`POST /api/v1/recommendations/deadlines` accepts the chain ID, ordered service
path, chain health, window timestamps, request and deadline-failure counts,
end-to-end p99 latency, and current chain deadline. This is caller-supplied
telemetry; the endpoint does not query Tempo or derive chain p99 from service p95.

When fresh, sufficient evidence shows deadline failures, it proposes
`ceil(chain p99 latency * safety margin)`. The initial configurable margin is
1.20, maximum increase factor is 1.50, absolute maximum is 10,000 ms, and minimum
request count is 100. A p99 of 1,200 ms with a current deadline of 1,000 ms
therefore produces a proposed deadline of 1,440 ms. These are PoC choices to
validate, not tuned production settings. They use the `rrpe.recommendation`
properties `deadline-safety-margin`, `maximum-deadline-increase-factor`,
`maximum-chain-deadline-ms`, and `minimum-chain-requests`.

Incomplete or stale evidence, overload, and exceeded safety limits return
HTTP 200 with a status message and no proposed deadline. Without observed
deadline failures, or when the calculated deadline is already covered by the
current policy, the response is `NO_CHANGE`. Invalid input returns HTTP 400.
This endpoint only suggests increases; recovery-based deadline reductions
remain future work.

A suggestion includes `CHANGE_CHAIN_DEADLINE`, the current policy and source
measurements in `evidence`, `requiresApproval: true`, and a five-minute expiry.
The dashboard and review API implement human decisions and expiry checks.
Nothing is sent to Component 3 or enforced by a sidecar.

Test synthetic chain evidence against the standard port 8090 server:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\intelligence-layer\examples\member3\Test-DeadlineRecommendations.ps1
```

For the accelerated demo server, use its shorter window:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\intelligence-layer\examples\member3\Test-DeadlineRecommendations.ps1 -BaseUrl http://localhost:8091 -WindowSeconds 30
```

## Cancellation analysis

`POST /api/v1/analyses/cancellations` checks whether work continues after a
request is cancelled. It accepts caller-supplied correlated request or trace
evidence. A service's aggregate `cancellationCount` alone cannot establish
whether that cancelled request continued processing or started downstream calls.
The endpoint does not collect traces from Tempo.

The request fields are:

| Field | Meaning |
|---|---|
| `chainId`, `services` | Chain identity and ordered service path |
| `windowStart`, `windowEnd` | Measurement window timestamps; the end must not be in the future |
| `cancellationCount` | Number of distinct cancelled requests in the window |
| `continuedProcessingCount` | Distinct cancelled requests still processing after cancellation plus the grace period |
| `downstreamActivityCount` | Distinct cancelled requests starting new downstream calls after cancellation plus the grace period |
| `observationGraceMs` | Grace period used by the evidence producer, in milliseconds |
| `telemetryComplete` | Whether correlated evidence covers the full window and observation grace period |

Each affected count must be between zero and `cancellationCount`. The two
groups can overlap: 15 continued-processing cases and 12 downstream-call cases
among 20 cancellations are valid. Do not add them to claim 27 affected requests.
`observationGraceMs` describes how the supplied evidence was measured; it does
not change a timeout or cancellation policy. The producer must wait long enough
to observe the grace period before marking evidence complete.

The measurement window must match `rrpe.baseline.recent-window`, allowing up to
one extra `sample-interval`. Evidence is stale when its window ends more than
twice `analysis-interval` before analysis. Standard settings use a five-minute
window and a two-minute freshness limit; the accelerated demo uses 30 seconds
and a 30-second freshness limit.

| Status | Interpretation |
|---|---|
| `PROBLEM_DETECTED` | Findings require human investigation (`requiresReview: true`) |
| `NO_PROBLEM_OBSERVED` | No post-cancellation activity was observed in this window beyond the supplied grace period |
| `NO_CANCELLATIONS_OBSERVED` | No cancelled requests were observed; cancellation handling cannot be validated from this window |
| `INSUFFICIENT_DATA` | Wait for complete evidence covering the configured window |
| `WAITING_FOR_FRESH_DATA` | Wait for fresh evidence |

All these statuses return HTTP 200. Waiting states have `ready: false` and empty
`findings`. Invalid identities, timestamps, counts, or grace periods return
HTTP 400. Findings include `code`, `affectedRequestCount`, `message`, and
`suggestedCheck`. The response uses `schemaVersion: "1.0"`, `mode: "ADVISORY"`,
the original `evidence`, and `analysedAt`. It reports observations and possible
checks; it does not prove a root cause or create an executable policy.

Test against a running standard port 8090 server from the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\intelligence-layer\examples\member3\Test-CancellationAnalysis.ps1
```

For the accelerated demo, start the server in one terminal and leave it running:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\intelligence-layer\examples\member3\Test-AutomaticRecommendations.ps1 -StartServer
```

In another terminal, run from the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\intelligence-layer\examples\member3\Test-CancellationAnalysis.ps1 -BaseUrl http://localhost:8091 -WindowSeconds 30
```

The script uses synthetic evidence and checks problem findings, incomplete data,
no observed problem, no cancellations, stale evidence, and an intentionally
invalid count. Expect `All six cancellation checks passed`, followed by an
example finding response. The HTTP 400 check deliberately sends invalid input.
No policy is applied.

| File | Responsibility |
|---|---|
| `analysis/api/CancellationAnalysisRequest.java` | Defines the evidence input fields |
| `analysis/service/CancellationAnalysisService.java` | Validates evidence and produces findings or waiting states |
| `controller/CancellationAnalysisController.java` | Exposes the HTTP endpoint |
| `examples/member3/Test-CancellationAnalysis.ps1` | Runs six live checks and prints example findings |
| `src/test/java/org/rrpe/intelligence/CancellationAnalysisEndpointTests.java` | Automatically tests the endpoint during Maven tests |

The first three paths are relative to
`intelligence-layer/src/main/java/org/rrpe/intelligence`; the last two are
relative to `intelligence-layer`.

## Component 4 implementation progress

The current prototype can learn a baseline from supplied observations, generate
service rate/retry suggestions, propose a chain deadline from supplied p99
evidence, and diagnose cancellation problems from correlated evidence. The
latest verification passed all 45 automated tests, including nine cancellation
endpoint tests, and all six live cancellation script checks.

The dashboard now records human approval or rejection and enforces the original
recommendation expiry. Real Prometheus and Tempo collection, scheduled analysis,
policy delivery to Component 3, and recovery
monitoring remain to be implemented. Leaky Bucket enforcement details and
retry-budget accounting integration also remain pending. Learning and recent-window state are currently in memory
and reset on restart. Component 4 storage is still an open choice; Redis or
another backend may be selected independently of the business services' current
PostgreSQL setup. The cancellation stage changed no Component 3 Kubernetes files.

## Recommendation review dashboard

The intelligence layer serves a dashboard at its server root, for example
`http://localhost:8090/` with standard settings or `http://localhost:8091/`
with the accelerated demo. It uses the same Spring Boot server; no separate
frontend install or build is required. Running the intelligence layer locally
requires Java 17 configured through `JAVA_HOME` or the host path.

The layout follows the Component 4 section of the supplied
`RRPE_All_Components_Dashboard.html` reference. Its five navigation pages are
**Overview**, **Service Health**, **Request Chains**, **Policy Decisions**, and
**Recovery & History**. The other components' prototypes are not included.
Overview shows the selected evidence, service metrics, and suggested response.
Service Health lists observed services and explains their actual classifier
results. Request Chains displays supplied paths, chain p99/deadline evidence,
and cancellation findings. Policy Decisions holds the approval queue. Recovery
& History shows real review decisions alongside an explicitly labelled recovery
demo. The demo starts at 2/5 simulated healthy windows. **Simulate healthy window**
advances its progress to 5/5 and **Reset simulation** restarts it. This updates
browser-only demo state, not observations, review records, or applied policies.
**Show demo history** toggles the labelled sample history rows independently of
real review decisions. Actual applied-policy recovery monitoring is not connected.

`GET /api/v1/metrics` provides a read-only summary of up to 100 services with
submitted observations. It does not queue policies. Service metrics are observed
p95 values, not the reference prototype's p99 values. Retry amplification,
classification persistence, confidence percentages, per-hop timings, automatic
trace discovery, and enforced/recovered policy statuses are not fabricated.
Synthetic snapshot metrics are available in the browser session that created
them; after reload, stored reviews retain their evidence but do not invent
missing metric values. Learned metrics remain available from the running server.

From the repository root, start the accelerated demo in PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\intelligence-layer\examples\member3\Test-AutomaticRecommendations.ps1 -StartServer
```

Leave that terminal open and open `http://localhost:8091/` in your browser.
Click **Add synthetic demo plans** on Overview, then open **Policy Decisions**
and **Review plan**, inspect the evidence,
enter your reviewer name and optional comment, then choose **Approve plan** or
**Reject**. Synthetic plans have service names beginning with `synthetic-` and
do not represent real service observations. Approval records a decision only;
no policy is sent to Component 3 or applied to Kubernetes or a sidecar.

To queue a recommendation from learned observations, first submit observations
using the automatic demo script in another terminal. Enter the synthetic service
name printed by that script under **Service Health**, then click
**Analyze learned metrics**. You can also enter a real service name after its
observations have been submitted. Learning and insufficient data show normal
waiting messages. Healthy results do not create empty review records. The
existing recommendation GET endpoints remain read-only; queueing is explicit.

The queue displays the latest 100 reviews and refreshes every 20 seconds.
Counts and filters refer to those latest records. Expiry countdowns disable
decisions when validity ends. Review records start `PENDING`, then become
`APPROVED` or `REJECTED`; pending and approved plans become `EXPIRED` when
retrieved after their original expiry. Rejected records remain rejected for
history. Expiry is checked on lookup and decision, rather than by a background
timer. Approval never restarts the validity period or removes an applied policy.

Chain deadline evidence can be submitted through the dashboard to create a
reviewable deadline suggestion. Cancellation evidence produces findings in the
current browser session; these findings are for investigation and have no policy
approval buttons. **Load synthetic example** fills either evidence form using
the configured recent-window duration. Click the corresponding analysis button
to submit it. Replace synthetic input with measured evidence and its actual
timestamps when testing a real chain.

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/reviews?limit=100` | Latest reviews, with current expiry state; limit must be 1 to 100 |
| `GET /api/v1/metrics` | Read-only analysis of observed services for Service Health |
| `GET /api/v1/reviews/{recommendationId}` | One review and its original plan |
| `POST /api/v1/metrics/{serviceName}/reviews` | Analyze learned metrics and queue an actionable plan |
| `POST /api/v1/reviews/service` | Generate and queue a plan from supplied recent/baseline snapshots |
| `POST /api/v1/reviews/deadline` | Generate and queue a plan from supplied chain deadline evidence |
| `POST /api/v1/reviews/decisions` | Record approval or rejection of a stored plan |
| `GET /api/v1/reviews/config` | Recent-window duration used by synthetic evidence examples |

A decision contains `recommendationId`, `decision` (`APPROVE` or `REJECT`),
`reviewer`, and optional `comment`. It cannot replace plan actions, parameters,
or expiry. Identical decision replays preserve the first audit time; conflicting
decisions return HTTP 409 with an explanation. Unknown IDs return HTTP 404 and
invalid decisions return HTTP 400. An expired review returns HTTP 200 with
`status: "EXPIRED"`, without recording approval. The reviewer name is a
caller-supplied PoC audit label, not an authenticated identity.

The manual snapshot path retains the original manual endpoint's telemetry-age
limitations. Review checks plan expiry but does not rerun classification or
revalidate source telemetry on approval. Before policy delivery is implemented,
the consumer must also verify source freshness, supported actions and schema,
current conditions, and aggregate scope. Review records are stored in memory,
are not shared across application replicas, and reset on restart. Redis can
implement the same storage interface later.

| File | What it does |
|---|---|
| `intelligence-layer/src/main/resources/static/index.html` | Dashboard structure and review forms |
| `intelligence-layer/src/main/resources/static/dashboard.css` | Responsive dashboard styling |
| `intelligence-layer/src/main/resources/static/dashboard.js` | Loads reviews, displays findings and countdowns, and submits decisions |
| `intelligence-layer/src/main/java/org/rrpe/intelligence/controller/RecommendationReviewController.java` | Review list, lookup, generation, and decision HTTP endpoints |
| `intelligence-layer/src/main/java/org/rrpe/intelligence/analysis/service/RecommendationReviewService.java` | Approval rules, audit history, and expiry checks |

The storage interface and in-memory store now support a bounded latest-record
listing. `MetricsController.java` adds the learned-metrics queueing endpoint.
Automated tests cover review endpoints, static dashboard assets, expiry
boundaries without sleeps, concurrent decisions, and preservation of original
plans. Browser verification covered synthetic plans, approve/reject actions,
filters, deadline and cancellation forms, waiting states, expired controls,
plain-text rendering of untrusted values, and the mobile layout.
