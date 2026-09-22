-- V1 Baseline: Packet ingestion table
-- PostgreSQL timezone Etc/UTC is enforced by container; application uses UTC for Hibernate

CREATE TABLE packets (
    id UUID PRIMARY KEY,
    packet_id UUID NOT NULL UNIQUE,
    encrypted_payload TEXT NOT NULL,
    bridge_id VARCHAR(255) NOT NULL,
    sender_id VARCHAR(64),
    recipient_id VARCHAR(64),
    type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_packets_packet_id ON packets (packet_id);
CREATE INDEX idx_packets_bridge_id ON packets (bridge_id);
CREATE INDEX idx_packets_status ON packets (status);
