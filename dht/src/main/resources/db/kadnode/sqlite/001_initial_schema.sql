-- Initial schema for Boson DHT node

CREATE TABLE IF NOT EXISTS valores (
    id BLOB NOT NULL PRIMARY KEY,
    public_key BLOB DEFAULT NULL,
    private_key BLOB DEFAULT NULL,
    recipient BLOB DEFAULT NULL,
    nonce BLOB DEFAULT NULL,
    signature BLOB DEFAULT NULL,
    sequence_number INTEGER NOT NULL DEFAULT 0,
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
    node_id BLOB NOT NULL,
    private_key BLOB DEFAULT NULL,
    origin BLOB DEFAULT NULL,
    port INTEGER NOT NULL,
    alternative_uri VARCHAR(512) DEFAULT NULL,
    signature BLOB NOT NULL,
    persistent BOOLEAN NOT NULL DEFAULT FALSE,
    created INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    updated INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
    PRIMARY KEY (id, node_id)
) WITHOUT ROWID;

-- Partial index for persistent + announced queries
CREATE INDEX IF NOT EXISTS idx_peers_persistent_true_updated ON peers (updated DESC) WHERE persistent = TRUE;
-- Partial index for non-persistent + updated queries
CREATE INDEX IF NOT EXISTS idx_peers_persistent_false_updated ON peers (updated DESC) WHERE persistent = FALSE;
-- Full index for all values
CREATE INDEX IF NOT EXISTS idx_peers_updated ON peers (updated DESC);