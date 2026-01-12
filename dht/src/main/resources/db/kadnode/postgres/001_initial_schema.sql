-- Initial schema for Boson DHT node

CREATE TABLE IF NOT EXISTS valores (
    id BYTEA NOT NULL PRIMARY KEY,
    public_key BYTEA DEFAULT NULL,
    private_key BYTEA DEFAULT NULL,
    recipient BYTEA DEFAULT NULL,
    nonce BYTEA DEFAULT NULL,
    sequence_number INTEGER NOT NULL DEFAULT 0,
    signature BYTEA DEFAULT NULL,
    data BYTEA NOT NULL,
    persistent BOOLEAN NOT NULL DEFAULT FALSE,
    created BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000
);

-- Partial index for persistent + announced queries
CREATE INDEX IF NOT EXISTS idx_valores_persistent_true_updated ON valores (updated DESC) WHERE persistent = TRUE;
-- Partial index for non-persistent + updated queries
CREATE INDEX IF NOT EXISTS idx_valores_persistent_false_updated ON valores (updated DESC) WHERE persistent = FALSE;
-- Full index for all values
CREATE INDEX IF NOT EXISTS idx_valores_updated ON valores (updated DESC);

CREATE TABLE IF NOT EXISTS peers (
    id BYTEA NOT NULL,
    fingerprint BIGINT NOT NULL default 0,
    private_key BYTEA DEFAULT NULL,
    nonce BYTEA NOT NULL,
    sequence_number INTEGER NOT NULL DEFAULT 0,
    node_id BYTEA DEFAULT NULL,
    node_signature BYTEA DEFAULT NULL,
    signature BYTEA NOT NULL,
    endpoint VARCHAR(512) NOT NULL,
    extra BYTEA DEFAULT NULL,
    persistent BOOLEAN NOT NULL DEFAULT FALSE,
    created BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    PRIMARY KEY (id, fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_peers_id ON peers (id);

-- Partial index for persistent + announced queries
CREATE INDEX IF NOT EXISTS idx_peers_persistent_true_updated ON peers (updated DESC) WHERE persistent = TRUE;
-- Partial index for non-persistent + updated queries
CREATE INDEX IF NOT EXISTS idx_peers_persistent_false_updated ON peers (updated DESC) WHERE persistent = FALSE;
-- Full index for all values
CREATE INDEX IF NOT EXISTS idx_peers_updated ON peers (updated DESC);