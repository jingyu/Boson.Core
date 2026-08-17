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
 * Detects and manages suspicious nodes in a Kademlia DHT network by monitoring inconsistent node IDs
 * and malformed messages. Sources are observed for a specified period and acted on when they exceed a
 * configurable hit threshold.
 *
 * <p><strong>Accounting unit.</strong> Every method here takes an address but counts a <em>source</em>: an
 * IPv4 /32 or an IPv6 /64. A port is free to change and an IPv6 allocation hands out addresses by the
 * billion, so neither can be allowed to buy a fresh budget. One consequence is worth stating outright: a
 * ban earned by one address applies to every address in its /64.</p>
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
 * <p>Usage note: {@link #purge()} should be called periodically - the node schedules it every minute - to
 * reclaim memory. Nothing about enforcement depends on it; see the method for what it does and does not
 * do.</p>
 */
public interface SuspiciousNodeDetector {
	/**
	 * Constructs a detector with custom observation, ban and suppression parameters.
	 *
	 * @param observationPeriod Duration (in milliseconds) a source's accumulated hits and escalation level
	 *        survive without further activity. Reaching the end of a quiet period forgives a source
	 *        completely; it is not itself a deadline at which anything is banned.
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
	 *
	 * @see #create(long, int, long, long)
	 */
	static SuspiciousNodeDetector create() {
		return new DefaultSuspiciousNodeDetector();
	}

	/**
	 * Returns a detector that observes nothing and bans nobody.
	 *
	 * <p>For deployments that police their own membership some other way, and for tests that would
	 * otherwise have to keep their traffic under the thresholds.</p>
	 */
	static SuspiciousNodeDetector disabled() {
		return new DisabledSuspiciousNodeDetector();
	}

	/**
	 * Checks whether a source is currently banned or suppressed.
	 *
	 * <p>One answer covers both tiers: a caller deciding whether to drop a packet has no use for the
	 * difference between a proven ban and an unproven suppression, only for whether this source is
	 * currently held. The host is reduced to its accounting unit first, so an address that has never
	 * been seen reads as banned when its /64 is.</p>
	 *
	 * @param host The host address to check (must not be null).
	 * @return true if the source is currently banned or suppressed, false otherwise.
	 * @throws NullPointerException if host is null.
	 */
	boolean isBanned(String host);

	/**
	 * Checks whether an address's source is currently banned or suppressed.
	 *
	 * <p>The overload to prefer where an address is already at hand: it settles once, here, which of the
	 * accessors names the source, so that callers cannot disagree about it.</p>
	 *
	 * @param addr The socket address to check (must not be null).
	 * @return true if the source is currently banned or suppressed, false otherwise.
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
	 * @return the id this endpoint presented before, or null if it is unchanged, newly seen, or the source
	 *         is already banned. A non-null result names a binding the caller has reason to stop trusting.
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
	 * <p>Diagnostics and tests only - no decision is taken on these counts. They are the only way to
	 * observe that the tables are bounded, which is a property no behavioural assertion can reach.</p>
	 *
	 * @return the count of observed sources, including any whose observation period has elapsed but that
	 *         {@link #purge()} has not yet reclaimed.
	 */
	long getObservedSize();

	/**
	 * Returns the number of sources currently banned or suppressed.
	 *
	 * <p>Diagnostics and tests only, as with {@link #getObservedSize()}. Note that this counts table
	 * entries rather than sources being held: expiry is lazy, so an entry outlives the ban it records
	 * until the next purge, and this count can exceed the number of sources {@link #isBanned(String)}
	 * would turn away.</p>
	 *
	 * @return the count of banned entries.
	 */
	long getBannedSize();

	/**
	 * Removes already-expired entries.
	 *
	 * <p>Memory only. Bans and observations expire lazily on read, so enforcement is exact whatever the
	 * purge interval is - and the tables are bounded by their capacity caps rather than by this. What the
	 * interval actually trades is footprint against scan cost; the node calls it every minute.</p>
	 */
	void purge();

	/**
	 * Removes all observed and banned nodes.
	 */
	void clear();
}
