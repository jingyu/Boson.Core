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
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;

/**
 * A {@link DataStorage} that reports every successful write to its listeners.
 * <p>
 * Storage is written from several places - the remote STORE_VALUE and ANNOUNCE_PEER handlers of each
 * address family, the local store and announce paths, and the caching of lookup results - and a
 * subscription must hear about all of them. Wrapping the storage covers every one without touching any,
 * and keeps subscriptions out of the code that writes.
 * </p>
 * <p>
 * What is reported is the <em>effective</em> record the storage returned, which is not always the one
 * passed in: a write that loses a sequence race, or that meets a record this node owns, leaves the stored
 * record in place. Whether that amounts to a change is for the listener to decide against what it has
 * already seen; keep-alive re-announces are reported too, and are expected to be ignored.
 * </p>
 * <p>
 * Listeners run on whatever thread completed the write, and must hand the work to their own context
 * rather than do it there. They must not throw; one that does is logged and does not affect the write or
 * the other listeners.
 * </p>
 */
public class ObservableStorage implements DataStorage {
	/**
	 * Receives the records a write left in storage.
	 */
	public interface Listener {
		/**
		 * Called after a value was written.
		 *
		 * @param value the value now stored under its id.
		 */
		default void valueStored(Value value) { }

		/**
		 * Called after a peer record was written.
		 *
		 * @param peer the record now stored under its id and fingerprint.
		 */
		default void peerStored(PeerInfo peer) { }
	}

	private final DataStorage delegate;
	private final List<Listener> listeners;

	private static final Logger log = LoggerFactory.getLogger(ObservableStorage.class);

	/**
	 * Wraps a storage.
	 *
	 * @param delegate the storage that does the work.
	 */
	public ObservableStorage(DataStorage delegate) {
		this.delegate = Objects.requireNonNull(delegate, "delegate");
		this.listeners = new CopyOnWriteArrayList<>();
	}

	/**
	 * Adds a listener.
	 *
	 * @param listener the listener.
	 */
	public void addListener(Listener listener) {
		listeners.add(Objects.requireNonNull(listener, "listener"));
	}

	/**
	 * Removes a listener.
	 *
	 * @param listener the listener.
	 */
	public void removeListener(Listener listener) {
		listeners.remove(listener);
	}

	private Value valueStored(Value value) {
		if (value == null)
			return null;

		for (Listener listener : listeners) {
			try {
				listener.valueStored(value);
			} catch (Exception e) {
				log.error("Storage listener failed on value {}", value.getId(), e);
			}
		}

		return value;
	}

	private PeerInfo peerStored(PeerInfo peer) {
		if (peer == null)
			return null;

		for (Listener listener : listeners) {
			try {
				listener.peerStored(peer);
			} catch (Exception e) {
				log.error("Storage listener failed on peer {}", peer.getId(), e);
			}
		}

		return peer;
	}

	private List<PeerInfo> peersStored(List<PeerInfo> peers) {
		if (peers != null)
			peers.forEach(this::peerStored);

		return peers;
	}

	@Override
	public Future<Integer> initialize(Vertx vertx, long valueExpiration, long peerInfoExpiration) {
		return delegate.initialize(vertx, valueExpiration, peerInfoExpiration);
	}

	@Override
	public Future<Void> close() {
		return delegate.close();
	}

	@Override
	public int getSchemaVersion() {
		return delegate.getSchemaVersion();
	}

	@Override
	public Future<Void> purge() {
		return delegate.purge();
	}

	@Override
	public Future<Value> putValue(Value value) {
		return delegate.putValue(value).map(this::valueStored);
	}

	@Override
	public Future<Value> putValue(Value value, boolean persistent) {
		return delegate.putValue(value, persistent).map(this::valueStored);
	}

	@Override
	public Future<Value> putValue(Value value, int expectedSequenceNumber, boolean persistent, boolean failIfNotOwner) {
		return delegate.putValue(value, expectedSequenceNumber, persistent, failIfNotOwner).map(this::valueStored);
	}

	@Override
	public Future<Value> getValue(Id id) {
		return delegate.getValue(id);
	}

	@Override
	public Future<List<Value>> getValues() {
		return delegate.getValues();
	}

	@Override
	public Future<List<Value>> getValues(int offset, int limit) {
		return delegate.getValues(offset, limit);
	}

	@Override
	public Future<List<Value>> getValues(boolean persistent, long announcedBefore) {
		return delegate.getValues(persistent, announcedBefore);
	}

	@Override
	public Future<List<Value>> getValues(boolean persistent, long announcedBefore, int offset, int limit) {
		return delegate.getValues(persistent, announcedBefore, offset, limit);
	}

	@Override
	public Future<Long> updateValueAnnouncedTime(Id id) {
		return delegate.updateValueAnnouncedTime(id);
	}

	@Override
	public Future<Boolean> removeValue(Id id) {
		return delegate.removeValue(id);
	}

	@Override
	public Future<PeerInfo> putPeer(PeerInfo peerInfo) {
		return delegate.putPeer(peerInfo).map(this::peerStored);
	}

	@Override
	public Future<PeerInfo> putPeer(PeerInfo peerInfo, boolean persistent) {
		return delegate.putPeer(peerInfo, persistent).map(this::peerStored);
	}

	@Override
	public Future<PeerInfo> putPeer(PeerInfo peerInfo, int expectedSequenceNumber, boolean persistent, boolean failIfNotOwner) {
		return delegate.putPeer(peerInfo, expectedSequenceNumber, persistent, failIfNotOwner).map(this::peerStored);
	}

	@Override
	public Future<List<PeerInfo>> putPeers(List<PeerInfo> peerInfos) {
		return delegate.putPeers(peerInfos).map(this::peersStored);
	}

	@Override
	public Future<PeerInfo> getPeer(Id id, long fingerprint) {
		return delegate.getPeer(id, fingerprint);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(Id id) {
		return delegate.getPeers(id);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(Id id, int expectedSequenceNumber, int limit) {
		return delegate.getPeers(id, expectedSequenceNumber, limit);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(Id id, Id nodeId) {
		return delegate.getPeers(id, nodeId);
	}

	@Override
	public Future<List<PeerInfo>> getPeers() {
		return delegate.getPeers();
	}

	@Override
	public Future<List<PeerInfo>> getPeers(int offset, int limit) {
		return delegate.getPeers(offset, limit);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(boolean persistent, long announcedBefore) {
		return delegate.getPeers(persistent, announcedBefore);
	}

	@Override
	public Future<List<PeerInfo>> getPeers(boolean persistent, long announcedBefore, int offset, int limit) {
		return delegate.getPeers(persistent, announcedBefore, offset, limit);
	}

	@Override
	public Future<Long> updatePeerAnnouncedTime(Id id, long fingerprint) {
		return delegate.updatePeerAnnouncedTime(id, fingerprint);
	}

	@Override
	public Future<Boolean> removePeer(Id id, long fingerprint) {
		return delegate.removePeer(id, fingerprint);
	}

	@Override
	public Future<Boolean> removePeers(Id id) {
		return delegate.removePeers(id);
	}
}
