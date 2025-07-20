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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.jetbrains.annotations.NotNull;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.kademlia.exceptions.ImmutableSubstitutionFail;
import io.bosonnetwork.kademlia.exceptions.KadException;
import io.bosonnetwork.kademlia.exceptions.SequenceNotExpected;
import io.bosonnetwork.kademlia.exceptions.SequenceNotMonotonic;

public class InMemoryStorage implements DataStorage {
	private static final int SCHEMA_VERSION = 5;

	private static final int DEFAULT_MAP_CAPACITY = 32;

	private final Map<Id, StorageEntry<Value>> values;
	private final Map<CompositeId, StorageEntry<PeerInfo>> peers;

	private long valueExpiration;
	private long peerInfoExpiration;
	private boolean initialized;

	protected InMemoryStorage() {
		values = new ConcurrentHashMap<>(DEFAULT_MAP_CAPACITY);
		peers = new ConcurrentHashMap<>(DEFAULT_MAP_CAPACITY);
	}

	@Override
	public Future<Integer> initialize(Vertx vertx, String connectionUri, long valueExpiration, long peerInfoExpiration) {
		if (initialized)
			return Future.failedFuture(new DataStorageException("Storage already initialized"));

		this.valueExpiration = valueExpiration;
		this.peerInfoExpiration = peerInfoExpiration;
		initialized = true;
		return Future.succeededFuture(SCHEMA_VERSION);
	}

	@Override
	public Future<Void> close() {
		values.clear();
		peers.clear();
		return Future.succeededFuture();
	}

	@Override
	public int getSchemaVersion() {
		return SCHEMA_VERSION;
	}

	@Override
	public Future<Void> purge() {
		values.entrySet().removeIf(entry -> entry.getValue().isExpired(valueExpiration));
		peers.entrySet().removeIf(entry -> entry.getValue().isExpired(valueExpiration));

		return Future.succeededFuture();
	}

	@Override
	public Future<Value> putValue(Value value) {
		return putValue(value, false, 0);
	}

	@Override
	public Future<Value> putValue(Value value, boolean persistent) {
		return putValue(value, persistent, 0);
	}

	private void updateValueEntry(StorageEntry<Value> entry, Value value, boolean persistent,
								  int expectedSequenceNumber) throws KadException {
		Value existing = entry.getObject();

		// Immutable value handling
		if (existing.isMutable() != value.isMutable())
			throw new ImmutableSubstitutionFail("Cannot replace mismatched mutable/immutable value");

		if (value.getSequenceNumber() < existing.getSequenceNumber())
			throw new SequenceNotMonotonic("Sequence number less than current");

		if (expectedSequenceNumber > 0 && existing.getSequenceNumber() != expectedSequenceNumber)
			throw new SequenceNotExpected("Sequence number not expected");

		if (existing.hasPrivateKey() && !value.hasPrivateKey()) {
			// Skip update if the existing value is owned by this node and the new value is not.
			// Should not throw NotOwnerException, just silently ignores to avoid disrupting valid operations.
			return;
		}

		entry.update(value, persistent);
	}

	@Override
	public Future<Value> putValue(Value value, boolean persistent, int expectedSequenceNumber) {
		try {
			StorageEntry<Value> updated = values.compute(value.getId(), (id, entry) -> {
				if (entry == null)
					return new StorageEntry<>(value, persistent);

				try {
					updateValueEntry(entry, value, persistent, expectedSequenceNumber);
					return entry;
				} catch (KadException e) {
					throw new UncheckedStorageException(e);
				}
			});

			return Future.succeededFuture(updated.getObject());
		} catch (UncheckedStorageException e) {
			return Future.failedFuture(new DataStorageException("InMemoryStorage error", e.getCause()));
		}
	}

	@Override
	public Future<Value> getValue(Id id) {
		StorageEntry<Value> entry = values.get(id);
		// Returns succeeded Future with null if entry is missing or expired
		return entry == null || entry.isExpired(valueExpiration) ?
				Future.succeededFuture() : Future.succeededFuture(entry.getObject());
	}

	private List<Value> getValues(Predicate<StorageEntry<Value>> predicate, int offset, int limit) {
		Stream<StorageEntry<Value>> stream = values.values().stream()
				.filter(predicate)
				.sorted(StorageEntry::compareTo);

		if (offset > 0)
			stream = stream.skip(offset);

		if (limit > 0)
			stream = stream.limit(limit);

		return stream.map(StorageEntry::getObject).toList();
	}

	@Override
	public Future<List<Value>> getValues() {
		List<Value> result = getValues(entry -> !entry.isExpired(valueExpiration), -1, -1);
		return Future.succeededFuture(result);
	}

	@Override
	public Future<List<Value>> getValues(int offset, int limit) {
		List<Value> result = getValues(entry -> !entry.isExpired(valueExpiration), offset, limit);
		return Future.succeededFuture(result);
	}

	@Override
	public Future<List<Value>> getValues(boolean persistent, long announcedBefore) {
		List<Value> result = getValues(entry -> !entry.isExpired(valueExpiration) &&
						entry.isPersistent() == persistent && entry.getAnnounced() <= announcedBefore, -1, -1);
		return Future.succeededFuture(result);
	}

	@Override
	public Future<List<Value>> getValues(boolean persistent, long announcedBefore, int offset, int limit) {
		List<Value> result = getValues(entry -> !entry.isExpired(valueExpiration) &&
						entry.isPersistent() == persistent && entry.getAnnounced() <= announcedBefore, offset, limit);
		return Future.succeededFuture(result);
	}

	@Override
	public Future<Long> updateValueAnnouncedTime(Id id) {
		return updateAnnouncementTime(values, id);
	}

	@Override
	public Future<Boolean> removeValue(Id id) {
		StorageEntry<Value> entry = values.remove(id);
		return Future.succeededFuture(entry != null);
	}

	@Override
	public Future<PeerInfo> putPeer(PeerInfo peerInfo) {
		return putPeer(peerInfo, false);
	}

	private void updatePeerEntry(StorageEntry<PeerInfo> entry, PeerInfo peerInfo, boolean persistent) throws KadException {
		PeerInfo existing = entry.getObject();

		if (existing.hasPrivateKey() && !peerInfo.hasPrivateKey()) {
			// Skip update if the existing peer info is owned by this node and the new peer info is not.
			// Should not throw NotOwnerException, just silently ignores to avoid disrupting valid operations.
			return;
		}

		entry.update(peerInfo, persistent);
	}

	private StorageEntry<PeerInfo> putPeerEntry(PeerInfo peerInfo, boolean persistent) throws UncheckedStorageException {
		return peers.compute(CompositeId.of(peerInfo), (unused, entry) -> {
			if (entry == null)
				return new StorageEntry<>(peerInfo, persistent);

			try {
				updatePeerEntry(entry, peerInfo, persistent);
				return entry;
			} catch (KadException e) {
				throw new UncheckedStorageException(e);
			}
		});
	}

	@Override
	public Future<PeerInfo> putPeer(PeerInfo peerInfo, boolean persistent) {
		try {
			StorageEntry<PeerInfo> entry = putPeerEntry(peerInfo, persistent);
			return Future.succeededFuture(entry.getObject());
		} catch (UncheckedStorageException e) {
			return Future.failedFuture(new DataStorageException("InMemoryStorage error", e.getCause()));
		}
	}

	@Override
	public Future<List<PeerInfo>> putPeers(List<PeerInfo> peerInfos) {
		try {
			peerInfos.forEach(peerInfo -> putPeerEntry(peerInfo, false));
			return Future.succeededFuture(peerInfos);
		} catch (UncheckedStorageException e) {
			return Future.failedFuture(new DataStorageException("InMemoryStorage error", e.getCause()));
		}
	}

	@Override
	public Future<PeerInfo> getPeer(Id id, Id nodeId) {
		StorageEntry<PeerInfo> entry = peers.get(CompositeId.of(id, nodeId));
		// Returns succeeded Future with null if entry is missing or expired
		return entry == null || entry.isExpired(peerInfoExpiration) ?
				Future.succeededFuture() : Future.succeededFuture(entry.getObject());
	}

	@Override
	public Future<List<PeerInfo>> getPeers(Id id) {
		List<PeerInfo> result = peers.values().stream()
				.filter(entry -> !entry.isExpired(peerInfoExpiration) && entry.getObject().getId().equals(id))
				.sorted(StorageEntry::compareTo)
				.map(StorageEntry::getObject)
				.toList();

		return Future.succeededFuture(result);
	}

	private List<PeerInfo> getPeers(Predicate<StorageEntry<PeerInfo>> predicate, int offset, int limit) {
		Stream<StorageEntry<PeerInfo>> stream = peers.values().stream()
				.filter(predicate)
				.sorted(StorageEntry::compareTo);

		if (offset > 0)
			stream = stream.skip(offset);

		if (limit > 0)
			stream = stream.limit(limit);

		return stream.map(StorageEntry::getObject).toList();
	}

	@Override
	public Future<List<PeerInfo>> getPeers() {
		List<PeerInfo> result = getPeers(entry -> !entry.isExpired(peerInfoExpiration), -1, -1);
		return Future.succeededFuture(result);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(int offset, int limit) {
		List<PeerInfo> result = getPeers(entry -> !entry.isExpired(peerInfoExpiration), offset, limit);
		return Future.succeededFuture(result);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(boolean persistent, long announcedBefore) {
		List<PeerInfo> result = getPeers(entry -> !entry.isExpired(peerInfoExpiration) &&
				entry.isPersistent() == persistent && entry.getAnnounced() <= announcedBefore, -1, -1);
		return Future.succeededFuture(result);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(boolean persistent, long announcedBefore, int offset, int limit) {
		List<PeerInfo> result = getPeers(entry -> !entry.isExpired(peerInfoExpiration) &&
				entry.isPersistent() == persistent && entry.getAnnounced() <= announcedBefore, offset, limit);
		return Future.succeededFuture(result);
	}

	@Override
	public Future<Long> updatePeerAnnouncedTime(Id id, Id nodeId) {
		return updateAnnouncementTime(peers, CompositeId.of(id, nodeId));
	}

	@Override
	public Future<Boolean> removePeer(Id id, Id nodeId) {
		StorageEntry<PeerInfo> entry = peers.remove(CompositeId.of(id, nodeId));
		return Future.succeededFuture(entry != null);
	}

	@Override
	public Future<Boolean> removePeers(Id id) {
		boolean removed = peers.entrySet().removeIf(entry -> entry.getKey().getPeerId().equals(id));
		return Future.succeededFuture(removed);
	}

	private <K, V> Future<Long> updateAnnouncementTime(Map<K, StorageEntry<V>> map, K key) {
		StorageEntry<V> entry = map.get(key);
		if (entry != null) {
			entry.setAnnounced(System.currentTimeMillis());
			return Future.succeededFuture(entry.getAnnounced());
		}
		return Future.succeededFuture(0L);
	}

	static class CompositeId {
		private final Id peerId;
		private final Id nodeId;

		private CompositeId(Id peerId, Id nodeId) {
			this.peerId = peerId;
			this.nodeId = nodeId;
		}

		public static CompositeId of(Id peerId, Id nodeId) {
			return new CompositeId(peerId, nodeId);
		}

		public static CompositeId of(PeerInfo peerInfo) {
			return new CompositeId(peerInfo.getId(), peerInfo.getNodeId());
		}

		public Id getPeerId() {
			return peerId;
		}

		public Id getNodeId() {
			return nodeId;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o)
				return true;

			if (o instanceof CompositeId that)
				return peerId.equals(that.peerId) && nodeId.equals(that.nodeId);

			return false;
		}

		@Override
		public int hashCode() {
			return Objects.hash(peerId, nodeId);
		}
	}

	static class StorageEntry<T> implements Comparable<StorageEntry<T>> {
		private T object;
		private boolean persistent;
		private long updated;
		private long announced;

		public StorageEntry(T object, boolean persistent, long updated, long announced) {
			this.object = object;
			this.persistent = persistent;
			this.updated = updated;
			this.announced = announced;
		}

		public StorageEntry(T object, boolean persistent) {
			this(object, persistent, System.currentTimeMillis(), 0);
		}

		void update(T object, boolean persistent) {
			this.object = object;
			this.persistent = persistent;
			this.updated = System.currentTimeMillis();
		}

		public T getObject() {
			return object;
		}

		public boolean isPersistent() {
			return persistent;
		}

		public long getUpdated() {
			return updated;
		}

		public long getAnnounced() {
			return announced;
		}

		void setAnnounced(long announced) {
			this.announced = announced;
		}

		public boolean isExpired(long expiration) {
			if (persistent)
				return false;

			// Expiration is based on the last announced timestamp, as per Kademlia protocol requirements
			// for tracking when values or peers were last advertised to the network.
			return System.currentTimeMillis() - (announced != 0 ? announced : updated) > expiration;
		}

		@Override
		public int compareTo(@NotNull StorageEntry<T> o) {
			if (o == this)
				return 0;

			int rc = Long.compare(announced, o.announced);
			if (rc != 0)
				return rc;

			return Long.compare(updated, o.updated);
		}
	}

	// Custom unchecked exception for wrapping checked exceptions in compute lambdas
	static class UncheckedStorageException extends RuntimeException {
		private static final long serialVersionUID = 8595433903736762221L;

		public UncheckedStorageException(Throwable cause) {
			super(cause);
		}
	}
}