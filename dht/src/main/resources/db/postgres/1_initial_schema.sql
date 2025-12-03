-- Initial schema for Boson DHT node

CREATE TABLE IF NOT EXISTS valores (
    id BYTEA NOT NULL PRIMARY KEY,
    public_key BYTEA DEFAULT NULL,
    private_key BYTEA DEFAULT NULL,
    recipient BYTEA DEFAULT NULL,
    nonce BYTEA DEFAULT NULL,
    signature BYTEA DEFAULT NULL,
    sequence_number INTEGER NOT NULL DEFAULT 0,
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
    node_id BYTEA NOT NULL,
    private_key BYTEA DEFAULT NULL,
    origin BYTEA DEFAULT NULL,
    port INTEGER NOT NULL,
    alternative_uri VARCHAR(512) DEFAULT NULL,
    signature BYTEA NOT NULL,
    persistent BOOLEAN NOT NULL DEFAULT FALSE,
    created BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    updated BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
    PRIMARY KEY (id, node_id)
);

-- Partial index for persistent + announced queries
CREATE INDEX IF NOT EXISTS idx_peers_persistent_true_updated ON peers (updated DESC) WHERE persistent = TRUE;
-- Partial index for non-persistent + updated queries
CREATE INDEX IF NOT EXISTS idx_peers_persistent_false_updated ON peers (updated DESC) WHERE persistent = FALSE;
-- Full index for all values
CREATE INDEX IF NOT EXISTS idx_peers_updated ON peers (updated DESC);