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
import java.util.function.Function;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.BosonException;
import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.kademlia.exceptions.ImmutableSubstitutionFail;
import io.bosonnetwork.kademlia.exceptions.SequenceNotExpected;
import io.bosonnetwork.kademlia.exceptions.SequenceNotMonotonic;

public abstract class DatabaseStorage implements DataStorage {
	protected static final int SCHEMA_VERSION = 5;

	protected final String connectionUri;

	protected long valueExpiration;
	protected long peerInfoExpiration;

	protected int schemaVersion;

	protected SqlClient client;

	private static final Logger log = LoggerFactory.getLogger(DatabaseStorage.class);

	protected DatabaseStorage(String connectionUri) {
		this.connectionUri = connectionUri;
	}

	protected Future<Void> executeSequentially(SqlConnection connection, List<String> statements, int index) {
		if (index >= statements.size())
			return Future.succeededFuture();

		// Execute current statement and recurse to next
		String sql = statements.get(index);
		log.debug("Executing schema statement: {}", sql);
		return connection.preparedQuery(sql)
				.execute()
				.compose(result -> executeSequentially(connection, statements, index + 1)) // Move to next statement
				.recover(e -> {
					log.error("Schema statement failed: {}", sql, e);
					BosonException error = new DataStorageException("Create database schema failed: " + sql, e);
					return Future.failedFuture(error);
				});
	}

	protected Future<Void> createSchema(List<String> statements) {
		return pool().withTransaction(connection -> executeSequentially(connection, statements, 0));
	}

	protected Pool pool() {
		Pool pool = client instanceof Pool p ? p : null;
		if (pool == null) // This should never happen
			throw new IllegalStateException("SqlClient is not a Pool instance");

		return pool;
	}

	// Returns the first row mapped via the given function, or null if the row set is empty.
	protected static <T> T findUniqueOrNull(RowSet<Row> rows, Function<Row, T> mapper) {
		return mapper.apply(rows.size() == 0 ? null : rows.iterator().next());
	}

	protected static <T> List<T> findMany(RowSet<Row> rows, Function<Row, T> mapper) {
		return rows.stream().map(mapper).toList();
	}

	protected abstract void setupSqlClient(Vertx vertx, String connectionUri);

	protected abstract List<String> createSchemaStatements();

	protected abstract String selectSchemaVersion();

	protected abstract String insertSchemaVersion();

	protected abstract String selectValueById();

	protected abstract String selectValuesByPersistentAndAnnouncedBefore();

	protected abstract String selectValuesByPersistentAndAnnouncedBeforePaginated();

	protected abstract String selectAllValues();

	protected abstract String selectAllValuesPaginated();

	protected abstract String upsertValue();

	protected abstract String updateValueAnnouncedById();

	protected abstract String deleteValueById();

	protected abstract String deleteNonPersistentValuesAnnouncedBefore();

	protected abstract String selectPeerByIdAndNodeId();

	protected abstract String selectPeersById();

	protected abstract String selectPeersByPersistentAndAnnouncedBefore();

	protected abstract String selectPeersByPersistentAndAnnouncedBeforePaginated();

	protected abstract String selectAllPeers();

	protected abstract String selectAllPeersPaginated();

	protected abstract String upsertPeer();

	protected abstract String updatePeerAnnouncedByIdAndNodeId();

	protected abstract String deletePeerByIdAndNodeId();

	protected abstract String deletePeersById();

	protected abstract String deleteNonPersistentPeersAnnouncedBefore();

	protected Future<Integer> getOrInitSchemaVersion() {
		log.debug("Checking schema version...");
		return client.preparedQuery(selectSchemaVersion())
				.execute()
				.compose(rows -> {
					if (rows.size() > 0) {
						int version = findUniqueOrNull(rows, DatabaseStorage::rowToInteger);
						log.info("Detected existing schema version: {}", version);
						return Future.succeededFuture(version);
					} else {
						log.info("No schema version found, setting version {}", SCHEMA_VERSION);
						return client.preparedQuery(insertSchemaVersion())
								.execute(Tuple.of(SCHEMA_VERSION))
								.map(v -> SCHEMA_VERSION);
					}
				});
	}

	@Override
	public Future<Integer> initialize(Vertx vertx, long valueExpiration, long peerInfoExpiration) {
		if (client != null)
			return Future.failedFuture(new DataStorageException("Storage already initialized"));

		log.info("Initializing storage with connection URI: {}", connectionUri);

		this.valueExpiration = valueExpiration;
		this.peerInfoExpiration = peerInfoExpiration;

		setupSqlClient(vertx, connectionUri);

		log.info("Creating database schema...");
		return createSchema(createSchemaStatements())
				.compose(unused -> getOrInitSchemaVersion())
				.andThen(ar -> {
					if (ar.succeeded()) {
						schemaVersion = ar.result();
						log.info("Database schema created successfully, version: {}", schemaVersion);
					} else {
						log.error("Database schema creation failed", ar.cause());
					}
				}).recover(cause ->
					Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<Void> close() {
		if (client == null) {
			log.info("Storage already closed");
			return Future.succeededFuture();
		}
		log.info("Closing storage...");
		Future<Void> future = client.close();
		client = null;
		return future.recover(cause ->
				Future.failedFuture(new DataStorageException("Database operation failed", cause))
		);
	}

	@Override
	public int getSchemaVersion() {
		return schemaVersion;
	}

	@Override
	public Future<Void> purge() {
		long now = System.currentTimeMillis();

		log.info("Purging expired values and peers...");
		return pool().withTransaction(connection ->
				connection.preparedQuery(deleteNonPersistentValuesAnnouncedBefore())
						.execute(Tuple.of(now - valueExpiration))
						.compose(unused ->
								connection.preparedQuery(deleteNonPersistentPeersAnnouncedBefore())
										.execute(Tuple.of(now - peerInfoExpiration))
						)
		).andThen(ar -> {
			if (ar.succeeded())
				log.info("Purge completed successfully");
			else
				log.error("Failed to purge expired values and peers", ar.cause());
		}).recover(cause ->
				Future.failedFuture(new DataStorageException("Database operation failed", cause))
		).mapEmpty();
	}

	@Override
	public Future<Value> putValue(Value value) {
		return putValue(value, false, -1);
	}

	@Override
	public Future<Value> putValue(Value value, boolean persistent) {
		return putValue(value, persistent, -1);
	}

	@Override
	public Future<Value> putValue(Value value, boolean persistent, int expectedSequenceNumber) {
		log.debug("Putting value with id: {}, persistent: {}, expectedSequenceNumber: {}",
				value.getId(), persistent, expectedSequenceNumber);
		log.debug("Trying to check the existing value with id: {}", value.getId());
		return getValue(value.getId()).compose(existing -> {
			if (existing != null) {
				// Immutable check
				if (existing.isMutable() != value.isMutable()) {
					log.warn("Rejecting value {}: cannot replace mismatched mutable/immutable", value.getId());
					return Future.failedFuture(new ImmutableSubstitutionFail("Cannot replace mismatched mutable/immutable value"));
				}

				if (value.getSequenceNumber() < existing.getSequenceNumber()) {
					log.warn("Rejecting value {}: sequence number not monotonic", value.getId());
					return Future.failedFuture(new SequenceNotMonotonic("Sequence number less than current"));
				}

				if (expectedSequenceNumber >= 0 && existing.getSequenceNumber() > expectedSequenceNumber) {
					log.warn("Rejecting value {}: sequence number not expected", value.getId());
					return Future.failedFuture(new SequenceNotExpected("Sequence number not expected"));
				}

				if (existing.hasPrivateKey() && !value.hasPrivateKey()) {
					// Skip update if the existing value is owned by this node and the new value is not.
					// Should not throw NotOwnerException, just silently ignores to avoid disrupting valid operations.
					log.info("Skipping to update value for id {}: owned by this node", value.getId());
					return Future.succeededFuture(existing);
				}
			}

			long now = System.currentTimeMillis();
			return client.preparedQuery(upsertValue())
					.execute(Tuple.of(value.getId().bytes(),
							persistent,
							value.getPublicKey() != null ? value.getPublicKey().bytes() : null,
							value.getPrivateKey(),
							value.getRecipient() != null ? value.getRecipient().bytes() : null,
							value.getNonce(),
							value.getSignature(),
							value.getSequenceNumber(),
							value.getData(),
							now,
							now))
					.map(v -> value);
		}).andThen(ar -> {
			if (ar.succeeded())
				log.debug("Put value with id: {} successfully", value.getId());
			else
				log.error("Failed to put value with id: {}", value.getId(), ar.cause());
		}).recover(cause ->
				Future.failedFuture(new DataStorageException("Database operation failed", cause))
		);
	}

	@Override
	public Future<Value> getValue(Id id) {
		log.debug("Getting value with id: {}", id);
		return client.preparedQuery(selectValueById())
				.execute(Tuple.of(id.bytes()))
				.map(rows -> findUniqueOrNull(rows, DatabaseStorage::rowToValue))
				.andThen(ar -> {
					if (ar.succeeded()) {
						if (ar.result() != null)
							log.debug("Got value with id: {}", id);
						else
							//noinspection LoggingSimilarMessage
							log.debug("No value found with id: {}", id);
					} else {
						log.error("Failed to get value with id: {}", id, ar.cause());
					}
				}).recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<Value>> getValues() {
		return client.preparedQuery(selectAllValues())
				.execute()
				.map(rows -> findMany(rows, DatabaseStorage::rowToValue))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<Value>> getValues(int offset, int limit) {
		return client.preparedQuery(selectAllValuesPaginated())
				.execute(Tuple.of(limit, offset))
				.map(rows -> findMany(rows, DatabaseStorage::rowToValue))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<Value>> getValues(boolean persistent, long announcedBefore) {
		return client.preparedQuery(selectValuesByPersistentAndAnnouncedBefore())
				.execute(Tuple.of(persistent, announcedBefore))
				.map(rows -> findMany(rows, DatabaseStorage::rowToValue))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<Value>> getValues(boolean persistent, long announcedBefore, int offset, int limit) {
		return client.preparedQuery(selectValuesByPersistentAndAnnouncedBeforePaginated())
				.execute(Tuple.of(persistent, announcedBefore, limit, offset))
				.map(rows -> findMany(rows, DatabaseStorage::rowToValue))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<Long> updateValueAnnouncedTime(Id id) {
		log.debug("Updating value announced time with id: {}", id);
		long now = System.currentTimeMillis();
		return client.preparedQuery(updateValueAnnouncedById())
				.execute(Tuple.of(now, id.bytes()))
				.map(v -> v.rowCount() > 0 ? now : 0L)
				.andThen(ar -> {
					if (ar.succeeded()) {
						if (ar.result() != 0)
							log.debug("Updated value announced time with id: {}", id);
						else
							//noinspection LoggingSimilarMessage
							log.debug("No value found with id: {}", id);
					} else {
						log.error("Failed to update value announced time with id: {}", id, ar.cause());
					}
				}).recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<Boolean> removeValue(Id id) {
		log.debug("Removing value with id: {}", id);
		return client.preparedQuery(deleteValueById())
				.execute(Tuple.of(id.bytes()))
				.map(rowSet -> rowSet.rowCount() >= 1)
				.andThen(ar -> {
					if (ar.succeeded()) {
						if (ar.result())
							log.debug("Removed value with id: {}", id);
						else
							log.debug("No value found with id: {}", id);
					} else {
						log.error("Failed to remove value with id: {}", id, ar.cause());
					}
				}).recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<PeerInfo> putPeer(PeerInfo peerInfo) {
		return putPeer(peerInfo, false);
	}

	@Override
	public Future<PeerInfo> putPeer(PeerInfo peerInfo, boolean persistent) {
		return putPeer(client, peerInfo, persistent);
	}

	protected Future<PeerInfo> putPeer(SqlClient sqlClient, PeerInfo peerInfo, boolean persistent) {
		log.debug("Putting peer with id: {} @ {}, persistent: {}", peerInfo.getId(), peerInfo.getNodeId(), persistent);
		log.debug("Trying to check the existing peer with id: {} @ {}", peerInfo.getId(), peerInfo.getNodeId());
		return getPeer(sqlClient, peerInfo.getId(), peerInfo.getNodeId()).compose(existing -> {
			if (existing != null && existing.hasPrivateKey() && !peerInfo.hasPrivateKey()) {
				// Skip update if the existing peer info is owned by this node and the new peer info is not.
				// Should not throw NotOwnerException, just silently ignores to avoid disrupting valid operations.
				log.info("Skipping to update peer for id {} @ {}: owned by this node", peerInfo.getId(), peerInfo.getNodeId());
				return Future.succeededFuture(peerInfo);
			}

			long now = System.currentTimeMillis();
			return sqlClient.preparedQuery(upsertPeer())
					.execute(Tuple.of(peerInfo.getId().bytes(),
							peerInfo.getNodeId().bytes(),
							persistent,
							peerInfo.getPrivateKey(),
							peerInfo.getOrigin() != null ? peerInfo.getOrigin().bytes() : null,
							peerInfo.getPort(),
							peerInfo.getAlternativeURL(),
							peerInfo.getSignature(),
							now,
							now))
					.map(v -> peerInfo);
		}).andThen(ar -> {
			if (ar.succeeded())
				log.debug("Put peer with id: {} @ {} successfully", peerInfo.getId(), peerInfo.getNodeId());
			else
				log.error("Failed to put peer with id: {} @ {}", peerInfo.getId(), peerInfo.getNodeId(), ar.cause());
		}).recover(cause ->
				Future.failedFuture(new DataStorageException("Database operation failed", cause))
		);
	}

	/* noinspection
	protected Future<Void> putPeersSequentially(SqlClient sqlClient, List<PeerInfo> peerInfos, int index) {
		return index >= peerInfos.size() ?
				Future.succeededFuture() :
				putPeer(sqlClient, peerInfos.get(index), false).compose(r ->
						putPeersSequentially(sqlClient, peerInfos, index + 1)
				);
	}

	@Override
	public Future<List<PeerInfo>> putPeers(List<PeerInfo> peerInfos) {
		if (peerInfos.isEmpty())
			return Future.succeededFuture(peerInfos);

		return pool().withTransaction(connection ->
				putPeersSequentially(connection, peerInfos, 0)
						.map(v -> peerInfos)
		);
	}
	*/

	@Override
	public Future<List<PeerInfo>> putPeers(List<PeerInfo> peerInfos) {
		if (peerInfos.isEmpty())
			return Future.succeededFuture(peerInfos);

		long now = System.currentTimeMillis();
		List<Tuple> tuples = peerInfos.stream().map(peerInfo -> Tuple.of(
				peerInfo.getId().bytes(),
				peerInfo.getNodeId().bytes(),
				false,
				peerInfo.getPrivateKey(),
				peerInfo.getOrigin() != null ? peerInfo.getOrigin().bytes() : null,
				peerInfo.getPort(),
				peerInfo.getAlternativeURL(),
				peerInfo.getSignature(),
				now,
				now
		)).toList();

		return pool().withTransaction(connection ->
				connection.preparedQuery(upsertPeer())
						.executeBatch(tuples)
						.map(v -> peerInfos)
		).andThen(ar -> {
			if (ar.succeeded())
				log.debug("Put {} peers successfully", peerInfos.size());
			else
				log.error("Failed to put peers", ar.cause());
		}).recover(cause ->
				Future.failedFuture(new DataStorageException("Database operation failed", cause))
		);
	}

	@Override
	public Future<PeerInfo> getPeer(Id id, Id nodeId) {
		return getPeer(client, id, nodeId);
	}

	protected Future<PeerInfo> getPeer(SqlClient sqlClient, Id id, Id nodeId) {
		log.debug("Getting peer with id: {} @ {}", id, nodeId);
		return sqlClient.preparedQuery(selectPeerByIdAndNodeId())
				.execute(Tuple.of(id.bytes(), nodeId.bytes()))
				.map(rows -> findUniqueOrNull(rows, DatabaseStorage::rowToPeer))
				.andThen(ar -> {
					if (ar.succeeded()) {
						if (ar.result() != null)
							log.debug("Got peer with id: {} @ {}", id, nodeId);
						else
							//noinspection LoggingSimilarMessage
							log.debug("No peer found with id: {} @ {}", id, nodeId);
					} else {
						log.error("Failed to get peer with id: {} @ {}", id, nodeId, ar.cause());
					}
				}).recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(Id id) {
		log.debug("Getting peers with id: {}", id);
		return client.preparedQuery(selectPeersById())
				.execute(Tuple.of(id.bytes()))
				.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
				.andThen(ar -> {
					if (ar.succeeded()) {
						if (!ar.result().isEmpty())
							log.debug("Got peers with id: {}", id);
						else
							//noinspection LoggingSimilarMessage
							log.debug("No peers found with id: {}", id);
					} else {
						log.error("Failed to get peers with id: {}", id, ar.cause());
					}
				}).recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<PeerInfo>> getPeers() {
		return client.preparedQuery(selectAllPeers())
				.execute()
				.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(int offset, int limit) {
		return client.preparedQuery(selectAllPeersPaginated())
				.execute(Tuple.of(limit, offset))
				.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(boolean persistent, long announcedBefore) {
		return client.preparedQuery(selectPeersByPersistentAndAnnouncedBefore())
				.execute(Tuple.of(persistent, announcedBefore))
				.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(boolean persistent, long announcedBefore, int offset, int limit) {
		return client.preparedQuery(selectPeersByPersistentAndAnnouncedBeforePaginated())
				.execute(Tuple.of(persistent, announcedBefore, limit, offset))
				.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<Long> updatePeerAnnouncedTime(Id id, Id nodeId) {
		log.debug("Updating peer announced time with id: {} @ {}", id, nodeId);
		long now = System.currentTimeMillis();
		return client.preparedQuery(updatePeerAnnouncedByIdAndNodeId())
				.execute(Tuple.of(now, id.bytes(), nodeId.bytes()))
				.map(v ->  v.rowCount() > 0 ? now : 0L)
				.andThen(ar -> {
					if (ar.succeeded()) {
						if (ar.result() != 0)
							log.debug("Updated peer announced time with id: {} @ {}", id, nodeId);
						else
							log.debug("No peer found with id: {} @ {}", id, nodeId);
					} else {
						log.error("Failed to update peer announced time with id: {} @ {}", id, nodeId, ar.cause());
					}
				}).recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<Boolean> removePeer(Id id, Id nodeId) {
		log.debug("Removing peer with id: {} @ {}", id, nodeId);
		return client.preparedQuery(deletePeerByIdAndNodeId())
				.execute(Tuple.of(id.bytes(), nodeId.bytes()))
				.map(rowSet -> rowSet.rowCount() >= 1)
				.andThen(ar -> {
					if (ar.succeeded()) {
						if (ar.result())
							log.debug("Removed peer with id: {} @ {}", id, nodeId);
						else
							//noinspection LoggingSimilarMessage
							log.debug("No peer found with id: {} @ {}", id, nodeId);
					} else {
						log.error("Failed to remove peer with id: {} @ {}", id, nodeId, ar.cause());
					}
				}).recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<Boolean> removePeers(Id id) {
		log.debug("Removing peers with id: {}", id);
		return client.preparedQuery(deletePeersById())
				.execute(Tuple.of(id.bytes()))
				.map(rowSet -> rowSet.rowCount() >= 1)
				.andThen(ar -> {
					if (ar.succeeded()) {
						if (ar.result())
							log.debug("Removed peers with id: {}", id);
						else
							log.debug("No peers found with id: {}", id);
					} else {
						log.error("Failed to remove peers with id: {}", id, ar.cause());
					}
				}).recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@SuppressWarnings("SameParameterValue")
	protected static int rowToInteger(Row row, int defaultValue) {
		return row != null ? row.getInteger(0) : defaultValue;
	}

	protected static int rowToInteger(Row row) {
		return rowToInteger(row, 0);
	}

	protected static Value rowToValue(Row row) {
		if (row == null)
			return null;

		// Column indices:
		// 0: id (BLOB, NOT NULL)
		// 1: persistent (BOOLEAN, NOT NULL)
		// 2: publicKey (BLOB, nullable)
		// 3: privateKey (BLOB, nullable)
		// 4: recipient (BLOB, nullable)
		// 5: nonce (BLOB, nullable)
		// 6: signature (BLOB, nullable)
		// 7: sequenceNumber (INTEGER, NOT NULL)
		// 8: data (BLOB, nullable)
		// 9: created (BIGINT, NOT NULL)
		// 10: updated (BIGINT, NOT NULL)

		// skip 0:id
		// skip 1:persistent
		Buffer buffer = row.getBuffer(2);
		Id publicKey = buffer == null ? null : Id.of(buffer.getBytes());
		buffer = row.getBuffer(3);
		byte[] privateKey = buffer == null ? null : buffer.getBytes();
		buffer = row.getBuffer(4);
		Id recipient = buffer == null ? null : Id.of(buffer.getBytes());
		buffer = row.getBuffer(5);
		byte[] nonce = buffer == null ? null : buffer.getBytes();
		buffer = row.getBuffer(6);
		byte[] signature = buffer == null ? null : buffer.getBytes();
		int sequenceNumber = row.getInteger(7); // NOT NULL
		buffer = row.getBuffer(8);
		byte[] data = buffer == null ? null : buffer.getBytes();

		return Value.of(publicKey, privateKey, recipient, nonce, sequenceNumber, signature, data);
	}

	protected static PeerInfo rowToPeer(Row row) {
		if (row == null)
			return null;

		// Column indices:
		// 0: id (BLOB, NOT NULL)
		// 1: nodeId (BLOB, NOT NULL)
		// 2: persistent (BOOLEAN, NOT NULL)
		// 3: privateKey (BLOB, nullable)
		// 4: origin (BLOB, nullable)
		// 5: port (INTEGER, NOT NULL)
		// 6: alternativeURL (TEXT, nullable)
		// 7: signature (BLOB, nullable)
		// 8: created (BIGINT, NOT NULL)
		// 9: updated (BIGINT, NOT NULL)

		Id id = Id.of(row.getBuffer(0).getBytes()); // NOT NULL
		Id nodeId = Id.of(row.getBuffer(1).getBytes()); // NOT NULL
		Buffer buffer = row.getBuffer(3);
		byte[] privateKey = buffer == null ? null : buffer.getBytes();
		buffer = row.getBuffer(4);
		Id origin = buffer == null ? null : Id.of(buffer.getBytes());
		int port = row.getInteger(5); // NOT NULL
		String alternativeURL = row.getString(6); // Nullable
		buffer = row.getBuffer(7);
		byte[] signature = buffer == null ? null : buffer.getBytes();

		return PeerInfo.of(id, privateKey, nodeId, origin, port, alternativeURL, signature);
	}
}