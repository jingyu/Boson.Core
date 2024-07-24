/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

package io.bosonnetwork;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The public interface for Boson DHT node.
 */
public interface Node extends Identity {
	/**
	 * Gets the ID of the node.
	 *
	 * @return the ID of the node.
	 */
	@Override
	public Id getId();

	/**
	 * Gets information about the node.
	 *
	 * @return a {@code Result} containing the node information on IPv4 and IPv6.
	 */
	public Result<NodeInfo> getNodeInfo();

	/**
	 * Checks if the given ID corresponds to the local node.
	 *
	 * @param id the ID to check.
	 * @return {@code true} if the ID corresponds to the local node, {@code false} otherwise.
	 */
	public boolean isLocalId(Id id);

	/**
	 * Sets the default lookup option for the node.
	 *
	 * @param option the default lookup option.
	 */
	public void setDefaultLookupOption(LookupOption option);

	/**
	 * Adds a status listener to the node.
	 *
	 * @param listener the status listener to add.
	 */
	public void addStatusListener(NodeStatusListener listener);

	/**
	 * Removes a status listener from the node.
	 *
	 * @param listener the status listener to remove.
	 */
	public void removeStatusListener(NodeStatusListener listener);

	/**
	 * Adds a connection status listener to the node.
	 *
	 * @param listener the connection status listener to add.
	 */
	public void addConnectionStatusListener(ConnectionStatusListener listener);

	/**
	 * Removes a connection status listener from the node.
	 *
	 * @param listener the connection status listener to remove.
	 */
	public void removeConnectionStatusListener(ConnectionStatusListener listener);

	/**
	 * Gets the {@code ScheduledExecutorService} used by the node.
	 *
	 * @return the scheduled executor service.
	 */
	public ScheduledExecutorService getScheduler();

	/**
	 * Bootstraps the node from the specified node.
	 *
	 * @param node the node information to bootstrap with.
	 * @throws BosonException if an error occurs during bootstrap.
	 */
	public void bootstrap(NodeInfo node) throws BosonException;

	/**
	 * Bootstraps the node from multiple nodes.
	 *
	 * @param bootstrapNodes the collection of nodes to bootstrap with.
	 * @throws BosonException if an error occurs during bootstrap.
	 */
	public void bootstrap(Collection<NodeInfo> bootstrapNodes) throws BosonException;

	// TODO: start, stop change to async method and return a future
	/**
	 * Starts the node.
	 *
	 * @throws BosonException if an error occurs during startup.
	 */
	public void start() throws BosonException;

	/**
	 * Stops the node.
	 */
	public void stop();

	/**
	 * Gets the current status of the node.
	 *
	 * @return the status of the node.
	 */
	public NodeStatus getStatus();

	/**
	 * Checks if the node is running.
	 *
	 * @return {@code true} if the node is running, {@code false} otherwise.
	 */
	public default boolean isRunning() {
		return getStatus() == NodeStatus.Running;
	}

	/**
	 * Signs the given data.
	 *
	 * @param data the data to sign.
	 * @return the signature.
	 */
	@Override
	public byte[] sign(byte[] data);

	/**
	 * Verifies the signature of the given data.
	 *
	 * @param data the data to verify.
	 * @param signature the signature to verify.
	 * @return {@code true} if the signature is valid, {@code false} otherwise.
	 */
	@Override
	public boolean verify(byte[] data, byte[] signature);

	/**
	 * Encrypts the given data for a specific recipient.
	 *
	 * @param recipient the ID of the recipient.
	 * @param data the data to encrypt.
	 * @return the encrypted data.
	 */
	@Override
	public byte[] encrypt(Id recipient, byte[] data);

	/**
	 * Decrypts the given data from a specific sender.
	 *
	 * @param sender the ID of the sender.
	 * @param data the data to decrypt.
	 * @return the decrypted data.
	 * @throws BosonException if an error occurs during decryption.
	 */
	@Override
	public byte[] decrypt(Id sender, byte[] data) throws BosonException;

	/**
	 * Create {@code CryptoContext} object for the target Id.
	 *
	 * @param id the target id
	 * @return the {@code CryptoContext} object for id
	 */
	@Override
	public CryptoContext createCryptoContext(Id id);

	/**
	 * Lookup the information about a node with the given ID.
	 *
	 * @param id the ID of the node to find.
	 * @return a {@code CompletableFuture} object to retrieve the result of the node lookup.
	 */
	public default CompletableFuture<Result<NodeInfo>> findNode(Id id) {
		return findNode(id, null);
	}
	/**
	 * Lookup the information about a node with the given ID.
	 *
	 * @param id the ID of the node to find.
	 * @param option the lookup option to use.
	 * @return a {@code CompletableFuture} object to retrieve the result of the node lookup.
	 */
	public CompletableFuture<Result<NodeInfo>> findNode(Id id, LookupOption option);

	/**
	 * Lookup the value with the given ID on the Boson network.
	 *
	 * @param id the ID of the value to lookup.
	 * @return a {@code CompletableFuture} object to retrieve the result of the value lookup.
	 */
	public default CompletableFuture<Value> findValue(Id id) {
		return findValue(id, null);
	}

	/**
	 * Lookup the value with the given ID on the Boson network.
	 *
	 * @param id the ID of the value to lookup.
	 * @param option the lookup option to use.
	 * @return a {@code CompletableFuture} object to retrieve the result of the value lookup.
	 */
	public CompletableFuture<Value> findValue(Id id, LookupOption option);

	/**
	 * Stores a value in the network.
	 *
	 * @param value the value to store.
	 * @param persistent {@code true} if the value should be stored persistently, {@code false} otherwise.
	 * @return a {@code CompletableFuture} representing the completion of the operation.
	 */
	public CompletableFuture<Void> storeValue(Value value, boolean persistent);

	/**
	 * Stores a value in the network without persistency.
	 *
	 * @param value the value to store.
	 * @return a {@code CompletableFuture} representing the completion of the operation.
	 */
	public default CompletableFuture<Void> storeValue(Value value) {
		return storeValue(value, false);
	}

	/**
	 * Lookup peers in the network with the given ID.
	 *
	 * @param id the ID to find peers for.
	 * @param expected the expected number of peers to lookup.
	 * @return a {@code CompletableFuture} object to retrieve the the list of peers found.
	 */
	public default CompletableFuture<List<PeerInfo>> findPeer(Id id, int expected) {
		return findPeer(id, expected, null);
	}

	/**
	 * Lookup peers in the network with the given ID.
	 *
	 * @param id the ID to find peers for.
	 * @param expected the expected number of peers to lookup.
	 * @param option the lookup option to use.
	 * @return a {@code CompletableFuture} object to retrieve the the list of peers found.
	 */
	public CompletableFuture<List<PeerInfo>> findPeer(Id id, int expected, LookupOption option);

	/**
	 * Announces a peer in the network.
	 *
	 * @param peer the peer to announce.
	 * @param persistent {@code true} if the peer should be announced persistently, {@code false} otherwise.
	 * @return a {@code CompletableFuture} representing the completion of the operation.
	 */
	public CompletableFuture<Void> announcePeer(PeerInfo peer, boolean persistent);

	/**
	 * Announces a peer in the network without persistency.
	 *
	 * @param peer the peer to announce.
	 * @return a {@code CompletableFuture} representing the completion of the operation.
	 */
	public default CompletableFuture<Void> announcePeer(PeerInfo peer) {
		return announcePeer(peer, false);
	}

	/**
	 * Gets the value associated with the given ID from the node's storage.
	 *
	 * @param valueId the ID of the value to retrieve.
	 * @return a {@code CompletableFuture} representing the completion of the operation.
	 */
	public CompletableFuture<Value> getValue(Id valueId);

	/**
	 * Removes the value with the given ID from the node's storage.
	 *
	 * @param valueId the ID of the value to remove.
	 * @return a {@code CompletableFuture} representing the completion of the operation.
	 */
	public CompletableFuture<Boolean> removeValue(Id valueId);

	/**
	 * Gets the peer information with the given ID from the node's storage.
	 *
	 * @param peerId the ID of the peer to retrieve information for.
	 * @return a {@code CompletableFuture} representing the completion of the operation.
	 */
	public CompletableFuture<PeerInfo> getPeer(Id peerId);

	/**
	 * Removes the peer entry with the given ID from the node's storage.
	 *
	 * @param peerId the ID of the peer to remove.
	 * @return a {@code CompletableFuture} representing the completion of the operation.
	 */
	public CompletableFuture<Boolean> removePeer(Id peerId);

	/**
	 * Get the software version.
	 *
	 * @return the version string.
	 */
	public String getVersion();
}
