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

import io.bosonnetwork.crypto.CryptoException;

/**
 * Represents a Boson DHT node with full network, storage, and cryptographic capabilities.
 * <p>
 * This interface extends {@link Identity} and provides methods for:
 * <ul>
 *     <li>Accessing node information and version</li>
 *     <li>Bootstrapping from other nodes</li>
 *     <li>Finding nodes, peers, and values in the network</li>
 *     <li>Storing values and announcing peers (optionally persistent)</li>
 *     <li>Cryptographic operations: sign, verify, encrypt, decrypt</li>
 * </ul>
 */
public interface Node extends Identity {
	/**
	 * Gets the ID of the node.
	 *
	 * @return the {@link Id} of the node
	 */
	@Override
	Id getId();

	/**
	 * Retrieves detailed information about this node, including IPv4/IPv6 addresses.
	 *
	 * @return a {@link Result} containing {@link NodeInfo} for this node
	 */
	Result<NodeInfo> getNodeInfo();

	/**
	 * Get the software version.
	 *
	 * @return the version string.
	 */
	String getVersion();

	/**
	 * Sets the default lookup option used for network queries.
	 *
	 * @param option the default {@link LookupOption} to use
	 */
	void setDefaultLookupOption(LookupOption option);

	/**
	 * Gets the default lookup option currently set for the node.
	 *
	 * @return the default {@link LookupOption}
	 */
	LookupOption getDefaultLookupOption();

	/**
	 * Adds a listener to monitor connection status changes.
	 *
	 * @param listener the {@link ConnectionStatusListener} to add
	 */
	void addConnectionStatusListener(ConnectionStatusListener listener);

	/**
	 * Removes a previously registered connection status listener.
	 *
	 * @param listener the listener to remove
	 */
	void removeConnectionStatusListener(ConnectionStatusListener listener);

	/**
	 * Starts the node.
	 *
	 * @return a {@link CompletableFuture} that completes when the node is running
	 */
	CompletableFuture<Void> run();

	/**
	 * Shutdown the node asynchronously.
	 *
	 * @return a {@link CompletableFuture} that completes when the node has shut down
	 */
	CompletableFuture<Void> shutdown();

	/**
	 * Checks whether the node is currently running.
	 *
	 * @return {@code true} if the node is running, {@code false} otherwise
	 */
	boolean isRunning();

	/**
	 * Bootstraps this node using a single node's information.
	 *
	 * @param node the {@link NodeInfo} to bootstrap with
	 * @return a {@link CompletableFuture} that completes when bootstrap is finished
	 */
	default CompletableFuture<Void> bootstrap(NodeInfo node) {
		return bootstrap(List.of(node));
	}

	/**
	 * Bootstraps this node using multiple nodes' information.
	 *
	 * @param bootstrapNodes the collection of {@link NodeInfo} to bootstrap with
	 * @return a {@link CompletableFuture} that completes when bootstrap is finished
	 */
	CompletableFuture<Void>	bootstrap(Collection<NodeInfo> bootstrapNodes);

	/**
	 * Finds a node by its ID using the default lookup option.
	 *
	 * @param id the {@link Id} of the node to find
	 * @return a {@link CompletableFuture} containing the {@link Result} of the lookup
	 */
	default CompletableFuture<Result<NodeInfo>> findNode(Id id) {
		return findNode(id, null);
	}

	/**
	 * Finds a node by its ID with a specific lookup option.
	 *
	 * @param id the {@link Id} of the node to find
	 * @param option the {@link LookupOption} to use
	 * @return a {@link CompletableFuture} containing the {@link Result} of the lookup
	 */
	CompletableFuture<Result<NodeInfo>> findNode(Id id, LookupOption option);

	/**
	 * Finds a value by its ID without the expected sequence number and default lookup option.
	 *
	 * @param id the {@link Id} of the value to find
	 * @return a {@link CompletableFuture} containing the {@link Value}
	 */
	default CompletableFuture<Value> findValue(Id id) {
		return findValue(id, -1, null);
	}

	/**
	 *  Finds a value by its ID with the expected sequence number and default lookup option.
	 *
	 * @param id the unique identifier for the value to be retrieved
	 * @param expectedSequenceNumber the expected sequence number to match for the value
	 * @return a CompletableFuture containing the result value if found, otherwise may resolve to null
	 */
	default CompletableFuture<Value> findValue(Id id, int expectedSequenceNumber) {
		return findValue(id, expectedSequenceNumber, null);
	}

	/**
	 * Finds a value by its ID without the expected sequence number and the specified lookup option.
	 *
	 * @param id the identifier for which the value is to be looked up
	 * @param option the lookup option to use during the search
	 * @return a CompletableFuture that completes with the found value or an appropriate result based on the lookup
	 */
	default CompletableFuture<Value> findValue(Id id, LookupOption option) {
		return findValue(id, -1, option);
	}

	/**
	 * Finds a value by its ID with the specific expected sequence number and lookup option.
	 *
	 * @param id the {@link Id} of the value to find
	 * @param expectedSequenceNumber the expected sequence number for consistency
	 * @param option the {@link LookupOption} to use
	 * @return a {@link CompletableFuture} containing the {@link Value}
	 */
	CompletableFuture<Value> findValue(Id id, int expectedSequenceNumber, LookupOption option);

	/**
	 * Stores a value in the network without persistency.
	 *
	 * @param value the value to store.
	 * @return a {@code CompletableFuture} representing the completion of the operation.
	 */
	default CompletableFuture<Void> storeValue(Value value) {
		return storeValue(value, -1, false);
	}

	/**
	 * Stores the given value in the system with the provided expected sequence number.
	 *
	 * @param value the value to be stored
	 * @param expectedSequenceNumber the sequence number expected for this operation
	 * @return a {@code CompletableFuture} that completes when the operation is finished
	 */
	default CompletableFuture<Void> storeValue(Value value, int expectedSequenceNumber) {
		return storeValue(value, expectedSequenceNumber, false);
	}

	/**
	 * Stores a value in the network.
	 *
	 * @param value       the value to store.
	 * @param persistent  {@code true} if the value should be stored persistently, {@code false} otherwise.
	 * @return a {@code CompletableFuture} representing the completion of the operation.
	 */
	default CompletableFuture<Void> storeValue(Value value, boolean persistent) {
		return storeValue(value, -1, persistent);
	}

	/**
	 * Stores a value in the network.
	 *
	 * @param value the value to be stored
	 * @param expectedSequenceNumber the expected sequence number for the value
	 * @param persistent determines whether the value should be stored persistently
	 * @return a {@code CompletableFuture} representing the completion of the operation.
	 */
	CompletableFuture<Void> storeValue(Value value, int expectedSequenceNumber, boolean persistent);

	/**
	 * Finds peers in the network by ID using the default lookup option.
	 *
	 * @param id the {@link Id} to find peers for
	 * @return a {@link CompletableFuture} containing the list of {@link PeerInfo}
	 */
	default CompletableFuture<List<PeerInfo>> findPeer(Id id) {
		return findPeer(id, 0, null);
	}

	/**
	 * Finds peers in the network by ID, with expected number of peers.
	 *
	 * @param id the unique identifier to search for.
	 * @param expected the number of peers expected to be found.
	 * @return a {@code CompletableFuture} containing a list of PeerInfo objects representing the found peers.
	 */
	default CompletableFuture<List<PeerInfo>> findPeer(Id id, int expected) {
		return findPeer(id, expected, null);
	}

	/**
	 * Finds peers in the network by ID using the specified lookup option.
	 *
	 * @param id the unique identifier for which peers are being searched
	 * @param option the lookup option specifying how the search should be conducted
	 * @return a {@code CompletableFuture} containing a list of PeerInfo objects representing the peers found
	 */
	default CompletableFuture<List<PeerInfo>> findPeer(Id id, LookupOption option) {
		return findPeer(id, 0, option);
	}

	/**
	 * Lookup peers in the network with the given ID.
	 *
	 * @param id the ID to find peers for.
	 * @param expected the expected number of peers to lookup.
	 * @param option lookup option to use.
	 * @return a {@code CompletableFuture} object to retrieve the list of peers found.
	 */
	CompletableFuture<List<PeerInfo>> findPeer(Id id, int expected, LookupOption option);

	/**
	 * Announces a peer to the network without persistence.
	 *
	 * @param peer the {@link PeerInfo} to announce
	 * @return a {@link CompletableFuture} representing completion
	 */
	default CompletableFuture<Void> announcePeer(PeerInfo peer) {
		return announcePeer(peer, false);
	}

	/**
	 * Announces a peer to the network with optional persistence.
	 *
	 * @param peer the {@link PeerInfo} to announce
	 * @param persistent whether to announce persistently
	 * @return a {@link CompletableFuture} representing completion
	 */
	CompletableFuture<Void> announcePeer(PeerInfo peer, boolean persistent);

	/**
	 * Gets the value associated with the given ID from the node's local storage.
	 *
	 * @param valueId the ID of the value to retrieve.
	 * @return a {@code CompletableFuture} representing the completion of the operation.
	 */
	CompletableFuture<Value> getValue(Id valueId);

	/**
	 * Removes the value with the given ID from the node's local storage.
	 *
	 * @param valueId the ID of the value to remove.
	 * @return a {@code CompletableFuture} representing the completion of the operation.
	 */
	CompletableFuture<Boolean> removeValue(Id valueId);

	/**
	 * Gets the peer information with the given ID from the node's local storage.
	 *
	 * @param peerId the ID of the peer to retrieve information for.
	 * @return a {@code CompletableFuture} representing the completion of the operation.
	 */
	CompletableFuture<PeerInfo> getPeer(Id peerId);

	/**
	 * Removes the peer entry with the given ID from the node's local storage.
	 *
	 * @param peerId the ID of the peer to remove.
	 * @return a {@code CompletableFuture} representing the completion of the operation.
	 */
	CompletableFuture<Boolean> removePeer(Id peerId);

	/**
	 * Signs the given data.
	 *
	 * @param data the data to sign.
	 * @return the signature.
	 */
	@Override
	byte[] sign(byte[] data);

	/**
	 * Verifies the signature of the given data.
	 *
	 * @param data the data to verify.
	 * @param signature the signature to verify.
	 * @return {@code true} if the signature is valid, {@code false} otherwise.
	 */
	@Override
	boolean verify(byte[] data, byte[] signature);

	/**
	 * Encrypts the given data for a specific recipient.
	 *
	 * @param recipient the ID of the recipient.
	 * @param data the data to encrypt.
	 * @return the encrypted data.
	 */
	@Override
	byte[] encrypt(Id recipient, byte[] data) throws CryptoException;

	/**
	 * Decrypts the given data from a specific sender.
	 *
	 * @param sender the ID of the sender.
	 * @param data the data to decrypt.
	 * @return the decrypted data.
	 * @throws CryptoException if an error occurs during decryption.
	 */
	@Override
	byte[] decrypt(Id sender, byte[] data) throws CryptoException;

	/**
	 * Create {@code CryptoContext} object for the target Id.
	 *
	 * @param id the target id
	 * @return the {@code CryptoContext} object for id
	 */
	@Override
	CryptoContext createCryptoContext(Id id) throws CryptoException;

	/**
	 * Creates and initializes a new KadNode instance using the provided configuration.
	 *
	 * @param config the configuration object used to initialize the KadNode.
	 * @return an initialized instance of a {@code Node} representing the KadNode.
	 * @throws BosonException if the KadNode class is not found or an error occurs during instantiation.
	 */
	static Node kadNode(NodeConfiguration config) throws BosonException {
		try {
			return (Node) Class.forName("io.bosonnetwork.kademlia.KadNode")
					.getConstructor(NodeConfiguration.class)
					.newInstance(config);
		} catch (ClassNotFoundException e) {
			throw new BosonException("KadNode not found in classpath", e);
		} catch (Exception e) {
			throw new BosonException("Internal error: can not instantiate KadNode", e);
		}
	}
}