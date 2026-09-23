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
