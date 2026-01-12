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

import io.vertx.core.Future;
import io.vertx.core.Vertx;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;

/**
 * Interface for a Kademlia local storage system.
 * Provides methods to manage values and peer information, including storage, retrieval,
 * and maintenance operations.
 */
public interface DataStorage {
	/**
	 * Initializes the storage system by creating necessary tables and indexes.
	 *
	 * @param vertx the Vert.x instance to use for asynchronous operations
	 * @param valueExpiration the expiration time for values in milliseconds
	 * @param peerInfoExpiration the expiration time for peer information in milliseconds
	 * @return a {@link Future} containing the current schema version of the storage system
	 */
	Future<Integer> initialize(Vertx vertx, long valueExpiration, long peerInfoExpiration);

	/**
	 * Closes the storage system.
	 *
	 * @return a {@link Future} that completes when the storage system is closed
	 */
	Future<Void> close();

	/**
	 * Retrieves the current schema version of the storage system.
	 *
	 * @return the schema version
	 */
	int getSchemaVersion();

	/**
	 * Removes expired data from the storage system.
	 *
	 * @return a {@link Future} that completes when the purge operation is done
	 */
	Future<Void> purge();

	/**
	 * Stores a value in the storage with the specified identifier.
	 *
	 * @param value the value to store
	 * @return a {@link Future} containing the stored {@link Value}
	 */
	Future<Value> putValue(Value value);

	/**
	 * Stores a value in the storage with the specified identifier and persistence flag.
	 *
	 * @param value       the value to store
	 * @param persistent  true if the value should be stored persistently, false otherwise
	 * @return a {@link Future} containing the stored {@link Value}
	 */
	Future<Value> putValue(Value value, boolean persistent);

	/**
	 * Retrieves a value from the local storage by its identifier.
	 *
	 * @param id the identifier of the value
	 * @return a {@link Future} containing the {@link Value} or null if not found
	 */
	Future<Value> getValue(Id id);

	/**
	 * Retrieves all values stored in the storage.
	 *
	 * @return a {@link Future} containing a list of all {@link Value}s
	 */
	Future<List<Value>> getValues();

	/**
	 * Retrieves a paginated list of values stored in the storage.
	 *
	 * @param offset the starting index (non-negative)
	 * @param limit  the maximum number of values to return (positive)
	 * @return a {@link Future} containing a list of {@link Value}s
	 * @throws IllegalArgumentException if offset is negative or limit is non-positive
	 */
	Future<List<Value>> getValues(int offset, int limit);

	/**
	 * Retrieves values filtered by persistence and announcement time.
	 *
	 * @param persistent      true to retrieve only persistent values, false for non-persistent
	 * @param announcedBefore timestamp (in milliseconds) to filter values announced before
	 * @return a {@link Future} containing a list of matching {@link Value}s
	 */
	Future<List<Value>> getValues(boolean persistent, long announcedBefore);

	/**
	 * Retrieves a paginated list of values filtered by persistence and announcement time.
	 *
	 * @param persistent      true to retrieve only persistent values, false for non-persistent
	 * @param announcedBefore timestamp (in milliseconds) to filter values announced before
	 * @param offset          the starting index (non-negative)
	 * @param limit           the maximum number of values to return (positive)
	 * @return a {@link Future} containing a list of matching {@link Value}s
	 * @throws IllegalArgumentException if offset is negative or limit is non-positive
	 */
	Future<List<Value>> getValues(boolean persistent, long announcedBefore, int offset, int limit);

	/**
	 * Updates the announcement timestamp for a value.
	 *
	 * @param id the identifier of the value
	 * @return a {@link Future} containing the updated timestamp (in milliseconds)
	 */
	Future<Long> updateValueAnnouncedTime(Id id);

	/**
	 * Removes a value from the storage by its identifier.
	 *
	 * @param id the identifier of the value to remove
	 * @return a {@link Future}{@code <Boolean>} that completes with {@code true} if the value was successfully removed,
	 *         or {@code false} if no matching value was found
	 */
	Future<Boolean> removeValue(Id id);

	/**
	 * Stores peer information in the storage.
	 *
	 * @param peerInfo the peer information to store
	 * @return a {@link Future} containing the stored {@link PeerInfo}
	 */
	Future<PeerInfo> putPeer(PeerInfo peerInfo);

	/**
	 * Stores peer information in the storage with a persistence flag.
	 *
	 * @param peerInfo    the peer information to store
	 * @param persistent  true if the peer information should be stored persistently, false otherwise
	 * @return a {@link Future} containing the stored {@link PeerInfo}
	 */
	Future<PeerInfo> putPeer(PeerInfo peerInfo, boolean persistent);

	/**
	 * Stores a list of peer information.
	 *
	 * @param peerInfos the list of peer information to store
	 * @return a {@link Future} containing the list of stored {@link PeerInfo}s
	 */
	Future<List<PeerInfo>> putPeers(List<PeerInfo> peerInfos);


	/**
	 * Retrieves information about a peer based on the provided identifier and serial number.
	 *
	 * @param id the unique identifier of the peer
	 * @param fingerprint the serial number associated with the peer
	 * @return a Future containing the peer information as a PeerInfo object
	 */
	Future<PeerInfo> getPeer(Id id, long fingerprint);

	/**
	 * Retrieves all peer information associated with a peer identifier.
	 *
	 * @param id the peer identifier
	 * @return a {@link Future} containing a list of {@link PeerInfo}s
	 */
	Future<List<PeerInfo>> getPeers(Id id);

	/**
	 * Retrieves peer information associated with a peer identifier, filtered by sequence number.
	 *
	 * @param id                     the peer identifier
	 * @param expectedSequenceNumber the minimum sequence number to include
	 * @param limit                  the maximum number of results to return (positive)
	 * @return a {@link Future} containing a list of matching {@link PeerInfo}s
	 * @throws IllegalArgumentException if limit is non-positive
	 */
	Future<List<PeerInfo>> getPeers(Id id, int expectedSequenceNumber, int limit);

	/**
	 * Retrieves peer information by peer and node identifiers.
	 *
	 * @param id     the peer identifier
	 * @param nodeId the node identifier
	 * @return a {@link Future} containing a list of matching {@link PeerInfo}s
	 */
	Future<List<PeerInfo>> getPeers(Id id, Id nodeId);

	/**
	 * Retrieves all peer information stored in the storage.
	 *
	 * @return a {@link Future} containing a list of all {@link PeerInfo}s
	 */
	Future<List<PeerInfo>> getPeers();

	/**
	 * Retrieves a paginated list of peer information stored in the storage.
	 *
	 * @param offset the starting index (non-negative)
	 * @param limit  the maximum number of peers to return (positive)
	 * @return a {@link Future} containing a list of {@link PeerInfo}s
	 * @throws IllegalArgumentException if offset is negative or limit is non-positive
	 */
	Future<List<PeerInfo>> getPeers(int offset, int limit);

	/**
	 * Retrieves peer information filtered by persistence and announcement time.
	 *
	 * @param persistent      true to retrieve only persistent peers, false for non-persistent
	 * @param announcedBefore timestamp (in milliseconds) to filter peers announced before
	 * @return a {@link Future} containing a list of matching {@link PeerInfo}s
	 */
	Future<List<PeerInfo>> getPeers(boolean persistent, long announcedBefore);

	/**
	 * Retrieves a paginated list of peer information filtered by persistence and announcement time.
	 *
	 * @param persistent      true to retrieve only persistent peers, false for non-persistent
	 * @param announcedBefore timestamp (in milliseconds) to filter peers announced before
	 * @param offset          the starting index (non-negative)
	 * @param limit           the maximum number of peers to return (positive)
	 * @return a {@link Future} containing a list of matching {@link PeerInfo}s
	 * @throws IllegalArgumentException if offset is negative or limit is non-positive
	 */
	Future<List<PeerInfo>> getPeers(boolean persistent, long announcedBefore, int offset, int limit);

	/**
	 * Updates the announcement timestamp for a peer.
	 *
	 * @param id     the peer identifier
	 * @param fingerprint the serial number associated with the peer
	 * @return a {@link Future} containing the updated timestamp (in milliseconds)
	 */
	Future<Long> updatePeerAnnouncedTime(Id id, long fingerprint);

	/**
	 * Removes peer information by peer and node identifiers.
	 *
	 * @param id     the peer identifier
	 * @param fingerprint the serial number associated with the peer
	 * @return a {@link Future}{@code <Boolean>} that completes with {@code true} if the peer was successfully removed,
	 *         or {@code false} if no matching peer was found
	 */
	Future<Boolean> removePeer(Id id, long fingerprint);

	/**
	 * Removes all peer information associated with a peer identifier.
	 *
	 * @param id the peer identifier
	 * @return a {@link Future}{@code <Boolean>} that completes with {@code true} if the peers was successfully removed,
	 *         or {@code false} if no matching peer was found
	 */
	Future<Boolean> removePeers(Id id);

	/**
	 * Checks if the provided storage URI is supported by this implementation.
	 *
	 * @param uri the storage URI to check
	 * @return true if the URI is supported, false otherwise
	 */
	static boolean supports(String uri) {
		// now only support sqlite and postgres
		return uri.startsWith(SQLiteStorage.STORAGE_URI_PREFIX) || uri.startsWith(PostgresStorage.STORAGE_URI_PREFIX);
	}

	/**
	 * Creates a new DataStorage instance based on the provided URI.
	 *
	 * @param uri      the storage connection URI
	 * @param poolSize the connection pool size
	 * @param schema   the database schema name (if applicable, e.g., for PostgreSQL)
	 * @return a new DataStorage instance
	 * @throws IllegalArgumentException if the URI is unsupported
	 */
	static DataStorage create(String uri, int poolSize, String schema) {
		Objects.requireNonNull(uri, "url");

		if (uri.startsWith(SQLiteStorage.STORAGE_URI_PREFIX))
			return new SQLiteStorage(uri, poolSize);
		else if (uri.startsWith(PostgresStorage.STORAGE_URI_PREFIX))
			return new PostgresStorage(uri, poolSize, schema);
		else
			throw new IllegalArgumentException("Unsupported storage: " + uri);
	}
}