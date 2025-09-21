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

package io.bosonnetwork.kademlia.security;

import io.vertx.core.net.SocketAddress;

import io.bosonnetwork.Id;

/**
 * Detect and manages suspicious nodes in a Kademlia DHT network by monitoring inconsistent node IDs
 * and malformed messages. Nodes are observed for a specified period and marked as suspicious when
 * they exceed a configurable hit threshold. Suspicious nodes are banned for a configurable duration.
 *
 * <p>Usage note: The {@link #purge()} method should be called periodically (e.g., every 2 minutes) to
 * remove expired entries and promote nodes to the suspicious list.</p>
 */
public interface SuspiciousNodeDetector {
	/**
	 * Constructs a detector with custom observation and ban parameters.
	 *
	 * @param observationPeriod Duration (in milliseconds) to observe a node before resetting or banning.
	 * @param observationHitThreshold Number of suspicious events required to ban a node.
	 * @param banDuration Duration (in milliseconds) a node remains banned after detection.
	 * @throws IllegalArgumentException if any parameter is non-positive.
	 */
	static SuspiciousNodeDetector create(long observationPeriod, int observationHitThreshold, long banDuration) {
		return new DefaultSuspiciousNodeDetector(observationPeriod, observationHitThreshold, banDuration);
	}

	/**
	 * Constructs a detector with default parameters: 10 hits, 15-minute observation period,
	 * and 30-minute ban duration.
	 */
	static SuspiciousNodeDetector create() {
		return new DefaultSuspiciousNodeDetector();
	}

	static SuspiciousNodeDetector disabled() {
		return new DisabledSuspiciousNodeDetector();
	}

	/**
	 * Checks if a node at the given address is suspicious based on an expected ID.
	 *
	 * <p>A node is considered suspicious if:</p>
	 * <ul>
	 *   <li>Its host is banned, OR</li>
	 *   <li>It has an observation record with an ID that doesn't match the expected ID</li>
	 * </ul>
	 *
	 * @param addr The node's socket address (must not be null).
	 * @param expected The expected node ID (maybe null if no ID is expected).
	 * @return true if the node is suspicious, false otherwise.
	 * @throws NullPointerException if address is null.
	 */
	boolean isSuspicious(SocketAddress addr, Id expected);

	/**
	 * Checks if a node at the given address is either under observation or banned.
	 *
	 * @param addr The node's socket address (must not be null).
	 * @return true if the node is observed or banned, false otherwise.
	 * @throws NullPointerException if address is null.
	 */
	boolean isSuspicious(SocketAddress addr);

	/**
	 * Checks if a host is currently banned.
	 *
	 * @param host The host address to check (must not be null).
	 * @return true if the host is banned, false otherwise.
	 * @throws NullPointerException if host is null.
	 */
	boolean isBanned(String host);

	/**
	 * Checks if a address is currently banned.
	 *
	 * @param addr The socket address to check (must not be null).
	 * @return true if the host is banned, false otherwise.
	 * @throws NullPointerException if addr is null.
	 */
	default boolean isBanned(SocketAddress addr) {
		return isBanned(addr.hostAddress());
	}

	Id lastKnownId(SocketAddress addr);

	/**
	 * Records an observation of a node.
	 *
	 * @param addr The node's socket address (must not be null).
	 * @param id The node ID observed.
	 * @throws NullPointerException if address is null.
	 */
	void observe(SocketAddress addr, Id id);

	/**
	 * Records an observation of a node that sent a malformed message.
	 *
	 * <p>This method should be called when a node sends messages that cannot be properly
	 * parsed or violate the protocol specification.</p>
	 *
	 * @param addr The node's socket address (must not be null).
	 * @throws NullPointerException if address is null.
	 */
	void malformedMessage(SocketAddress addr);

	/**
	 * Records an observation of a node that inconsistent id or address.
	 *
	 * <p>This method should be called when a node has an inconsistent id or address.</p>
	 *
	 * @param addr The node's socket address (must not be null).
	 * @param id The node ID observed.
	 * @throws NullPointerException if address is null.
	 */
	void inconsistent(SocketAddress addr, Id id);

	/**
	 * Returns the number of nodes currently under observation.
	 *
	 * @return the count of observed nodes
	 */
	long getObservedSize();

	/**
	 * Returns the number of hosts currently banned.
	 *
	 * @return the count of banned hosts
	 */
	long getBannedSize();

	/**
	 * Removes expired entries and promotes nodes to the suspicious list if they exceed the hit threshold.
	 *
	 * <p><strong>Important:</strong> This method should be called periodically (recommended: every 2 minutes)
	 * to maintain the detector's state and prevent memory leaks.</p>
	 */
	void purge();

	/**
	 * Removes all observed and banned nodes.
	 */
	void clear();
}