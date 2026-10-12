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

package io.bosonnetwork.identifier;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.bosonnetwork.AnnounceResult;
import io.bosonnetwork.ConnectionStatusListener;
import io.bosonnetwork.CryptoContext;
import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.LookupOption;
import io.bosonnetwork.Node;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoIdentity;

/**
 * A stand-in node for registry tests that keeps apart what it holds itself ({@link #kept}, as a web
 * gateway or a super node keeps a publication persistently) and what the rest of the network has
 * ({@link #network}, where another publisher of the same owner may have stored a newer version).
 */
class InMemoryNode implements Node {
	private final Identity identity = new CryptoIdentity();
	final Map<Id, Value> kept = new ConcurrentHashMap<>();
	final Map<Id, Value> network = new ConcurrentHashMap<>();
	/** When set, lookups in the network fail, as with no connection. */
	volatile boolean networkDown;
	/** When set, lookups in the network answer only once it completes. */
	volatile CompletableFuture<Void> gate;
	/** The lookups made to resolve a card (publishing looks up with no lookup option). */
	final AtomicInteger resolutions = new AtomicInteger();

	@Override
	public CompletableFuture<Optional<Value>> findValue(Id id, int expectedSequenceNumber, LookupOption option) {
		if (option != null)
			resolutions.incrementAndGet();
		if (networkDown)
			return CompletableFuture.failedFuture(new IllegalStateException("No network"));
		CompletableFuture<Void> g = gate;
		if (g != null)
			return g.thenApply(v -> Optional.ofNullable(network.get(id)));
		return CompletableFuture.completedFuture(Optional.ofNullable(network.get(id)));
	}

	@Override
	public CompletableFuture<AnnounceResult> storeValue(Value value, int expectedSequenceNumber, boolean persistent) {
		Value current = network.get(value.getId());
		if (current != null && current.getSequenceNumber() > value.getSequenceNumber())
			return CompletableFuture.failedFuture(new IllegalStateException("Sequence number less than current"));
		network.put(value.getId(), value);
		if (persistent)
			kept.put(value.getId(), value);
		return CompletableFuture.completedFuture(AnnounceResult.of(List.of(
				new AnnounceResult.Target(Id.random(), AnnounceResult.Outcome.ACKNOWLEDGED, null))));
	}

	@Override
	public CompletableFuture<Optional<Value>> getValue(Id id) {
		return CompletableFuture.completedFuture(Optional.ofNullable(kept.get(id)));
	}

	@Override
	public CompletableFuture<Boolean> removeValue(Id valueId) {
		return CompletableFuture.completedFuture(kept.remove(valueId) != null);
	}

	@Override
	public Id getId() {
		return identity.getId();
	}

	@Override
	public Optional<NodeInfo> getNodeInfo() {
		return Optional.empty();
	}

	@Override
	public void setDefaultLookupOption(LookupOption option) {
	}

	@Override
	public LookupOption getDefaultLookupOption() {
		return LookupOption.CONSERVATIVE;
	}

	@Override
	public void addConnectionStatusListener(ConnectionStatusListener listener) {
	}

	@Override
	public void removeConnectionStatusListener(ConnectionStatusListener listener) {
	}

	@Override
	public CompletableFuture<Void> bootstrap(Collection<NodeInfo> bootstrapNodes) {
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Void> start() {
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public CompletableFuture<Void> stop() {
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public boolean isRunning() {
		return true;
	}

	@Override
	public byte[] sign(byte[] data) {
		return identity.sign(data);
	}

	@Override
	public boolean verify(byte[] data, byte[] signature) {
		return identity.verify(data, signature);
	}

	@Override
	public byte[] encrypt(Id recipient, byte[] data) {
		throw new UnsupportedOperationException();
	}

	@Override
	public byte[] encrypt(Id receiver, byte[] nonce, byte[] data) {
		throw new UnsupportedOperationException();
	}

	@Override
	public byte[] decrypt(Id sender, byte[] data) {
		throw new UnsupportedOperationException();
	}

	@Override
	public byte[] decrypt(Id sender, byte[] nonce, byte[] data) {
		throw new UnsupportedOperationException();
	}

	@Override
	public CryptoContext createCryptoContext(Id id) {
		throw new UnsupportedOperationException();
	}

	@Override
	public <T> Optional<T> unwrap(Class<T> clazz) {
		return Optional.empty();
	}

	@Override
	public CompletableFuture<Optional<NodeInfo>> findNode(Id id, LookupOption option) {
		return CompletableFuture.completedFuture(Optional.empty());
	}

	@Override
	public CompletableFuture<List<PeerInfo>> findPeer(Id id, int expectedSequenceNumber, int expectedCount, LookupOption option) {
		return CompletableFuture.completedFuture(List.of());
	}

	@Override
	public CompletableFuture<AnnounceResult> announcePeer(PeerInfo peer, int expectedSequenceNumber, boolean persistent) {
		return CompletableFuture.failedFuture(new UnsupportedOperationException());
	}

	@Override
	public CompletableFuture<List<PeerInfo>> getPeers(Id peerId) {
		return CompletableFuture.completedFuture(List.of());
	}

	@Override
	public CompletableFuture<Boolean> removePeers(Id peerId) {
		return CompletableFuture.completedFuture(false);
	}

	@Override
	public CompletableFuture<Optional<PeerInfo>> getPeer(Id peerId, long fingerprint) {
		return CompletableFuture.completedFuture(Optional.empty());
	}

	@Override
	public CompletableFuture<Boolean> removePeer(Id peerId, long fingerprint) {
		return CompletableFuture.completedFuture(false);
	}

	@Override
	public String getVersion() {
		return "test";
	}
}
