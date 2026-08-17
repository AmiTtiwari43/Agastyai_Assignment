# 📖 Webhook Delivery Engine — Website User Guide & Demo Walkthrough

Welcome to the **Reliable Webhook Delivery Service Control Center**! This guide provides a step-by-step walkthrough of how to use the interactive web interface, test webhook deliveries, and explore the system with demo workspaces and credentials.

---

## ⚡ 1. Quick Access & Demo Credentials

| Resource | URL / Details |
|---|---|
| **Web Dashboard** | [http://localhost:8080/](http://localhost:8080/) |
| **Interactive Swagger API** | [http://localhost:8080/swagger-ui/index.html](http://localhost:8080/swagger-ui/index.html) |
| **Health Check Endpoint** | [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health) |
| **Prometheus Metrics** | [http://localhost:8080/actuator/prometheus](http://localhost:8080/actuator/prometheus) |
| **PostgreSQL Database** | `localhost:5432` · DB: `webhooks` · User: `postgres` · Pass: `postgres` |

### 🏢 Pre-Configured Demo Workspaces (Tenants)

The system enforces strict **Multi-Tenant Isolation**. You can switch between different company workspaces using the **Company / Workspace** field in the top header:

| Workspace ID | Company Profile | Pre-Configured Use Case |
|---|---|---|
| `acme-corp` *(Default)* | Global eCommerce Platform | Orders (`order.created`), Payments (`payment.completed`) |
| `fintech-global` | Payment Gateway & Invoicing | Invoices (`invoice.paid`), Refunds (`refund.processed`) |
| `retail-hub` | Inventory & Fulfillment | Stock updates (`inventory.low`), Shipments (`order.shipped`) |

> 🔒 **Tenant Isolation Guarantee**: Receivers, HMAC signing secrets, event streams, and delivery retry logs in `acme-corp` are completely inaccessible to `fintech-global`.

---

## 🚀 2. The 1-Click Instant Demo

If you want to see the system in action immediately:
1. Open [http://localhost:8080/](http://localhost:8080/) in your browser.
2. In the top glowing banner, click the green **`🚀 Run 1-Click Demo`** button.
3. **What happens automatically**:
   - Registers a live test receiver endpoint (`https://httpbin.org/post`).
   - Generates a cryptographically secure 32-byte HMAC secret.
   - Publishes a sample `payment.completed` event with order details.
   - Atomically fans out delivery jobs into the PostgreSQL queue.
   - A virtual thread worker claims the job with `FOR UPDATE SKIP LOCKED` and sends the webhook.
   - Automatically switches to the **Delivery Tracker & History** tab to display the delivered status, HTTP 200 response, and latency!

---

## 🧭 3. Step-by-Step Interactive Manual Guide

### Step 1: Set Your Company / Workspace
- In the top header bar, find the **Company / Workspace** input box.
- Enter `acme-corp` (or any custom workspace name of your choice).
- All API requests will automatically attach the `X-Tenant-Id: acme-corp` header.

---

### Step 2: Add a Webhook Receiver (Subscriber)
1. Click the **"📡 Webhook Receivers (Subscribers)"** tab.
2. Under **"Add Webhook Receiver"**, fill out:
   - **Receiver Webhook URL**: Enter any public test endpoint:
     - `https://httpbin.org/post` *(Always returns HTTP 200 with request echo)*
     - Or get a free unique URL from [webhook.site](https://webhook.site) to inspect real-time inbound HTTP headers.
   - **Events to Listen For**: Comma-separated event topics, e.g.:
     ```text
     payment.completed, order.created
     ```
3. Click **"Add Webhook Receiver"**.
4. **Result**: Your new receiver appears in the table with an auto-generated HMAC secret (e.g. `whsec_8f92ab...`).

---

### Step 3: Publish a Test Event (Publisher)
1. Click the **"🚀 Send Test Event (Publisher)"** tab.
2. Click the **"Auto UUID"** button to generate a unique **Event Tracking ID (Idempotency Key)**.
3. Set **Event Name / Topic** to `payment.completed`.
4. The **Event Payload** box is pre-filled with sample JSON data:
   ```json
   {
     "orderId": "ORD-2026-9901",
     "amount": 149.99,
     "currency": "USD",
     "customer": {
       "name": "Alex Mercer",
       "email": "alex@example.com"
     }
   }
   ```
5. Click **"Publish & Fan-Out to Subscribed Receivers"**.
6. **Result**: The event is accepted (`HTTP 201/202`), saved, and fanned out to all matching receivers.
   - *Test Idempotency*: Click the publish button again without changing the Event ID — notice the service safely returns the original event without creating duplicate deliveries.

---

### Step 4: Track Deliveries & Inspect Live HTTP Responses
1. Click the **"📊 Delivery Tracker & History"** tab.
2. The dashboard auto-polls every 3 seconds and displays:
   - **Delivery ID**: Click to open full attempt details & HTTP response snippet.
   - **Event Name / Topic**: The matching topic (e.g. `payment.completed`).
   - **Delivery Status**:
     - 🟡 `PENDING`: Queued in database.
     - 🔵 `IN_PROGRESS`: Claimed by Virtual Thread worker.
     - 🟢 `DELIVERED`: Successfully delivered (HTTP 2xx received).
     - 🔴 `DEAD_LETTERED`: Failed after 8 retries (eligible for Redrive).
   - **Attempts (Max 8)**: Current attempt count.
   - **Last Response**: HTTP response status code and execution latency.
   - **Created At**: Timestamp when the delivery was scheduled.
3. Click **"Inspect"** on any row to open the response modal with full raw error/response snippets.

---

### Step 5: Simulating Failures, Backoff Retries & Manual Redrive

Want to see the system handle real-world network failures?

1. Go to **"📡 Webhook Receivers"** and add an intentionally failing endpoint:
   - URL: `https://httpbin.org/status/503` *(Always returns HTTP 503 Service Unavailable)*
   - Events: `payment.failed, order.cancelled`
2. Go to **"🚀 Send Test Event"** and publish an event with topic `payment.failed`.
3. Switch to **"📊 Delivery Tracker"**:
   - The worker attempts delivery and encounters a `503`.
   - The system calculates exponential backoff with full jitter and schedules **Retry 1/8** in ~30s.
   - Subsequent failures will schedule retries at increasing intervals (~60s, ~120s, ~240s...).
   - After 8 failed attempts, the delivery transitions to `DEAD_LETTERED`.
4. When a delivery is `DEAD_LETTERED`, a yellow **"Retry Now"** button appears in the action column.
5. Click **"Retry Now"** to instantly redrive the webhook!

---

## 🔐 4. Webhook Receiver Signature Verification

Every outbound webhook sent by the engine includes cryptographic authentication headers:
- `X-Webhook-Signature`: `sha256=<hex_digest>` (HMAC-SHA256 over raw request body)
- `X-Webhook-Timestamp`: Unix epoch seconds
- `X-Tenant-Id`: Company identifier

### How to Verify in Your Backend:

#### Python
```python
import hmac, hashlib

def verify_webhook(raw_body_bytes: bytes, signature_header: str, secret: str) -> bool:
    expected_sig = "sha256=" + hmac.new(secret.encode('utf-8'), raw_body_bytes, hashlib.sha256).hexdigest()
    return hmac.compare_digest(signature_header, expected_sig)
```

#### Node.js / Express
```javascript
const crypto = require('crypto');

function verifyWebhook(rawBodyBuffer, signatureHeader, secret) {
    const expectedSig = 'sha256=' + crypto.createHmac('sha256', secret).update(rawBodyBuffer).digest('hex');
    return crypto.timingSafeEqual(Buffer.from(signatureHeader), Buffer.from(expectedSig));
}
```

#### Java
```java
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.util.HexFormat;

public boolean verifyWebhook(byte[] rawBody, String signatureHeader, String secret) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
    String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(rawBody));
    return MessageDigest.isEqual(signatureHeader.getBytes(), expected.getBytes());
}
```

---

## 🛠️ 5. Running via Terminal / cURL

If you prefer testing directly through terminal or scripts:

```bash
# 1. Register Endpoint
curl -X POST http://localhost:8080/api/v1/endpoints \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: acme-corp" \
  -d '{
    "url": "https://httpbin.org/post",
    "subscribedEventTypes": ["order.created", "payment.completed"]
  }'

# 2. Ingest Event
curl -X POST http://localhost:8080/api/v1/events \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: acme-corp" \
  -d '{
    "eventId": "evt_demo_1001",
    "type": "payment.completed",
    "payload": {
      "orderId": "ORD-5544",
      "amount": 89.95,
      "currency": "USD"
    }
  }'

# 3. Check Actuator Health
curl http://localhost:8080/actuator/health
```

Enjoy exploring the **Reliable Webhook Delivery Engine**!
