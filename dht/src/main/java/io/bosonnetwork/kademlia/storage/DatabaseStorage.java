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

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.templates.SqlTemplate;
import org.slf4j.Logger;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.database.VersionedSchema;
import io.bosonnetwork.database.VertxDatabase;
import io.bosonnetwork.kademlia.exceptions.ImmutableSubstitutionFail;
import io.bosonnetwork.kademlia.exceptions.SequenceNotExpected;
import io.bosonnetwork.kademlia.exceptions.SequenceNotMonotonic;

public abstract class DatabaseStorage implements DataStorage, VertxDatabase {
	protected long valueExpiration;
	protected long peerInfoExpiration;

	protected int schemaVersion;

	protected abstract Logger getLogger();

	protected abstract void init(Vertx vertx);

	protected abstract Path getSchemaPath();

	protected abstract SqlDialect getDialect();

	@Override
	public Future<Integer> initialize(Vertx vertx, long valueExpiration, long peerInfoExpiration) {
		init(vertx);

		this.valueExpiration = valueExpiration;
		this.peerInfoExpiration = peerInfoExpiration;

		VersionedSchema schema = VersionedSchema.init(getClient(), getSchemaPath());
		return schema.migrate().andThen(ar -> {
					if (ar.succeeded()) {
						schemaVersion = schema.getCurrentVersion().version();
						getLogger().info("Database is ready, current schema version: {}", schemaVersion);
					} else {
						getLogger().error("Schema migration failed, current schema version: {}",
								schema.getCurrentVersion().version(), ar.cause());
					}
				}).map(v -> schema.getCurrentVersion().version())
				.recover(cause ->
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

		getLogger().info("Purging expired values and peers...");
		return withTransaction(c ->
				SqlTemplate.forUpdate(c, getDialect().deleteNonPersistentValuesAnnouncedBefore())
						.execute(Map.of("updatedBefore", now - valueExpiration))
						.compose(r ->
								SqlTemplate.forUpdate(c, getDialect().deleteNonPersistentPeersAnnouncedBefore())
										.execute(Map.of("updatedBefore", now - peerInfoExpiration))
										.map((Void) null)
						)
		).andThen(ar -> {
			if (ar.succeeded())
				getLogger().info("Purge completed successfully");
			else
				getLogger().error("Failed to purge expired values and peers", ar.cause());
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
		getLogger().debug("Putting value with id: {}, persistent: {}, expectedSequenceNumber: {}",
				value.getId(), persistent, expectedSequenceNumber);
		getLogger().debug("Trying to check the existing value with id: {}", value.getId());
		return getValue(value.getId()).compose(existing -> {
			if (existing != null) {
				// Immutable check
				if (existing.isMutable() != value.isMutable()) {
					getLogger().warn("Rejecting value {}: cannot replace mismatched mutable/immutable", value.getId());
					return Future.failedFuture(new ImmutableSubstitutionFail("Cannot replace mismatched mutable/immutable value"));
				}

				if (value.getSequenceNumber() < existing.getSequenceNumber()) {
					getLogger().warn("Rejecting value {}: sequence number not monotonic", value.getId());
					return Future.failedFuture(new SequenceNotMonotonic("Sequence number less than current"));
				}

				if (expectedSequenceNumber >= 0 && existing.getSequenceNumber() > expectedSequenceNumber) {
					getLogger().warn("Rejecting value {}: sequence number not expected", value.getId());
					return Future.failedFuture(new SequenceNotExpected("Sequence number not expected"));
				}

				if (existing.hasPrivateKey() && !value.hasPrivateKey()) {
					// Skip update if the existing value is owned by this node and the new value is not.
					// Should not throw NotOwnerException, just silently ignore to avoid disrupting valid operations.
					getLogger().info("Skipping to update value for id {}: owned by this node", value.getId());
					return Future.succeededFuture(existing);
				}
			}

			return withTransaction(c ->
					SqlTemplate.forUpdate(c, getDialect().upsertValue())
							.execute(valueToMap(value, persistent))
							.map(v -> value));
		}).andThen(ar -> {
			if (ar.succeeded())
				getLogger().debug("Put value with id: {} successfully", value.getId());
			else
				getLogger().error("Failed to put value with id: {}", value.getId(), ar.cause());
		}).recover(cause ->
				Future.failedFuture(new DataStorageException("Database operation failed", cause))
		);
	}

	@Override
	public Future<Value> getValue(Id id) {
		getLogger().debug("Getting value with id: {}", id);
		return SqlTemplate.forQuery(getClient(), getDialect().selectValueById())
				.execute(Map.of("id", id.bytes()))
				.map(rows -> findUnique(rows, DatabaseStorage::rowToValue))
				.andThen(ar -> {
					if (ar.succeeded()) {
						if (ar.result() != null)
							getLogger().debug("Got value with id: {}", id);
						else
							//noinspection LoggingSimilarMessage
							getLogger().debug("No value found with id: {}", id);
					} else {
						getLogger().error("Failed to get value with id: {}", id, ar.cause());
					}
				}).recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<Value>> getValues() {
		return query(getDialect().selectAllValues())
				.execute()
				.map(rows -> findMany(rows, DatabaseStorage::rowToValue))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<Value>> getValues(int offset, int limit) {
		return SqlTemplate.forQuery(getClient(), getDialect().selectAllValuesPaginated())
				.execute(Map.of("limit", limit, "offset", offset))
				.map(rows -> findMany(rows, DatabaseStorage::rowToValue))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<Value>> getValues(boolean persistent, long announcedBefore) {
		return SqlTemplate.forQuery(getClient(), getDialect().selectValuesByPersistentAndAnnouncedBefore())
				.execute(Map.of("persistent", persistent, "updatedBefore", announcedBefore))
				.map(rows -> findMany(rows, DatabaseStorage::rowToValue))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<Value>> getValues(boolean persistent, long announcedBefore, int offset, int limit) {
		return SqlTemplate.forQuery(getClient(), getDialect().selectValuesByPersistentAndAnnouncedBeforePaginated())
				.execute(Map.of(
						"persistent", persistent,
						"updatedBefore", announcedBefore,
						"limit", limit,
						"offset", offset))
				.map(rows -> findMany(rows, DatabaseStorage::rowToValue))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<Long> updateValueAnnouncedTime(Id id) {
		getLogger().debug("Updating value announced time with id: {}", id);
		long now = System.currentTimeMillis();
		return withTransaction(c ->
				SqlTemplate.forUpdate(c, getDialect().updateValueAnnouncedById())
						.execute(Map.of("id", id.bytes(), "updated", now))
						.map(r -> r.rowCount() > 0 ? now : 0L)
		).andThen(ar -> {
			if (ar.succeeded()) {
				if (ar.result() != 0)
					getLogger().debug("Updated value announced time with id: {}", id);
				else
					//noinspection LoggingSimilarMessage
					getLogger().debug("No value found with id: {}", id);
			} else {
				getLogger().error("Failed to update value announced time with id: {}", id, ar.cause());
			}
		}).recover(cause ->
				Future.failedFuture(new DataStorageException("Database operation failed", cause))
		);
	}

	@Override
	public Future<Boolean> removeValue(Id id) {
		getLogger().debug("Removing value with id: {}", id);
		return withTransaction(c ->
				SqlTemplate.forUpdate(c, getDialect().deleteValueById())
						.execute(Map.of("id", id.bytes()))
						.map(this::hasEffectedRows)
		).andThen(ar -> {
			if (ar.succeeded()) {
				if (ar.result())
					getLogger().debug("Removed value with id: {}", id);
				else
					getLogger().debug("No value found with id: {}", id);
			} else {
				getLogger().error("Failed to remove value with id: {}", id, ar.cause());
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
		getLogger().debug("Putting peer with id: {} @ {}, persistent: {}", peerInfo.getId(), peerInfo.getNodeId(), persistent);
		getLogger().debug("Trying to check the existing peer with id: {} @ {}", peerInfo.getId(), peerInfo.getNodeId());
		return getPeer(peerInfo.getId(), peerInfo.getNodeId()).compose(existing -> {
			if (existing != null && existing.hasPrivateKey() && !peerInfo.hasPrivateKey()) {
				// Skip update if the existing peer info is owned by this node and the new peer info is not.
				// Should not throw NotOwnerException, just silently ignore to avoid disrupting valid operations.
				getLogger().info("Skipping to update peer for id {} @ {}: owned by this node", peerInfo.getId(), peerInfo.getNodeId());
				return Future.succeededFuture(peerInfo);
			}

			return withTransaction(c ->
					SqlTemplate.forUpdate(c, getDialect().upsertPeer())
							.execute(peerToMap(peerInfo, persistent))
							.map(v -> peerInfo));
		}).andThen(ar -> {
			if (ar.succeeded())
				getLogger().debug("Put peer with id: {} @ {} successfully", peerInfo.getId(), peerInfo.getNodeId());
			else
				getLogger().error("Failed to put peer with id: {} @ {}", peerInfo.getId(), peerInfo.getNodeId(), ar.cause());
		}).recover(cause ->
				Future.failedFuture(new DataStorageException("Database operation failed", cause))
		);
	}

	@Override
	public Future<List<PeerInfo>> putPeers(List<PeerInfo> peerInfos) {
		if (peerInfos.isEmpty())
			return Future.succeededFuture(peerInfos);

		List<Map<String, Object>> params = peerInfos.stream().map(p -> peerToMap(p, false)).toList();

		return withTransaction(c ->
				SqlTemplate.forUpdate(c, getDialect().upsertPeer())
						.executeBatch(params)
						.map(v -> peerInfos)
		).andThen(ar -> {
			if (ar.succeeded())
				getLogger().debug("Put {} peers successfully", peerInfos.size());
			else
				getLogger().error("Failed to put peers", ar.cause());
		}).recover(cause ->
				Future.failedFuture(new DataStorageException("Database operation failed", cause))
		);
	}

	@Override
	public Future<PeerInfo> getPeer(Id id, Id nodeId) {
		getLogger().debug("Getting peer with id: {} @ {}", id, nodeId);
		return SqlTemplate.forQuery(getClient(), getDialect().selectPeerByIdAndNodeId())
				.execute(Map.of("id", id.bytes(), "nodeId", nodeId.bytes()))
				.map(rows -> findUnique(rows, DatabaseStorage::rowToPeer))
				.andThen(ar -> {
					if (ar.succeeded()) {
						if (ar.result() != null)
							getLogger().debug("Got peer with id: {} @ {}", id, nodeId);
						else
							//noinspection LoggingSimilarMessage
							getLogger().debug("No peer found with id: {} @ {}", id, nodeId);
					} else {
						getLogger().error("Failed to get peer with id: {} @ {}", id, nodeId, ar.cause());
					}
				}).recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(Id id) {
		getLogger().debug("Getting peers with id: {}", id);
		return SqlTemplate.forQuery(getClient(), getDialect().selectPeersById())
				.execute(Map.of("id", id.bytes()))
				.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
				.andThen(ar -> {
					if (ar.succeeded()) {
						if (!ar.result().isEmpty())
							getLogger().debug("Got peers with id: {}", id);
						else
							//noinspection LoggingSimilarMessage
							getLogger().debug("No peers found with id: {}", id);
					} else {
						getLogger().error("Failed to get peers with id: {}", id, ar.cause());
					}
				}).recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<PeerInfo>> getPeers() {
		return query(getDialect().selectAllPeers())
				.execute()
				.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(int offset, int limit) {
		return SqlTemplate.forQuery(getClient(), getDialect().selectAllPeersPaginated())
				.execute(Map.of("limit", limit, "offset", offset))
				.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(boolean persistent, long announcedBefore) {
		return SqlTemplate.forQuery(getClient(), getDialect().selectPeersByPersistentAndAnnouncedBefore())
				.execute(Map.of("persistent", persistent, "updatedBefore", announcedBefore))
				.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(boolean persistent, long announcedBefore, int offset, int limit) {
		return SqlTemplate.forQuery(getClient(), getDialect().selectPeersByPersistentAndAnnouncedBeforePaginated())
				.execute(Map.of(
						"persistent", persistent,
						"updatedBefore", announcedBefore,
						"limit", limit,
						"offset", offset))
				.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
				.recover(cause ->
						Future.failedFuture(new DataStorageException("Database operation failed", cause))
				);
	}

	@Override
	public Future<Long> updatePeerAnnouncedTime(Id id, Id nodeId) {
		getLogger().debug("Updating peer announced time with id: {} @ {}", id, nodeId);
		long now = System.currentTimeMillis();
		return withTransaction(c ->
				SqlTemplate.forUpdate(c, getDialect().updatePeerAnnouncedByIdAndNodeId())
						.execute(Map.of("id", id.bytes(), "nodeId", nodeId.bytes(), "updated", now))
						.map(r -> r.rowCount() > 0 ? now : 0L)
		).andThen(ar -> {
			if (ar.succeeded()) {
				if (ar.result() != 0)
					getLogger().debug("Updated peer announced time with id: {} @ {}", id, nodeId);
				else
					getLogger().debug("No peer found with id: {} @ {}", id, nodeId);
			} else {
				getLogger().error("Failed to update peer announced time with id: {} @ {}", id, nodeId, ar.cause());
			}
		}).recover(cause ->
				Future.failedFuture(new DataStorageException("Database operation failed", cause))
		);
	}

	@Override
	public Future<Boolean> removePeer(Id id, Id nodeId) {
		getLogger().debug("Removing peer with id: {} @ {}", id, nodeId);
		return withTransaction(c ->
				SqlTemplate.forUpdate(c, getDialect().deletePeerByIdAndNodeId())
						.execute(Map.of("id", id.bytes(), "nodeId", nodeId.bytes()))
						.map(this::hasEffectedRows)
		).andThen(ar -> {
			if (ar.succeeded()) {
				if (ar.result())
					getLogger().debug("Removed peer with id: {} @ {}", id, nodeId);
				else
					//noinspection LoggingSimilarMessage
					getLogger().debug("No peer found with id: {} @ {}", id, nodeId);
			} else {
				getLogger().error("Failed to remove peer with id: {} @ {}", id, nodeId, ar.cause());
			}
		}).recover(cause ->
				Future.failedFuture(new DataStorageException("Database operation failed", cause))
		);
	}

	@Override
	public Future<Boolean> removePeers(Id id) {
		getLogger().debug("Removing peers with id: {}", id);
		return withTransaction(c ->
				SqlTemplate.forUpdate(c, getDialect().deletePeersById())
						.execute(Map.of("id", id.bytes()))
						.map(this::hasEffectedRows)
		).andThen(ar -> {
			if (ar.succeeded()) {
				if (ar.result())
					getLogger().debug("Removed peers with id: {}", id);
				else
					getLogger().debug("No peers found with id: {}", id);
			} else {
				getLogger().error("Failed to remove peers with id: {}", id, ar.cause());
			}
		}).recover(cause ->
				Future.failedFuture(new DataStorageException("Database operation failed", cause))
		);
	}

	protected static Map<String, Object> valueToMap(Value value, boolean persistent) {
		Map<String, Object> map = new HashMap<>();
		map.put("id", value.getId().bytes());
		map.put("publicKey", value.getPublicKey() != null ? value.getPublicKey().bytes() : null);
		map.put("privateKey", value.getPrivateKey());
		map.put("recipient", value.getRecipient() != null ? value.getRecipient().bytes() : null);
		map.put("nonce", value.getNonce());
		map.put("signature", value.getSignature());
		map.put("sequenceNumber", value.getSequenceNumber());
		map.put("data", value.getData());
		map.put("persistent", persistent);
		long now = System.currentTimeMillis();
		map.put("created", now);
		map.put("updated", now);
		return map;
	}

	protected static Value rowToValue(Row row) {
		Id publicKey = getId(row, "public_key");
		Buffer buffer = row.getBuffer("private_key");
		byte[] privateKey = buffer == null ? null : buffer.getBytes();
		Id recipient = getId(row, "recipient");
		buffer = row.getBuffer("nonce");
		byte[] nonce = buffer == null ? null : buffer.getBytes();
		buffer = row.getBuffer("signature");
		byte[] signature = buffer == null ? null : buffer.getBytes();
		int sequenceNumber = row.getInteger("sequence_number"); // NOT NULL
		buffer = row.getBuffer("data");
		byte[] data = buffer == null ? null : buffer.getBytes();

		return Value.of(publicKey, privateKey, recipient, nonce, sequenceNumber, signature, data);
	}

	protected static Map<String, Object> peerToMap(PeerInfo peerInfo, boolean persistent) {
		Map<String, Object> map = new HashMap<>();
		map.put("id", peerInfo.getId().bytes());
		map.put("nodeId", peerInfo.getNodeId().bytes());
		map.put("privateKey", peerInfo.getPrivateKey());
		map.put("origin", peerInfo.getOrigin() != null ? peerInfo.getOrigin().bytes() : null);
		map.put("port", peerInfo.getPort());
		map.put("alternativeUri", peerInfo.getAlternativeURI());
		map.put("signature", peerInfo.getSignature());
		map.put("persistent", persistent);
		long now = System.currentTimeMillis();
		map.put("created", now);
		map.put("updated", now);
		return map;
	}

	protected static PeerInfo rowToPeer(Row row) {
		Id id = getId(row, "id");
		Id nodeId = getId(row, "node_id");
		Buffer buffer = row.getBuffer("private_key");
		byte[] privateKey = buffer == null ? null : buffer.getBytes();
		Id origin = getId(row, "origin");
		int port = row.getInteger("port");
		String alternativeURI = row.getString("alternative_uri");
		buffer = row.getBuffer("signature");
		byte[] signature = buffer == null ? null : buffer.getBytes();

		return PeerInfo.of(id, privateKey, nodeId, origin, port, alternativeURI, signature);
	}

	private static Id getId(Row row, String column) {
		Buffer buf = row.getBuffer(column);
		return buf == null ? null : Id.of(buf.getBytes());
	}

	@Override
	public Future<Void> close() {
		return getClient().close();
	}
}