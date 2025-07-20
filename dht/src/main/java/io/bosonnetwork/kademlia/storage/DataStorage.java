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
	 * @param connectionUri the database connection URI
	 * @param valueExpiration the expiration time for values in milliseconds
	 * @param peerInfoExpiration the expiration time for peer information in milliseconds
	 * @return a {@link Future} containing the current schema version of the storage system
	 */
	Future<Integer> initialize(Vertx vertx, String connectionUri, long valueExpiration, long peerInfoExpiration);

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
	 * Stores a value in the storage with the specified identifier, sequence number, and persistence flag.
	 *
	 * @param value               the value to store
	 * @param persistent          true if the value should be stored persistently, false otherwise
	 * @param expectedSequenceNumber the expected sequence number for the value
	 * @return a {@link Future} containing the stored {@link Value}
	 */
	Future<Value> putValue(Value value, boolean persistent, int expectedSequenceNumber);

	/**
	 * Retrieves a value from the DHT by its identifier.
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
	 * Removes a value from the DHT by its identifier.
	 *
	 * @param id the identifier of the value to remove
	 * @return a {@link Future Future<Boolean>} that completes with {@code true} if the value was successfully removed,
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
	 * Retrieves peer information by peer and node identifiers.

	 * @param id     the peer identifier
	 * @param nodeId the node identifier
	 * @return a {@link Future} containing the {@link PeerInfo} or null if not found
	 */
	Future<PeerInfo> getPeer(Id id, Id nodeId);

	/**
	 * Retrieves all peer information associated with a peer identifier.
	 *
	 * @param id the peer identifier
	 * @return a {@link Future} containing a list of {@link PeerInfo}s
	 */
	Future<List<PeerInfo>> getPeers(Id id);

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
	 * @param nodeId the node identifier
	 * @return a {@link Future} containing the updated timestamp (in milliseconds)
	 */
	Future<Long> updatePeerAnnouncedTime(Id id, Id nodeId);

	/**
	 * Removes peer information by peer and node identifiers.
	 *
	 * @param id     the peer identifier
	 * @param nodeId the node identifier
	 * @return a {@link Future Future<Boolean>} that completes with {@code true} if the peer was successfully removed,
	 *         or {@code false} if no matching peer was found
	 */
	Future<Boolean> removePeer(Id id, Id nodeId);

	/**
	 * Removes all peer information associated with a peer identifier.
	 *
	 * @param id the peer identifier
	 * @return a {@link Future Future<Boolean>} that completes with {@code true} if the peers was successfully removed,
	 *         or {@code false} if no matching peer was found
	 */
	Future<Boolean> removePeers(Id id);
}