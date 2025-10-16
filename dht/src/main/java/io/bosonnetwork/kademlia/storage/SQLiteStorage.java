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
import io.vertx.jdbcclient.JDBCPool;
import org.sqlite.SQLiteDataSource;

public class SQLiteStorage extends DatabaseStorage implements DataStorage {
	protected static final String STORAGE_URL_PREFIX = "jdbc:sqlite:";

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
						id BLOB NOT NULL PRIMARY KEY,
						persistent BOOLEAN NOT NULL DEFAULT FALSE,
						publicKey BLOB,
						privateKey BLOB,
						recipient BLOB,
						nonce BLOB,
						signature BLOB,
						sequenceNumber INTEGER NOT NULL DEFAULT 0,
						data BLOB NOT NULL,
						created INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
						updated INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER))
					) WITHOUT ROWID
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
						id BLOB NOT NULL,
						nodeId BLOB NOT NULL,
						persistent BOOLEAN NOT NULL DEFAULT FALSE,
						privateKey BLOB,
						origin BLOB,
						port INTEGER NOT NULL,
						alternativeURI TEXT,
						signature BLOB NOT NULL,
						created INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
						updated INTEGER NOT NULL DEFAULT (CAST(unixepoch('subsec') * 1000 AS INTEGER)),
						PRIMARY KEY (id, nodeId)
					) WITHOUT ROWID
					""",
			// Partial index for persistent + announced queries
			"CREATE INDEX IF NOT EXISTS idx_peers_persistent_true_updated ON peers (updated DESC) WHERE persistent = TRUE",
			// Partial index for non-persistent + updated queries
			"CREATE INDEX IF NOT EXISTS idx_peers_persistent_false_updated ON peers (updated DESC) WHERE persistent = FALSE",
			// Full index for all values
			"CREATE INDEX IF NOT EXISTS idx_peers_updated ON peers (updated DESC)",
			// Initialize the schema version
			"INSERT INTO schema_version (id, version) VALUES (1, " + SCHEMA_VERSION + ") ON CONFLICT (id) DO NOTHING"
	);

	protected SQLiteStorage(String connectionUri) {
		super(connectionUri);
	}

	@Override
	protected void setupSqlClient(Vertx vertx, String connectionUri) {
		/*/
		// Vert.x 5.x style
		JDBCConnectOptions connectOptions = new JDBCConnectOptions()
				.setJdbcUrl(connectionUri);
		// Single connection recommended for SQLite
		PoolOptions poolOptions = new PoolOptions().setMaxSize(1);
		client = JDBCPool.pool(vertx, connectOptions, poolOptions);
		 */

		// Vert.x 4.x style
		SQLiteDataSource ds = new SQLiteDataSource();
		ds.setUrl(connectionUri);
		client = JDBCPool.pool(vertx, ds);
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
		return "INSERT INTO schema_version (id, version) VALUES (1, ?)";
	}

	@Override
	protected String selectValueById() {
		return "SELECT * FROM valores WHERE id = ?";
	}

	@Override
	protected String selectValuesByPersistentAndAnnouncedBefore() {
		return "SELECT * FROM valores WHERE persistent = ? AND updated <= ? ORDER BY updated DESC, id";
	}

	@Override
	protected String selectValuesByPersistentAndAnnouncedBeforePaginated() {
		return "SELECT * FROM valores WHERE persistent = ? AND updated <= ? ORDER BY updated DESC, id LIMIT ? OFFSET ?";
	}

	@Override
	protected String selectAllValues() {
		return "SELECT * FROM valores ORDER BY updated DESC, id";
	}

	@Override
	protected String selectAllValuesPaginated() {
		return "SELECT * FROM valores ORDER BY updated DESC, id LIMIT ? OFFSET ?";
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
					?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
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
		return "UPDATE valores SET updated = ? WHERE id = ?";
	}

	protected String deleteValueById() {
		return "DELETE FROM valores WHERE id = ?";
	}

	@Override
	protected String deleteNonPersistentValuesAnnouncedBefore() {
		return "DELETE FROM valores WHERE persistent = FALSE AND updated < ?";
	}

	@Override
	protected String selectPeerByIdAndNodeId() {
		return "SELECT * FROM peers WHERE id = ? AND nodeId = ?";
	}

	@Override
	protected String selectPeersById() {
		return "SELECT * FROM peers WHERE id = ? ORDER BY updated DESC, nodeId";
	}

	@Override
	protected String selectPeersByPersistentAndAnnouncedBefore() {
		return "SELECT * FROM peers WHERE persistent = ? AND updated <= ? ORDER BY updated DESC, id, nodeId";
	}

	@Override
	protected String selectPeersByPersistentAndAnnouncedBeforePaginated() {
		return "SELECT * FROM peers WHERE persistent = ? AND updated <= ? ORDER BY updated DESC, id, nodeId LIMIT ? OFFSET ?";
	}

	@Override
	protected String selectAllPeers() {
		return "SELECT * FROM peers ORDER BY updated DESC, id, nodeId";
	}

	@Override
	protected String selectAllPeersPaginated() {
		return "SELECT * FROM peers ORDER BY updated DESC, id, nodeId LIMIT ? OFFSET ?";
	}

	@Override
	protected String upsertPeer() {
		return """
				INSERT INTO peers (
					id, nodeId, persistent, privateKey, origin, port,
					alternativeURI, signature, created, updated
				) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
		return "UPDATE peers SET updated = ? WHERE id = ? AND nodeId = ?";
	}

	@Override
	protected String deletePeerByIdAndNodeId() {
		return "DELETE FROM peers WHERE id = ? AND nodeId = ?";
	}

	@Override
	protected String deletePeersById() {
		return "DELETE FROM peers WHERE id = ?";
	}

	@Override
	protected String deleteNonPersistentPeersAnnouncedBefore() {
		return "DELETE FROM peers WHERE persistent = FALSE AND updated < ?";
	}
}