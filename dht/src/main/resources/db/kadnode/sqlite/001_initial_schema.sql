-- Initial schema for Boson DHT node

CREATE TABLE IF NOT EXISTS valores (
    id BLOB NOT NULL PRIMARY KEY,
    public_key BLOB DEFAULT NULL,
    private_key BLOB DEFAULT NULL,
    recipient BLOB DEFAULT NULL,
    nonce BLOB DEFAULT NULL,
    sequence_number INTEGER NOT NULL DEFAULT 0,
    signature BLOB DEFAULT NULL,
    data BLOB NOT NULL,
    persistent BOOLEAN NOT NULL DEFAULT FALSE,
    created INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER))
) WITHOUT ROWID;

-- Partial index for persistent + announced queries
CREATE INDEX IF NOT EXISTS idx_valores_persistent_true_updated ON valores (updated DESC) WHERE persistent = TRUE;
-- Partial index for non-persistent + updated queries
CREATE INDEX IF NOT EXISTS idx_valores_persistent_false_updated ON valores (updated DESC) WHERE persistent = FALSE;
-- Full index for all values
CREATE INDEX IF NOT EXISTS idx_valores_updated ON valores (updated DESC);

CREATE TABLE IF NOT EXISTS peers (
    id BLOB NOT NULL,
    fingerprint INTEGER NOT NULL default 0,
    private_key BLOB DEFAULT NULL,
    nonce BLOB NOT NULL,
    sequence_number INTEGER NOT NULL DEFAULT 0,
    node_id BLOB DEFAULT NULL,
    node_signature BLOB DEFAULT NULL,
    signature BLOB DEFAULT NULL,
    endpoint VARCHAR(512) NOT NULL,
    extra BLOB DEFAULT NULL,
    persistent BOOLEAN NOT NULL DEFAULT FALSE,
    created INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    PRIMARY KEY (id, fingerprint)
) WITHOUT ROWID;

CREATE INDEX IF NOT EXISTS idx_peers_id ON peers (id);

-- Partial index for persistent + announced queries
CREATE INDEX IF NOT EXISTS idx_peers_persistent_true_updated ON peers (updated DESC) WHERE persistent = TRUE;
-- Partial index for non-persistent + updated queries
CREATE INDEX IF NOT EXISTS idx_peers_persistent_false_updated ON peers (updated DESC) WHERE persistent = FALSE;
-- Full index for all values
CREATE INDEX IF NOT EXISTS idx_peers_updated ON peers (updated DESC);