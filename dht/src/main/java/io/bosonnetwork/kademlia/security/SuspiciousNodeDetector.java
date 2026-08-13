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
 * and malformed messages. Sources are observed for a specified period and acted on when they exceed a
 * configurable hit threshold.
 *
 * <p>Callers must choose an entry point by <strong>what the packet proved about its source</strong>, not by
 * how bad the behavior looked. A UDP source address is chosen by the sender, so a counter keyed on it can
 * be aimed at a third party by anyone willing to forge one:</p>
 * <ul>
 *   <li>{@link #malformedMessage} and {@link #inconsistent} are for packets that arrived unsolicited or
 *       failed before anything could be checked. The source is unproven, and these can only suppress it
 *       briefly.</li>
 *   <li>{@link #misbehaved} is for a packet that answered a call this node made, from the address that call
 *       was sent to. That address demonstrably receives our traffic, so the evidence cannot have been aimed,
 *       and only this entry point can produce a full ban.</li>
 * </ul>
 *
 * <p>Usage note: The {@link #purge()} method should be called periodically (e.g., every 2 minutes) to
 * remove expired entries.</p>
 */
public interface SuspiciousNodeDetector {
	/**
	 * Constructs a detector with custom observation, ban and suppression parameters.
	 *
	 * @param observationPeriod Duration (in milliseconds) to observe a node before resetting or banning.
	 * @param observationHitThreshold Number of suspicious events required to act on a source.
	 * @param banDuration Duration (in milliseconds) a node remains banned after proven misbehavior.
	 * @param suppressionDuration Base duration (in milliseconds) an unproven source is suppressed for,
	 *        doubling on each repeat within one observation period.
	 * @throws IllegalArgumentException if any parameter is non-positive.
	 */
	static SuspiciousNodeDetector create(long observationPeriod, int observationHitThreshold,
			long banDuration, long suppressionDuration) {
		return new DefaultSuspiciousNodeDetector(observationPeriod, observationHitThreshold, banDuration,
				suppressionDuration);
	}

	/**
	 * Constructs a detector with default parameters: 32 hits, 15-minute observation period, 30-minute ban
	 * for proven misbehavior and a 1-minute base suppression for unproven sources.
	 */
	static SuspiciousNodeDetector create() {
		return new DefaultSuspiciousNodeDetector();
	}

	static SuspiciousNodeDetector disabled() {
		return new DisabledSuspiciousNodeDetector();
	}

	/**
	 * Checks if a host is currently suppressed or banned.
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

	/**
	 * Records that a source presented a node id, counting against it only when the id changed.
	 *
	 * <p>This is the Sybil budget. Node ids are free to mint, so the only way to put a ceiling on how many
	 * identities one sender can rotate through is to charge the churn to the place it came from. Call this
	 * for every message accepted from a source.</p>
	 *
	 * <p>The source is unproven - unsolicited requests reach this - so it can only suppress, never ban.</p>
	 *
	 * <p>Churn is decided per endpoint but charged per source: several peers behind one NAT or inside one
	 * IPv6 /64 legitimately present different ids from one address, and only a change at the same
	 * {@code ip:port} means an identity actually moved.</p>
	 *
	 * @param addr The node's socket address (must not be null).
	 * @param id The node ID observed.
	 * @return the id this endpoint presented before, or null if it is unchanged or newly seen. A non-null
	 *         result names a binding the caller has reason to stop trusting.
	 * @throws NullPointerException if address is null.
	 */
	Id observed(SocketAddress addr, Id id);

	/**
	 * Records an observation of a node that sent a malformed message.
	 *
	 * <p>This method should be called when a node sends messages that cannot be properly
	 * parsed or violate the protocol specification.</p>
	 *
	 * <p>The source is unproven, so this can only suppress it briefly.</p>
	 *
	 * @param addr The node's socket address (must not be null).
	 * @throws NullPointerException if address is null.
	 */
	void malformedMessage(SocketAddress addr);

	/**
	 * Records an observation of a node that inconsistent id or address.
	 *
	 * <p>This method should be called when a node has an inconsistent id or address, and the packet that
	 * revealed it arrived without proving where it came from. The source is unproven, so this can only
	 * suppress it briefly.</p>
	 *
	 * @param addr The node's socket address (must not be null).
	 * @param id The node ID observed.
	 * @throws NullPointerException if address is null.
	 */
	void inconsistent(SocketAddress addr, Id id);

	/**
	 * Records misbehavior by a node that answered a call this node made, from the address that call was
	 * sent to.
	 *
	 * <p>Only call this where both halves hold: the message matched an outstanding call, and its source
	 * address equals the address that call was sent to. Together those mean the address receives our
	 * traffic, which a sender forging its source cannot arrange for someone else. That is what makes this
	 * the only entry point allowed to earn a full ban.</p>
	 *
	 * @param addr The node's socket address (must not be null).
	 * @param id The node ID observed.
	 * @throws NullPointerException if address is null.
	 */
	void misbehaved(SocketAddress addr, Id id);

	/**
	 * Returns the number of sources currently under observation.
	 *
	 * @return the count of observed sources
	 */
	long getObservedSize();

	/**
	 * Returns the number of sources currently banned or suppressed.
	 *
	 * @return the count of banned sources
	 */
	long getBannedSize();

	/**
	 * Removes expired entries.
	 *
	 * <p><strong>Important:</strong> This method should be called periodically (recommended: every 2 minutes)
	 * to reclaim memory. Ban accuracy does not depend on it - entries expire lazily on read.</p>
	 */
	void purge();

	/**
	 * Removes all observed and banned nodes.
	 */
	void clear();
}
