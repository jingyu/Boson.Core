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
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

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
 * <p>
 * <b>Lookup result conventions:</b> {@link #findNode} completes with an {@link java.util.Optional}
 * {@link NodeInfo} that, when present, may carry the node's IPv4 and/or IPv6 address (either side may
 * be {@code null}). Single-result lookups ({@link #findValue}, {@link #getValue}, the single-result
 * {@link #findPeer(Id)}, {@link #getPeer}) complete with an {@link java.util.Optional} that is empty
 * when nothing is found, while collection lookups ({@link #getPeers},
 * {@link #findPeer(Id, int, int, LookupOption)}) complete with an empty list.
 */
public interface Node extends Identity {
	/** The maximum age for a peer (2 hours). */
	int MAX_PEER_AGE = 120 * 60 * 1000;             // 2 hours in milliseconds
	/** The maximum age for a value (2 hours). */
	int MAX_VALUE_AGE = 120 * 60 * 1000;            // 2 hours in milliseconds

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
	 * @return a {@link Optional} containing {@link NodeInfo} for this node
	 */
	Optional<NodeInfo> getNodeInfo();

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
	CompletableFuture<Void> start();

	/**
	 * Shutdown the node asynchronously.
	 *
	 * @return a {@link CompletableFuture} that completes when the node has shut down
	 */
	CompletableFuture<Void> stop();

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
	 * @return a {@link CompletableFuture} containing an {@link Optional} {@link NodeInfo} of the lookup
	 */
	default CompletableFuture<Optional<NodeInfo>> findNode(Id id) {
		return findNode(id, null);
	}

	/**
	 * Finds a node by its ID with a specific lookup option.
	 * <p>
	 * When present, the returned {@link NodeInfo} records which address families answered the lookup:
	 * {@link NodeInfo#hasAddress4()} and {@link NodeInfo#hasAddress6()} are true only for the families
	 * that contributed a result. A dual-stack node that responded over a single family therefore yields
	 * a single-address {@link NodeInfo} (a {@link LookupOption#CONSERVATIVE} lookup queries both families
	 * and still succeeds with a partial result if only one responds).
	 *
	 * @param id the {@link Id} of the node to find
	 * @param option the {@link LookupOption} to use
	 * @return a {@link CompletableFuture} containing an {@link Optional} {@link NodeInfo} of the lookup
	 */
	CompletableFuture<Optional<NodeInfo>> findNode(Id id, @Nullable LookupOption option);

	/**
	 * Finds a value by its ID without the expected sequence number and default lookup option.
	 *
	 * @param id the {@link Id} of the value to find
	 * @return a {@link CompletableFuture} completing with an {@link Optional} {@link Value} (empty if not found)
	 */
	default CompletableFuture<Optional<Value>> findValue(Id id) {
		return findValue(id, -1, null);
	}

	/**
	 * Finds a value by its ID with the expected sequence number and default lookup option.
	 *
	 * @param id the unique identifier for the value to be retrieved
	 * @param expectedSequenceNumber the expected sequence number to match for the value,
	 *                               use -1 if no specific sequence number is expected
	 * @return a {@link CompletableFuture} completing with an {@link Optional} {@link Value} (empty if not found)
	 */
	default CompletableFuture<Optional<Value>> findValue(Id id, int expectedSequenceNumber) {
		return findValue(id, expectedSequenceNumber, null);
	}

	/**
	 * Finds a value by its ID without the expected sequence number and the specified lookup option.
	 *
	 * @param id the identifier for which the value is to be looked up
	 * @param option the {@link LookupOption} to use
	 * @return a {@link CompletableFuture} completing with an {@link Optional} {@link Value} (empty if not found)
	 */
	default CompletableFuture<Optional<Value>> findValue(Id id, @Nullable LookupOption option) {
		return findValue(id, -1, option);
	}

	/**
	 * Finds a value by its ID with the specific expected sequence number and lookup option.
	 *
	 * @param id the {@link Id} of the value to find
	 * @param expectedSequenceNumber the expected sequence number for consistency
	 * @param option the {@link LookupOption} to use
	 * @return a {@link CompletableFuture} completing with an {@link Optional} {@link Value} (empty if not found)
	 */
	CompletableFuture<Optional<Value>> findValue(Id id, int expectedSequenceNumber, @Nullable LookupOption option);

	/**
	 * Stores a value in the network without persistence.
	 *
	 * @param value the {@link Value} to store.
	 * @return a {@link CompletableFuture} representing the completion of the operation.
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
	 * Stores a value in the network with optional persistence.
	 *
	 * @param value       the {@link Value} to store.
	 * @param persistent  {@code true} if the value should be stored persistently, {@code false} otherwise.
	 * @return a {@link CompletableFuture} representing the completion of the operation.
	 */
	default CompletableFuture<Void> storeValue(Value value, boolean persistent) {
		return storeValue(value, -1, persistent);
	}

	/**
	 * Stores a value in the network with optional persistence and expected sequence number.
	 *
	 * @param value                  the {@link Value} to be stored
	 * @param expectedSequenceNumber the expected sequence number for the value,
	 *                               use -1 if no specific sequence number is expected
	 * @param persistent             determines whether the value should be stored persistently
	 * @return a {@link CompletableFuture} representing the completion of the operation.
	 */
	CompletableFuture<Void> storeValue(Value value, int expectedSequenceNumber, boolean persistent);

	/**
	 * Finds a peer in the network by ID using the default lookup option.
	 *
	 * @param id the {@link Id} to find peers for
	 * @return a {@link CompletableFuture} completing with an {@link Optional} {@link PeerInfo} (empty if not found)
	 */
	default CompletableFuture<Optional<PeerInfo>> findPeer(Id id) {
		return findPeer(id, -1, 1, null)
				.thenApply(peers -> peers.isEmpty() ? Optional.empty() : Optional.of(peers.get(0)));
	}


	/**
	 * Finds a peer in the network by ID with the expected sequence number.
	 *
	 * @param id                     the {@link Id} to find peers for
	 * @param expectedSequenceNumber the expected sequence number for consistency,
	 *                               use -1 if no specific sequence number is expected
	 * @return a {@link CompletableFuture} completing with an {@link Optional} {@link PeerInfo} (empty if not found)
	 */
	default CompletableFuture<Optional<PeerInfo>> findPeer(Id id, int expectedSequenceNumber) {
		return findPeer(id, expectedSequenceNumber, 1,null)
				.thenApply(peers -> peers.isEmpty() ? Optional.empty() : Optional.of(peers.get(0)));
	}

	/**
	 * Finds a peer in the network by ID using the specified lookup option.
	 *
	 * @param id     the {@link Id} to find peers for
	 * @param option the {@link LookupOption} to use
	 * @return a {@link CompletableFuture} completing with an {@link Optional} {@link PeerInfo} (empty if not found)
	 */
	default CompletableFuture<Optional<PeerInfo>> findPeer(Id id, @Nullable LookupOption option) {
		return findPeer(id, -1, 1, option)
				.thenApply(peers -> peers.isEmpty() ? Optional.empty() : Optional.of(peers.get(0)));
	}

	/**
	 * Finds a peer in the network by ID with the given expected sequence number and lookup option.
	 *
	 * @param id                     the {@link Id} to find peers for
	 * @param expectedSequenceNumber the expected sequence number for consistency,
	 *                               use -1 if no specific sequence number is expected
	 * @param option                 the {@link LookupOption} to use
	 * @return a {@link CompletableFuture} completing with an {@link Optional} {@link PeerInfo} (empty if not found)
	 */
	default CompletableFuture<Optional<PeerInfo>> findPeer(Id id, int expectedSequenceNumber, @Nullable LookupOption option) {
		return findPeer(id, expectedSequenceNumber, 1, option)
				.thenApply(peers -> peers.isEmpty() ? Optional.empty() : Optional.of(peers.get(0)));
	}

	/**
	 * Finds multiple peers in the network associated with the given identifier.
	 *
	 * @param id                     the {@link Id} of the peers to find
	 * @param expectedSequenceNumber the expected sequence number for consistency,
	 *                               use -1 if no specific sequence number is expected
	 * @param expectedCount          the maximum number of peers to retrieve
	 * @param option                 the {@link LookupOption} to use
	 * @return a {@link CompletableFuture} that will complete with a list of {@link PeerInfo} objects
	 */
	CompletableFuture<List<PeerInfo>> findPeer(Id id, int expectedSequenceNumber, int expectedCount, @Nullable LookupOption option);

	/**
	 * Announces a peer to the network without persistence.
	 *
	 * @param peer the {@link PeerInfo} to announce
	 * @return a {@link CompletableFuture} representing the completion of the operation
	 */
	default CompletableFuture<Void> announcePeer(PeerInfo peer) {
		return announcePeer(peer, -1, false);
	}

	/**
	 * Announces a peer to the network with the given expected sequence number and without persistence.
	 *
	 * @param peer                   the {@link PeerInfo} to announce
	 * @param expectedSequenceNumber the expected sequence number for consistency,
	 *                               use -1 if no specific sequence number is expected
	 * @return a {@link CompletableFuture} representing the completion of the operation
	 */
	default CompletableFuture<Void> announcePeer(PeerInfo peer, int expectedSequenceNumber) {
		return announcePeer(peer, expectedSequenceNumber, false);
	}

	/**
	 * Announces a peer to the network with optional persistence.
	 *
	 * @param peer       the {@link PeerInfo} to announce
	 * @param persistent whether to announce persistently
	 * @return a {@link CompletableFuture} representing the completion of the operation
	 */
	default CompletableFuture<Void> announcePeer(PeerInfo peer, boolean persistent) {
		return announcePeer(peer, -1, persistent);
	}

	/**
	 * Announces a peer to the network with optional persistence and expected sequence number.
	 *
	 * @param peer                   the {@link PeerInfo} to announce
	 * @param expectedSequenceNumber the expected sequence number for consistency,
	 *                               use -1 if no specific sequence number is expected
	 * @param persistent             whether to announce persistently
	 * @return a {@link CompletableFuture} representing the completion of the operation
	 */
	CompletableFuture<Void> announcePeer(PeerInfo peer, int expectedSequenceNumber, boolean persistent);

	/**
	 * Gets the value associated with the given ID from the node's local storage.
	 *
	 * @param valueId the {@link Id} of the value to retrieve.
	 * @return a {@link CompletableFuture} completing with an {@link Optional} {@link Value} (empty if absent locally).
	 */
	CompletableFuture<Optional<Value>> getValue(Id valueId);

	/**
	 * Removes the value with the given ID from the node's local storage.
	 *
	 * @param valueId the {@link Id} of the value to remove.
	 * @return a {@link CompletableFuture} representing the completion of the operation.
	 */
	CompletableFuture<Boolean> removeValue(Id valueId);

	/**
	 * Gets all peer information with the given ID from the node's local storage.
	 *
	 * @param peerId the {@link Id} of the peers to retrieve information for.
	 * @return a {@link CompletableFuture} representing the completion of the operation.
	 */
	CompletableFuture<List<PeerInfo>> getPeers(Id peerId);

	/**
	 * Removes all peer entries with the given ID from the node's local storage.
	 *
	 * @param peerId the {@link Id} of the peers to remove.
	 * @return a {@link CompletableFuture} representing the completion of the operation.
	 */
	CompletableFuture<Boolean> removePeers(Id peerId);

	/**
	 * Gets the peer information with the given ID and fingerprint from the node's local storage.
	 *
	 * @param peerId      the {@link Id} of the peer to retrieve information for.
	 * @param fingerprint the fingerprint of the peer to retrieve information for.
	 * @return a {@link CompletableFuture} completing with an {@link Optional} {@link PeerInfo} (empty if absent locally).
	 */
	CompletableFuture<Optional<PeerInfo>> getPeer(Id peerId, long fingerprint);

	/**
	 * Removes the peer entry with the given ID and fingerprint from the node's local storage.
	 *
	 * @param peerId      the {@link Id} of the peer to remove.
	 * @param fingerprint the fingerprint of the peer to remove.
	 * @return a {@link CompletableFuture} representing the completion of the operation.
	 */
	CompletableFuture<Boolean> removePeer(Id peerId, long fingerprint);

	/**
	 * Signs the given data using the node's private key.
	 *
	 * @param data the data to sign
	 * @return the signature
	 */
	@Override
	byte[] sign(byte[] data);

	/**
	 * Verifies the signature of the given data using the node's public key.
	 *
	 * @param data      the data to verify
	 * @param signature the signature to verify
	 * @return {@code true} if the signature is valid, {@code false} otherwise
	 */
	@Override
	boolean verify(byte[] data, byte[] signature);

	/**
	 * Encrypts the given data for a specific recipient.
	 *
	 * @param recipient the {@link Id} of the recipient
	 * @param data      the data to encrypt
	 * @return the encrypted data
	 * @throws CryptoException if encryption fails
	 */
	@Override
	byte[] encrypt(Id recipient, byte[] data) throws CryptoException;

	/**
	 * Decrypts the given data from a specific sender.
	 *
	 * @param sender the {@link Id} of the sender
	 * @param data   the data to decrypt
	 * @return the decrypted data
	 * @throws CryptoException if decryption fails
	 */
	@Override
	byte[] decrypt(Id sender, byte[] data) throws CryptoException;

	/**
	 * Creates a {@link CryptoContext} object for the target ID.
	 *
	 * @param id the target {@link Id}
	 * @return the {@link CryptoContext} object for the ID
	 * @throws CryptoException if context creation fails
	 */
	@Override
	CryptoContext createCryptoContext(Id id) throws CryptoException;

	/**
	 * Unwraps the Node to provide the underlying infrastructure instance.
	 *
	 * @param clazz the type of the infrastructure component (e.g. io.vertx.core.Vertx)
	 * @return an {@link Optional} with the component instance, or empty if not available or not supported
	 * @param <T> the type parameter
	 */
	<T> Optional<T> unwrap(Class<T> clazz);

	/**
	 * Creates and initializes a new {@link Node} instance using the provided configuration.
	 * <p>
	 * The concrete implementation is discovered via the {@link ServiceLoader} mechanism,
	 * looking up a registered {@link NodeFactory} provider (the Kademlia DHT node is
	 * provided by the {@code boson-dht} module).
	 *
	 * @param config the node configuration
	 * @return an initialized {@link Node} instance
	 * @throws BosonException if no node implementation is available, or it cannot be initialized
	 */
	static Node kadNode(NodeConfiguration config) throws BosonException {
		NodeFactory factory = ServiceLoader.load(NodeFactory.class).findFirst()
				.orElseThrow(() -> new BosonException("No NodeFactory implementation found in classpath"));
		return factory.create(config);
	}
}