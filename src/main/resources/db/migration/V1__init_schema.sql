-- ====================================================================
-- Reliable Webhook Delivery Service — V1 Initial Schema
-- ====================================================================

-- 1. Tenants table
CREATE TABLE tenants (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- 2. Webhook Endpoints table
CREATE TABLE endpoints (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    url VARCHAR(2048) NOT NULL,
    secret VARCHAR(128) NOT NULL,
    subscribed_event_types TEXT[] NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_endpoints_tenant ON endpoints(tenant_id);
CREATE INDEX idx_endpoints_tenant_status ON endpoints(tenant_id, status);

-- 3. Ingested Events table
CREATE TABLE events (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    event_id_external VARCHAR(255) NOT NULL,
    type VARCHAR(128) NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_events_tenant_external_id UNIQUE (tenant_id, event_id_external)
);

CREATE INDEX idx_events_tenant_created ON events(tenant_id, created_at DESC);
CREATE INDEX idx_events_type ON events(type);

-- 4. Deliveries table
CREATE TABLE deliveries (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    endpoint_id UUID NOT NULL REFERENCES endpoints(id) ON DELETE CASCADE,
    tenant_id VARCHAR(64) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    locked_by VARCHAR(128),
    locked_until TIMESTAMP WITH TIME ZONE,
    last_response_code INT,
    last_response_snippet VARCHAR(1024),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Index for due-delivery claiming (NFR-2: FOR UPDATE SKIP LOCKED claim performance)
CREATE INDEX idx_deliveries_claim ON deliveries(status, next_attempt_at) WHERE status = 'PENDING';
-- Index for lease timeout recovery
CREATE INDEX idx_deliveries_lease ON deliveries(status, locked_until) WHERE status = 'IN_PROGRESS';
-- Indexes for visibility and tenant filtering
CREATE INDEX idx_deliveries_event ON deliveries(event_id);
CREATE INDEX idx_deliveries_endpoint ON deliveries(endpoint_id, created_at DESC);
CREATE INDEX idx_deliveries_tenant ON deliveries(tenant_id, created_at DESC);

-- 5. Delivery Attempts (history of every attempt per delivery)
CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY,
    delivery_id UUID NOT NULL REFERENCES deliveries(id) ON DELETE CASCADE,
    attempt_number INT NOT NULL,
    response_code INT,
    latency_ms BIGINT NOT NULL,
    error VARCHAR(1024),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_delivery_attempts_delivery ON delivery_attempts(delivery_id, attempt_number ASC);

-- Seed a default tenant for testing/demo
INSERT INTO tenants (id, name) VALUES ('tenant-default', 'Default Demo Tenant');
