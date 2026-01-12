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

public abstract class DatabaseStorage implements DataStorage, VertxDatabase {
	protected long valueExpiration;
	protected long peerInfoExpiration;

	protected int schemaVersion;

	protected abstract Logger getLogger();

	protected abstract void init(Vertx vertx);

	protected abstract Path getMigrationPath();

	protected String getSchema() {
		return null;
	}

	protected abstract SqlDialect getDialect();

	@Override
	public Future<Integer> initialize(Vertx vertx, long valueExpiration, long peerInfoExpiration) {
		init(vertx);

		this.valueExpiration = valueExpiration;
		this.peerInfoExpiration = peerInfoExpiration;

		VersionedSchema schema = VersionedSchema.init(vertx, getClient(), getSchema(), getMigrationPath());
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
						Future.failedFuture(new DataStorageException("Database initialize failed", cause))
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
		).recover(cause ->
				Future.failedFuture(new DataStorageException("purge database failed", cause))
		).mapEmpty();
	}

	@Override
	public Future<Value> putValue(Value value) {
		return putValue(value, false);
	}

	@Override
	public Future<Value> putValue(Value value, boolean persistent) {
		getLogger().debug("Putting value with id: {}, persistent: {}", value.getId(), persistent);
		return withTransaction(c ->
					SqlTemplate.forUpdate(c, getDialect().upsertValue())
							.execute(valueToMap(value, persistent))
							.map(v -> value)
		).recover(cause ->
				Future.failedFuture(new DataStorageException("putValue failed", cause))
		);
	}

	@Override
	public Future<Value> getValue(Id id) {
		getLogger().debug("Getting value with id: {}", id);
		return withConnection(c ->
				SqlTemplate.forQuery(c, getDialect().selectValue())
						.execute(Map.of("id", id.bytes()))
						.map(rows -> findUnique(rows, DatabaseStorage::rowToValue))
		).recover(cause ->
				Future.failedFuture(new DataStorageException("getValue failed", cause))
		);
	}

	@Override
	public Future<List<Value>> getValues() {
		return withConnection(c ->
				c.query(getDialect().selectAllValues())
						.execute()
						.map(rows -> findMany(rows, DatabaseStorage::rowToValue))
		).recover(cause ->
				Future.failedFuture(new DataStorageException("getValues/all failed", cause))
		);
	}

	@Override
	public Future<List<Value>> getValues(int offset, int limit) {
		return withConnection(c ->
				SqlTemplate.forQuery(c, getDialect().selectAllValuesPaginated())
						.execute(Map.of("limit", limit, "offset", offset))
						.map(rows -> findMany(rows, DatabaseStorage::rowToValue))
		).recover(cause ->
				Future.failedFuture(new DataStorageException("getValues/all/paginated failed", cause))
		);
	}

	@Override
	public Future<List<Value>> getValues(boolean persistent, long announcedBefore) {
		return withConnection(c ->
				SqlTemplate.forQuery(c, getDialect().selectValuesByPersistentAndAnnouncedBefore())
						.execute(Map.of("persistent", persistent, "updatedBefore", announcedBefore))
						.map(rows -> findMany(rows, DatabaseStorage::rowToValue))
		).recover(cause ->
				Future.failedFuture(new DataStorageException("getValues/announcedBefore failed", cause))
		);
	}

	@Override
	public Future<List<Value>> getValues(boolean persistent, long announcedBefore, int offset, int limit) {
		return withConnection(c ->
				SqlTemplate.forQuery(c, getDialect().selectValuesByPersistentAndAnnouncedBeforePaginated())
						.execute(Map.of(
								"persistent", persistent,
								"updatedBefore", announcedBefore,
								"limit", limit,
								"offset", offset))
						.map(rows -> findMany(rows, DatabaseStorage::rowToValue))
		).recover(cause ->
				Future.failedFuture(new DataStorageException("getValues/announcedBefore/paginated failed", cause))
		);
	}

	@Override
	public Future<Long> updateValueAnnouncedTime(Id id) {
		getLogger().debug("Updating value announced time with id: {}", id);
		long now = System.currentTimeMillis();
		return withTransaction(c ->
				SqlTemplate.forUpdate(c, getDialect().updateValueAnnounced())
						.execute(Map.of("id", id.bytes(), "updated", now))
						.map(r -> r.rowCount() > 0 ? now : 0L)
		).recover(cause ->
				Future.failedFuture(new DataStorageException("updateValueAnnouncedTime failed", cause))
		);
	}

	@Override
	public Future<Boolean> removeValue(Id id) {
		getLogger().debug("Removing value with id: {}", id);
		return withTransaction(c ->
				SqlTemplate.forUpdate(c, getDialect().deleteValue())
						.execute(Map.of("id", id.bytes()))
						.map(this::hasAffectedRows)
		).recover(cause ->
				Future.failedFuture(new DataStorageException("removeValue failed", cause))
		);
	}

	@Override
	public Future<PeerInfo> putPeer(PeerInfo peerInfo) {
		return putPeer(peerInfo, false);
	}

	@Override
	public Future<PeerInfo> putPeer(PeerInfo peerInfo, boolean persistent) {
		getLogger().debug("Putting peer with id: {} @ {}, persistent: {}", peerInfo.getId(), peerInfo.getNodeId(), persistent);
		return withTransaction(c ->
					SqlTemplate.forUpdate(c, getDialect().upsertPeer())
							.execute(peerToMap(peerInfo, persistent))
							.map(v -> peerInfo)
		).recover(cause ->
				Future.failedFuture(new DataStorageException("putPeer failed", cause))
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
		).recover(cause ->
				Future.failedFuture(new DataStorageException("putPeers failed", cause))
		);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(Id id, Id nodeId) {
		getLogger().debug("Getting peer with id: {} @ {}", id, nodeId);
		return withConnection(c ->
				SqlTemplate.forQuery(c, getDialect().selectPeersByIdAndNodeId())
						.execute(Map.of("id", id.bytes(), "nodeId", nodeId.bytes()))
						.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
		).recover(cause ->
				Future.failedFuture(new DataStorageException("getPeers/id&nodeId failed", cause))
		);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(Id id) {
		getLogger().debug("Getting peers with id: {}", id);
		return withConnection(c ->
				SqlTemplate.forQuery(c, getDialect().selectPeersById())
						.execute(Map.of("id", id.bytes()))
						.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
		).recover(cause ->
				Future.failedFuture(new DataStorageException("getPeers/id failed", cause))
		);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(Id id, int expectedSequenceNumber, int limit) {
		getLogger().debug("Getting peers with id: {}, expectedSequenceNumber: {}, limit{}", id, expectedSequenceNumber, limit);
		return withConnection(c ->
				SqlTemplate.forQuery(c, getDialect().selectPeersByIdAndSequenceNumberWithLimit())
						.execute(Map.of("id", id.bytes(),
								"expectedSequenceNumber", expectedSequenceNumber,
								"limit", limit))
						.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
		).recover(cause ->
				Future.failedFuture(new DataStorageException("getPeers/id&expectedSequenceNumber failed", cause))
		);
	}

	@Override
	public Future<List<PeerInfo>> getPeers() {
		return withConnection(c ->
				c.query(getDialect().selectAllPeers())
						.execute()
						.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
		).recover(cause ->
				Future.failedFuture(new DataStorageException("getPeers/all failed", cause))
		);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(int offset, int limit) {
		return withConnection(c ->
				SqlTemplate.forQuery(c, getDialect().selectAllPeersPaginated())
						.execute(Map.of("limit", limit, "offset", offset))
						.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
		).recover(cause ->
				Future.failedFuture(new DataStorageException("getPeers/all/paginated failed", cause))
		);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(boolean persistent, long announcedBefore) {
		return withConnection(c ->
				SqlTemplate.forQuery(c, getDialect().selectPeersByPersistentAndAnnouncedBefore())
						.execute(Map.of("persistent", persistent, "updatedBefore", announcedBefore))
						.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
		).recover(cause ->
				Future.failedFuture(new DataStorageException("getPeers/announcedBefore failed", cause))
		);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(boolean persistent, long announcedBefore, int offset, int limit) {
		return withConnection(c ->
				SqlTemplate.forQuery(c, getDialect().selectPeersByPersistentAndAnnouncedBeforePaginated())
						.execute(Map.of(
								"persistent", persistent,
								"updatedBefore", announcedBefore,
								"limit", limit,
								"offset", offset))
						.map(rows -> findMany(rows, DatabaseStorage::rowToPeer))
		).recover(cause ->
				Future.failedFuture(new DataStorageException("getPeers/announcedBefore/paginated failed", cause))
		);
	}

	@Override
	public Future<Long> updatePeerAnnouncedTime(Id id, long fingerprint) {
		getLogger().debug("Updating peer announced time with id: {}:{}", id, fingerprint);
		long now = System.currentTimeMillis();
		return withTransaction(c ->
				SqlTemplate.forUpdate(c, getDialect().updatePeerAnnounced())
						.execute(Map.of("id", id.bytes(), "fingerprint", fingerprint, "updated", now))
						.map(r -> r.rowCount() > 0 ? now : 0L)
		).recover(cause ->
				Future.failedFuture(new DataStorageException("updatePeerAnnouncedTime failed", cause))
		);
	}

	@Override
	public Future<PeerInfo> getPeer(Id id, long fingerprint) {
		return withConnection(c ->
				SqlTemplate.forQuery(c, getDialect().selectPeer())
						.execute(Map.of("id", id.bytes(), "fingerprint", fingerprint))
						.map(rows -> findUnique(rows, DatabaseStorage::rowToPeer))
		).recover(cause ->
				Future.failedFuture(new DataStorageException("getPeer failed", cause))
		);
	}

	@Override
	public Future<Boolean> removePeer(Id id, long fingerprint) {
		getLogger().debug("Removing peer with id: {}:{}", id, fingerprint);
		return withTransaction(c ->
				SqlTemplate.forUpdate(c, getDialect().deletePeer())
						.execute(Map.of("id", id.bytes(), "fingerprint", fingerprint))
						.map(this::hasAffectedRows)
		).recover(cause ->
				Future.failedFuture(new DataStorageException("removePeer failed", cause))
		);
	}

	@Override
	public Future<Boolean> removePeers(Id id) {
		getLogger().debug("Removing peers with id: {}", id);
		return withTransaction(c ->
				SqlTemplate.forUpdate(c, getDialect().deletePeersById())
						.execute(Map.of("id", id.bytes()))
						.map(this::hasAffectedRows)
		).recover(cause ->
				Future.failedFuture(new DataStorageException("removePeers/id failed", cause))
		);
	}

	protected static Map<String, Object> valueToMap(Value value, boolean persistent) {
		Map<String, Object> map = new HashMap<>();
		map.put("id", value.getId().bytes());
		map.put("publicKey", value.getPublicKey() != null ? value.getPublicKey().bytes() : null);
		map.put("privateKey", value.getPrivateKey());
		map.put("recipient", value.getRecipient() != null ? value.getRecipient().bytes() : null);
		map.put("nonce", value.getNonce());
		map.put("sequenceNumber", value.getSequenceNumber());
		map.put("signature", value.getSignature());
		map.put("data", value.getData());
		map.put("persistent", persistent);
		long now = System.currentTimeMillis();
		map.put("created", now);
		map.put("updated", now);
		return map;
	}

	protected static Value rowToValue(Row row) {
		Id publicKey = getId(row, "public_key");
		byte[] privateKey = getBytes(row, "private_key");
		Id recipient = getId(row, "recipient");
		byte[] nonce = getBytes(row, "nonce");
		int sequenceNumber = row.getInteger("sequence_number"); // NOT NULL
		byte[] signature = getBytes(row, "signature");
		byte[] data = getBytes(row, "data");

		return Value.of(publicKey, privateKey, recipient, nonce, sequenceNumber, signature, data);
	}

	protected static Map<String, Object> peerToMap(PeerInfo peerInfo, boolean persistent) {
		Map<String, Object> map = new HashMap<>();
		map.put("id", peerInfo.getId().bytes());
		map.put("fingerprint", peerInfo.getFingerprint());
		map.put("privateKey", peerInfo.getPrivateKey());
		map.put("nonce", peerInfo.getNonce());
		map.put("sequenceNumber", peerInfo.getSequenceNumber());
		if (peerInfo.isAuthenticated()) {
			map.put("nodeId", peerInfo.getNodeId().bytes());
			map.put("nodeSignature", peerInfo.getNodeSignature());
		} else {
			map.put("nodeId", null);
			map.put("nodeSignature", null);
		}
		map.put("signature", peerInfo.getSignature());
		map.put("endpoint", peerInfo.getEndpoint());
		map.put("extra", peerInfo.hasExtra() ? peerInfo.getExtraData() : null);
		map.put("persistent", persistent);
		long now = System.currentTimeMillis();
		map.put("created", now);
		map.put("updated", now);
		return map;
	}

	protected static PeerInfo rowToPeer(Row row) {
		Id id = getId(row, "id");
		long fingerprint = row.getLong("fingerprint");
		byte[] privateKey = getBytes(row, "private_key");
		byte[] nonce = getBytes(row, "nonce");
		int sequenceNumber = row.getInteger("sequence_number");
		Id nodeId = getId(row, "node_id");
		byte[] nodeSignature = getBytes(row, "node_signature");
		byte[] signature = getBytes(row, "signature");
		String endpoint = row.getString("endpoint");
		byte[] extra = getBytes(row, "extra");

		return PeerInfo.of(id, privateKey, nonce, sequenceNumber, nodeId, nodeSignature, signature, fingerprint, endpoint, extra);
	}

	private static Id getId(Row row, String column) {
		Buffer buf = row.getBuffer(column);
		return buf == null ? null : Id.of(buf.getBytes());
	}

	private static byte[] getBytes(Row row, String column) {
		Buffer buf = row.getBuffer(column);
		return buf == null ? null : buf.getBytes();
	}

	@Override
	public Future<Void> close() {
		return getClient().close();
	}
}