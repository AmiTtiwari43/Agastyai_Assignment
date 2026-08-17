# Reliable Webhook Delivery Service

A production-grade, multi-tenant, at-least-once webhook delivery system built with **Java 21**, **Spring Boot 3**, and **PostgreSQL 16**.

---

## ⚡ Quick Start (Under 5 Minutes)

**Prerequisites:** Docker + Docker Compose installed and running.

```bash
# 1. Clone the repo
git clone https://github.com/AmiTtiwari43/Agastyai_Assignment.git
cd Agastyai_Assignment

# 2. Copy environment file
cp .env.example .env

# 3. Start everything
docker compose up --build -d

# 4. Verify health
curl http://localhost:8080/actuator/health

# 5. Open Swagger UI
open http://localhost:8080/swagger-ui.html
```

That's it. Flyway migrations run automatically on startup. No manual schema steps needed.

---

## 🏗 Architecture

```
Producer (POST /api/v1/events)
    │
    ▼
┌──────────────────────────────────────────────────────┐
│  EventController → EventService                      │
│  • Validates X-Tenant-Id header (404 on unknown)     │
│  • Idempotent dedup on (tenant_id, event_id_external)│
│  • Returns 202 immediately (<100ms guarantee)        │
│  • Fans out PENDING deliveries for each matching     │
│    active endpoint subscribed to the event type      │
└──────────────────────────────────────────────────────┘
    │  (writes PENDING rows, returns 202)
    ▼
┌─────────────────────────────────────────────────────┐
│  PostgreSQL: deliveries table                       │
│  status='PENDING', next_attempt_at=NOW()            │
└─────────────────────────────────────────────────────┘
    │  (polled every 1 second)
    ▼
┌────────────────────────────────────────────────────────────┐
│  DeliveryWorker (Spring @Scheduled)                        │
│  • Calls claimDueDeliveries() — single SELECT ... FOR      │
│    UPDATE SKIP LOCKED query, batch atomically moved to     │
│    IN_PROGRESS with locked_by + locked_until               │
│  • Dispatches each delivery to Java 21 virtual thread      │
└────────────────────────────────────────────────────────────┘
    │  (one virtual thread per delivery)
    ▼
┌────────────────────────────────────────────────────────────┐
│  DeliveryExecutionService                                  │
│  1. Checks EndpointCircuitBreaker (OPEN → skip, reschedule)│
│  2. WebhookDispatcher: HMAC-SHA256 signs payload,          │
│     sends POST with X-Webhook-Signature, X-Webhook-        │
│     Timestamp, X-Correlation-Id + 5s connect / 10s request │
│     timeout via Java HttpClient                            │
│  3. 2xx → DELIVERED; non-2xx/timeout → record attempt,    │
│     calculate backoff, reschedule as PENDING               │
│  4. attempt >= maxAttempts → DEAD_LETTERED                 │
└────────────────────────────────────────────────────────────┘
    │  (every 30 seconds)
    ▼
┌─────────────────────────────────────────────────────────┐
│  Lease Recovery Sweeper                                  │
│  • Reclaims IN_PROGRESS deliveries where locked_until < NOW│
│  • Guards against worker crashes (at-least-once guarantee)│
└─────────────────────────────────────────────────────────┘
```

### Module Layout

```
src/main/java/com/company/webhooks/
├── WebhookDeliveryApplication.java   # Spring Boot entry point
├── tenant/                           # TenantContext, TenantInterceptor
├── endpoint/                         # Endpoint CRUD + URL validator + secret gen
├── event/                            # Event ingestion + fan-out
├── delivery/                         # Worker, dispatcher, signing, backoff, CB
├── deliveryattempt/                  # Per-attempt history records
├── observability/                    # CorrelationIdFilter, HealthIndicator
├── config/                           # WebMvcConfig (interceptor registration)
└── common/exception/                 # GlobalExceptionHandler, DTOs
```

---

## 🔒 Locking / Claiming Strategy

All due-delivery selection is done **100% at the database level** via a single native SQL query:

```sql
SELECT id FROM deliveries
WHERE status = 'PENDING' AND next_attempt_at <= :now
ORDER BY next_attempt_at ASC
LIMIT :batchSize
FOR UPDATE SKIP LOCKED
```

**Why this matters:**
- `FOR UPDATE` — exclusively locks the selected rows within the transaction
- `SKIP LOCKED` — competing workers skip already-locked rows instead of blocking, providing lock-free fan-out across multiple worker processes
- No Java-side `.stream()` filtering; the DB returns only what's truly due and unclaimed
- A partial index `idx_deliveries_claim ON deliveries(status, next_attempt_at) WHERE status = 'PENDING'` keeps the query sub-millisecond even with hundreds of thousands of pending rows

After claiming, each delivery is atomically set to `IN_PROGRESS` with `locked_by = workerId` and `locked_until = now + leaseSeconds`. If the worker crashes, a **lease recovery sweeper** (runs every 30s) resets any `IN_PROGRESS` delivery where `locked_until < now()` back to `PENDING`, ensuring no delivery is ever permanently lost.

---

## ⏱ Backoff Formula

```
delay = min(baseSec × 2^(attempt - 1), capSec) + jitter
```

| Parameter | Default | Config Key |
|---|---|---|
| Base delay | 30 seconds | `RETRY_BASE_DELAY_SECONDS` |
| Max delay cap | 6 hours (21600s) | `RETRY_MAX_DELAY_SECONDS` |
| Max attempts | 8 | `RETRY_MAX_ATTEMPTS` |
| Jitter | ±15% | `RETRY_JITTER_PERCENT` |

**Attempt schedule (approximate, before jitter):**

| Attempt | Delay |
|---|---|
| 1 | 30s |
| 2 | 1 min |
| 3 | 2 min |
| 4 | 4 min |
| 5 | 8 min |
| 6 | 16 min |
| 7 | 32 min |
| 8 (final) | 1h → DEAD_LETTERED |

Total span ≈ 2 hours before dead-lettering. With `RETRY_MAX_ATTEMPTS=12`, span reaches ~24 hours.

**Why jitter?** Without it, all retries from a batch failure land at the same instant (thundering herd). ±15% randomizes retry windows to distribute load.

---

## 🛡 At-Least-Once Guarantee

**How we guarantee at-least-once:**

1. Event ingestion writes a `PENDING` delivery record **before** returning 202. If the app crashes after the DB write but before returning, the delivery still happens on restart.
2. The worker claims deliveries with an exclusive row lock (`FOR UPDATE SKIP LOCKED`) inside a `REQUIRES_NEW` transaction — claim is committed before execution starts.
3. A lease expiry sweeper runs every 30 seconds to recover any delivery that was claimed but never completed (worker crash, OOM, etc.).
4. `DELIVERED` status is written in a separate `REQUIRES_NEW` transaction after the HTTP call succeeds.

**Where duplicates could theoretically still happen:**
- If the worker successfully delivers (gets 2xx from endpoint) but crashes before committing the `DELIVERED` status update — the lease expires and the delivery is retried. The endpoint receives the webhook twice. This is the classic **at-least-once** tradeoff; to eliminate it you'd need distributed 2-phase commit or idempotency keys on the receiving end.

---

## 🔑 HMAC Signing

Every webhook delivery includes:
- `X-Webhook-Signature: sha256=<hex>` — HMAC-SHA256 of the raw JSON payload bytes using the endpoint's 32-byte secret
- `X-Webhook-Timestamp: <unix_epoch>` — delivery timestamp in seconds
- `X-Correlation-Id: <uuid>` — traceable across logs

Receivers can verify authenticity:
```python
import hmac, hashlib
expected = "sha256=" + hmac.new(secret.encode(), payload_bytes, hashlib.sha256).hexdigest()
assert hmac.compare_digest(expected, request.headers["X-Webhook-Signature"])
```

---

## 🚦 Circuit Breaker

Per-endpoint circuit breaker with three states:

| State | Behavior |
|---|---|
| CLOSED | Normal delivery |
| OPEN | Skip delivery; reschedule after cooldown window |
| HALF_OPEN | Allow one probe attempt; CLOSED on success, OPEN on failure |

Configurable via `CB_FAILURE_THRESHOLD` (default: 5) and `CB_COOLDOWN_SECONDS` (default: 60s).

---

## 📡 API Reference

### Tenant Setup
All API calls require `X-Tenant-Id: <your-tenant-id>` header. Unknown tenant IDs return `404`.

Seed a tenant in PostgreSQL:
```sql
INSERT INTO tenants (id, name) VALUES ('my-tenant', 'My Tenant');
```

Or use the pre-seeded default: `X-Tenant-Id: tenant-default`

### Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/endpoints` | Register a webhook endpoint |
| GET | `/api/v1/endpoints` | List all endpoints (scoped to tenant) |
| GET | `/api/v1/endpoints/{id}` | Get endpoint details |
| DELETE | `/api/v1/endpoints/{id}` | Soft-disable an endpoint |
| POST | `/api/v1/endpoints/{id}/test` | Send a synthetic signed test event |
| POST | `/api/v1/endpoints/{id}/deliveries` | Paginated delivery history for endpoint |
| POST | `/api/v1/events` | Ingest an event (202, idempotent) |
| GET | `/api/v1/events/{id}/deliveries` | Delivery history for an event |
| POST | `/api/v1/deliveries/{id}/redrive` | Re-queue a dead-lettered delivery |
| GET | `/actuator/health` | Health check (DB + worker) |
| GET | `/swagger-ui.html` | Swagger interactive API docs |
| GET | `/api-docs` | OpenAPI JSON |

### Example: Register Endpoint
```bash
curl -X POST http://localhost:8080/api/v1/endpoints \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-default" \
  -d '{"url": "https://my-server.example.com/webhook", "subscribedEventTypes": ["order.paid", "user.signup"]}'
```

Response (201):
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "tenantId": "tenant-default",
  "url": "https://my-server.example.com/webhook",
  "secret": "3fa85f6457174562b3fc2c963f66afa6...",
  "subscribedEventTypes": ["order.paid", "user.signup"],
  "status": "ACTIVE",
  "createdAt": "2026-01-01T12:00:00Z"
}
```

### Example: Ingest Event
```bash
curl -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant-default" \
  -d '{"eventId": "evt_unique_123", "type": "order.paid", "payload": {"orderId": "ORD-456", "amount": 99.99}}'
```

Response (202):
```json
{
  "id": "7a9c3f21-...",
  "tenantId": "tenant-default",
  "eventIdExternal": "evt_unique_123",
  "type": "order.paid",
  "createdAt": "2026-01-01T12:00:01Z"
}
```

---

## 🧪 Running Tests

```bash
# Unit tests only (no Docker needed)
./mvnw test -Dtest="BackoffCalculatorTest,WebhookSignerTest,UrlValidatorTest,EndpointCircuitBreakerTest"

# All tests (requires Docker for Testcontainers)
./mvnw clean test

# Skip integration tests
./mvnw test -Dsurefire.failIfNoSpecifiedTests=false -Dexclude="**/*IntegrationTest*"
```

**Test suite coverage:**
- `BackoffCalculatorTest` — boundary conditions: attempt 0, max attempt, monotonic increase, jitter bounds
- `WebhookSignerTest` — HMAC-SHA256 signature correctness + tamper detection
- `UrlValidatorTest` — rejects non-HTTP/S, localhost, private IP ranges (SSRF protection)
- `EndpointCircuitBreakerTest` — state transitions: CLOSED → OPEN → HALF_OPEN → CLOSED
- `DeliveryLifecycleIntegrationTest` — full end-to-end: ingest → claim → dispatch → DELIVERED / DEAD_LETTERED → redrive
- `ConcurrencyClaimIntegrationTest` — 10 concurrent workers race `FOR UPDATE SKIP LOCKED`; zero double-claims verified
- `EventIdempotencyIntegrationTest` — duplicate `eventId` produces exactly one delivery (not two)
- `TenantIsolationIntegrationTest` — cross-tenant reads/redrive return 404, never 403

---

## 🎬 Demo Scenarios

### 1. Successful Delivery
```bash
# Register endpoint (use webhook.site or similar)
curl -X POST http://localhost:8080/api/v1/endpoints \
  -H "X-Tenant-Id: tenant-default" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://webhook.site/your-token","subscribedEventTypes":["demo.event"]}'

# Send event
curl -X POST http://localhost:8080/api/v1/events \
  -H "X-Tenant-Id: tenant-default" \
  -H "Content-Type: application/json" \
  -d '{"eventId":"demo-1","type":"demo.event","payload":{"hello":"world"}}'

# Check delivery
curl http://localhost:8080/api/v1/events/<event-id>/deliveries \
  -H "X-Tenant-Id: tenant-default"
```

### 2. Simulate Failing Endpoint (retries with backoff)
Register an endpoint pointing to an always-500 URL. After each failed attempt, check `nextAttemptAt` is in the future and increases exponentially.

### 3. Cross-Tenant Isolation
```bash
# Tenant A creates endpoint
curl -X POST http://localhost:8080/api/v1/endpoints \
  -H "X-Tenant-Id: tenant-a" ...

# Tenant B tries to access Tenant A's endpoint — gets 404
curl http://localhost:8080/api/v1/endpoints/<tenant-a-endpoint-id> \
  -H "X-Tenant-Id: tenant-b"
# → 404 Not Found (never reveals the endpoint exists)
```

### 4. Duplicate Event
Send the same `eventId` twice — the service returns 202 both times but creates exactly one delivery.

### 5. Manual Redrive
```bash
curl -X POST http://localhost:8080/api/v1/deliveries/<dead-lettered-id>/redrive \
  -H "X-Tenant-Id: tenant-default"
# → delivery re-queued as PENDING, next_attempt_at = now
```

---

## ⚙️ Configuration Reference

All configuration via environment variables (see `.env.example`):

| Variable | Default | Description |
|---|---|---|
| `DB_HOST` | localhost | PostgreSQL host |
| `DB_PORT` | 5432 | PostgreSQL port |
| `DB_NAME` | webhooks | Database name |
| `DB_USER` | postgres | Database user |
| `DB_PASSWORD` | postgres | Database password |
| `DB_POOL_SIZE` | 20 | HikariCP pool size |
| `WORKER_ENABLED` | true | Enable delivery worker |
| `WORKER_BATCH_SIZE` | 50 | Deliveries claimed per poll |
| `WORKER_POLL_INTERVAL_MS` | 1000 | Worker poll frequency |
| `WORKER_LEASE_DURATION_SECONDS` | 120 | Crash-recovery lease TTL |
| `HTTP_CONNECT_TIMEOUT_MS` | 5000 | Outbound connect timeout |
| `HTTP_READ_TIMEOUT_MS` | 10000 | Outbound read/request timeout |
| `RETRY_MAX_ATTEMPTS` | 8 | Before dead-lettering |
| `RETRY_BASE_DELAY_SECONDS` | 30 | First retry delay |
| `RETRY_MAX_DELAY_SECONDS` | 21600 | 6h cap on delay |
| `RETRY_JITTER_PERCENT` | 0.15 | ±15% random jitter |
| `CB_FAILURE_THRESHOLD` | 5 | Failures to trip circuit |
| `CB_COOLDOWN_SECONDS` | 60 | Circuit-open cool-down |
| `ALLOW_INTERNAL_URLS` | false | Allow localhost/private IPs |

---

## ⚠️ Known Limitations

1. **In-memory circuit breaker** — state is per-process. With multiple worker pods, each pod maintains its own circuit state. A distributed circuit breaker (backed by Redis) would be needed for full multi-pod correctness.

2. **In-memory claim lock** — the worker uses a simple `AtomicBoolean` to prevent re-entrant polling within the same process. Multiple processes are safe via `FOR UPDATE SKIP LOCKED`, but within a single JVM, only one poll batch runs at a time. This is intentional to avoid overloading the DB under a single worker.

3. **No per-tenant throughput isolation** — a chatty tenant with thousands of events could delay deliveries for quieter tenants. A tenant-weighted fair-scheduling queue would solve this.

4. **No webhook replay by date range** — mass replay of events by type + date range to a new endpoint is not implemented.

5. **No strict FIFO ordering guarantee** — deliveries are ordered by `next_attempt_at` but parallel virtual threads may execute them out of order. A serial delivery queue per endpoint would be needed for strict ordering.

---

## 💡 One Thing That Surprised Me

`FOR UPDATE SKIP LOCKED` was the key insight that made the whole design clean. I expected to need a distributed queue (Redis, Kafka, SQS) to safely fan out work to multiple workers — but the combination of `SKIP LOCKED` and the lease-based recovery sweeper gives you exactly the same semantics (at-least-once, no double-claim under normal operation, crash-safe) using only PostgreSQL. The simplicity of this approach while maintaining correctness was genuinely surprising.

---

## 🚀 What I'd Do With Two More Weeks

1. **Per-tenant retry policy in DB** — store `RetryPolicy` entity with custom `maxAttempts`, `baseDelaySec`, `maxDelaySec` per tenant, overriding defaults
2. **Distributed circuit breaker** — move circuit state to Redis with TTL-based expiry so multi-pod deployments share state
3. **Prometheus + Grafana dashboard** — delivery success rate, DLQ queue depth, P99 latency per tenant/endpoint
4. **Replay tool** — re-deliver events by type + date range to a new endpoint (useful for migrations)
5. **Structured audit log** — append-only event store for every state transition with actor, timestamp, reason

---

## 📁 Project Structure

```
.
├── docker-compose.yml          # PostgreSQL + app container
├── Dockerfile                  # Multi-stage build (build + runtime)
├── pom.xml                     # Maven build (Java 21, Spring Boot 3.4)
├── .env.example                # All required environment variables
├── mvnw / mvnw.cmd             # Maven wrapper (no mvn install needed)
└── src/
    ├── main/
    │   ├── java/com/company/webhooks/
    │   │   ├── WebhookDeliveryApplication.java
    │   │   ├── tenant/          # TenantContext + interceptor
    │   │   ├── endpoint/        # Endpoint registration + self-test
    │   │   ├── event/           # Event ingestion + fan-out
    │   │   ├── delivery/        # Worker + dispatcher + backoff + CB
    │   │   ├── deliveryattempt/ # Attempt history
    │   │   ├── observability/   # Correlation ID filter + health check
    │   │   └── common/          # Exception handling
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/V1__init_schema.sql
    └── test/
        ├── java/com/company/webhooks/
        │   ├── BackoffCalculatorTest.java
        │   ├── WebhookSignerTest.java
        │   ├── UrlValidatorTest.java
        │   ├── EndpointCircuitBreakerTest.java
        │   ├── DeliveryLifecycleIntegrationTest.java
        │   ├── ConcurrencyClaimIntegrationTest.java
        │   ├── EventIdempotencyIntegrationTest.java
        │   └── TenantIsolationIntegrationTest.java
        └── resources/
            ├── application-test.yml
            └── testcontainers.properties
```
