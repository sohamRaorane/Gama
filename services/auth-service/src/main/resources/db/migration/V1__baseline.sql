-- V1 Baseline: Bridge authentication table
-- PostgreSQL timezone Etc/UTC is enforced by container; application uses UTC for Hibernate

CREATE TABLE bridges (
    id UUID PRIMARY KEY,
    bridge_id VARCHAR(255) NOT NULL UNIQUE,
    credential_identifier VARCHAR(255) NOT NULL UNIQUE,
    credential_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bridges_bridge_id ON bridges (bridge_id);
CREATE INDEX idx_bridges_credential_identifier ON bridges (credential_identifier);
