/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.kademlia.storage;

import java.util.List;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;

public class PostgresStorage extends DatabaseStorage implements DataStorage {
	protected static final String STORAGE_URL_PREFIX = "postgresql://";

	private static final List<String> SCHEMA = List.of(
			// Schema version
			"""
			CREATE TABLE IF NOT EXISTS schema_version (
				id INTEGER NOT NULL PRIMARY KEY CHECK (id = 1),
				version INTEGER NOT NULL
			)
			""",
			// Table values
			"""
			CREATE TABLE IF NOT EXISTS valores (
				id BYTEA NOT NULL PRIMARY KEY,
				persistent BOOLEAN NOT NULL DEFAULT FALSE,
				publicKey BYTEA,
				privateKey BYTEA,
				recipient BYTEA,
				nonce BYTEA,
				signature BYTEA,
				sequenceNumber INTEGER NOT NULL DEFAULT 0,
				data BYTEA NOT NULL,
				created BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
				updated BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000
			)
			""",
			// Partial index for persistent + announced queries
			"CREATE INDEX IF NOT EXISTS idx_valores_persistent_true_updated ON valores (updated DESC) WHERE persistent = TRUE",
			// Partial index for non-persistent + updated queries
			"CREATE INDEX IF NOT EXISTS idx_valores_persistent_false_updated ON valores (updated DESC) WHERE persistent = FALSE",
			// Full index for all values
			"CREATE INDEX IF NOT EXISTS idx_valores_updated ON valores (updated DESC)",
			// Table peers
			"""
			CREATE TABLE IF NOT EXISTS peers (
				id BYTEA NOT NULL,
				nodeId BYTEA NOT NULL,
				persistent BOOLEAN NOT NULL DEFAULT FALSE,
				privateKey BYTEA,
				origin BYTEA,
				port INTEGER NOT NULL,
				alternativeURI VARCHAR(512),
				signature BYTEA NOT NULL,
				created BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
				updated BIGINT NOT NULL DEFAULT EXTRACT(EPOCH FROM NOW()) * 1000,
				PRIMARY KEY (id, nodeId)
			)
			""",
			// Partial index for persistent + announced queries
			"CREATE INDEX IF NOT EXISTS idx_peers_persistent_true_updated ON peers (updated DESC) WHERE persistent = TRUE",
			// Partial index for non-persistent + updated queries
			"CREATE INDEX IF NOT EXISTS idx_peers_persistent_false_updated ON peers (updated DESC) WHERE persistent = FALSE",
			// Full index IF NOT EXISTS for all values
			"CREATE INDEX IF NOT EXISTS idx_peers_updated ON peers (updated DESC)",
			// Initialize the schema version
			"INSERT INTO schema_version (id, version) VALUES (1, " + SCHEMA_VERSION + ") ON CONFLICT (id) DO NOTHING"
	);

	protected PostgresStorage(String connectionUri) {
		super(connectionUri);
	}

	// postgresql://[user[:password]@][host][:port][,...][/dbname][?param1=value1&...]
	@Override
	protected void setupSqlClient(Vertx vertx, String connectionUri) {
		PgConnectOptions connectOptions = PgConnectOptions.fromUri(connectionUri);
		PoolOptions poolOptions = new PoolOptions().setMaxSize(8);
		client = PgBuilder.pool()
				.with(poolOptions)
				.connectingTo(connectOptions)
				.using(vertx)
				.build();
	}

	@Override
	protected List<String> createSchemaStatements() {
		return SCHEMA;
	}

	@Override
	protected String selectSchemaVersion() {
		return "SELECT version FROM schema_version WHERE id = 1";
	}

	@Override
	protected String insertSchemaVersion() {
		return "INSERT INTO schema_version (id, version) VALUES (1, $1)";
	}

	@Override
	protected String selectValueById() {
		return "SELECT * FROM valores WHERE id = $1";
	}

	@Override
	protected String selectValuesByPersistentAndAnnouncedBefore() {
		return "SELECT * FROM valores WHERE persistent = $1 AND updated <= $2 ORDER BY updated DESC, id";
	}

	@Override
	protected String selectValuesByPersistentAndAnnouncedBeforePaginated() {
		return "SELECT * FROM valores WHERE persistent = $1 AND updated <= $2 ORDER BY updated DESC, id LIMIT $3 OFFSET $4";
	}

	@Override
	protected String selectAllValues() {
		return "SELECT * FROM valores ORDER BY updated DESC, id";
	}

	@Override
	protected String selectAllValuesPaginated() {
		return "SELECT * FROM valores ORDER BY updated DESC, id LIMIT $1 OFFSET $2";
	}

	/* noinspection
	privateKey = CASE
		WHEN excluded.privateKey IS NOT NULL THEN excluded.privateKey
		ELSE peers.privateKey
	END,
	*/

	@Override
	protected String upsertValue() {
		return """
				INSERT INTO valores (
					id, persistent, publicKey, privateKey, recipient, nonce, signature,
					sequenceNumber, data, created, updated
				) VALUES (
					$1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11
				) ON CONFLICT(id) DO UPDATE SET
					persistent = excluded.persistent,
					publicKey = excluded.publicKey,
					privateKey = excluded.privateKey,
					recipient = excluded.recipient,
					nonce = excluded.nonce,
					signature = excluded.signature,
					sequenceNumber = excluded.sequenceNumber,
					data = excluded.data,
					updated = excluded.updated
				""";
	}

	@Override
	protected String updateValueAnnouncedById() {
		return "UPDATE valores SET updated = $1 WHERE id = $2";
	}

	@Override
	protected String deleteValueById() {
		return "DELETE FROM valores WHERE id = $1";
	}

	@Override
	protected String deleteNonPersistentValuesAnnouncedBefore() {
		return "DELETE FROM valores WHERE persistent = FALSE AND updated < $1";
	}

	@Override
	protected String selectPeerByIdAndNodeId() {
		return "SELECT * FROM peers WHERE id = $1 AND nodeId = $2";
	}

	@Override
	protected String selectPeersById() {
		return "SELECT * FROM peers WHERE id = $1 ORDER BY updated DESC, nodeId";
	}

	@Override
	protected String selectPeersByPersistentAndAnnouncedBefore() {
		return "SELECT * FROM peers WHERE persistent = $1 AND updated <= $2 ORDER BY updated DESC, id, nodeId";
	}

	@Override
	protected String selectPeersByPersistentAndAnnouncedBeforePaginated() {
		return "SELECT * FROM peers WHERE persistent = $1 AND updated <= $2 ORDER BY updated DESC, id, nodeId LIMIT $3 OFFSET $4";
	}

	@Override
	protected String selectAllPeers() {
		return "SELECT * FROM peers ORDER BY updated DESC, id, nodeId";
	}

	@Override
	protected String selectAllPeersPaginated() {
		return "SELECT * FROM peers ORDER BY updated DESC, id, nodeId LIMIT $1 OFFSET $2";
	}

	@Override
	protected String upsertPeer() {
		return """
				INSERT INTO peers (
					id, nodeId, persistent, privateKey, origin, port,
					alternativeURI, signature, created, updated
				) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
				ON CONFLICT(id, nodeId) DO UPDATE SET
					persistent = excluded.persistent,
					privateKey = excluded.privateKey,
					origin = excluded.origin,
					port = excluded.port,
					alternativeURI = excluded.alternativeURI,
					signature = excluded.signature,
					updated = excluded.updated
				""";
	}

	@Override
	protected String updatePeerAnnouncedByIdAndNodeId() {
		return "UPDATE peers SET updated = $1 WHERE id = $2 AND nodeId = $3";
	}

	@Override
	protected String deletePeerByIdAndNodeId() {
		return "DELETE FROM peers WHERE id = $1 AND nodeId = $2";
	}

	@Override
	protected String deletePeersById() {
		return "DELETE FROM peers WHERE id = $1";
	}

	@Override
	protected String deleteNonPersistentPeersAnnouncedBefore() {
		return "DELETE FROM peers WHERE persistent = FALSE AND updated < $1";
	}
}