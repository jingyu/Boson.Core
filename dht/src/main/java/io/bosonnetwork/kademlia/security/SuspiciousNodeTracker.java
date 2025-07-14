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

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.vertx.core.net.SocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;

/**
 * Tracks and manages suspicious nodes in a Kademlia DHT network by monitoring inconsistent node IDs
 * and malformed messages. Nodes are observed for a specified period and marked as suspicious when
 * they exceed a configurable hit threshold. Suspicious nodes are banned for a configurable duration.
 *
 * <p><strong>Thread Safety:</strong> This class is designed for single-threaded use and is NOT thread-safe.
 * It should be used in a single-threaded environment or externally synchronized if used in a
 * multithreaded context.</p>
 *
 * <p>Usage note: The {@link #purge()} method should be called periodically (e.g., every 2 minutes) to
 * remove expired entries and promote nodes to the suspicious list.</p>
 */
public class SuspiciousNodeTracker {
	private static final int DEFAULT_OBSERVATION_HITS = 10;
	private static final long DEFAULT_OBSERVATION_PERIOD = TimeUnit.MINUTES.toMillis(15);
	private static final long DEFAULT_BAN_DURATION = TimeUnit.MINUTES.toMillis(30);

	private final long observationPeriod;
	private final int observationHitThreshold;
	private final long banDuration;

	private final Map<SocketAddress, ObservationRecord> observedNodes;
	private final Map<String, Long> bannedNodes;

	private static final Logger log = LoggerFactory.getLogger(SuspiciousNodeTracker.class);

	/**
	 * Represents the type of suspicious behavior observed from a node.
	 */
	enum SuspiciousActivity {
		/** Indicates an inconsistent node ID for the same address. */
		INCONSISTENT_ID,
		/** Indicates a malformed message received from the node. */
		MALFORMED_MESSAGE
	}

	static class ObservationRecord {
		private Id lastId;
		private SuspiciousActivity lastActivity;
		private int hits;
		private long expirationTime;

		public ObservationRecord(Id id, SuspiciousActivity activity, long expiration) {
			this.lastId = id;
			this.lastActivity = activity;
			this.hits = 1;
			this.expirationTime = expiration;
		}
	}

	/**
	 * Constructs a tracker with custom observation and ban parameters.
	 *
	 * @param observationPeriod Duration (in milliseconds) to observe a node before resetting or banning.
	 * @param observationHitThreshold Number of suspicious events required to ban a node.
	 * @param banDuration Duration (in milliseconds) a node remains banned after detection.
	 * @throws IllegalArgumentException if any parameter is non-positive.
	 */
	public SuspiciousNodeTracker(long observationPeriod, int observationHitThreshold, long banDuration) {
		if (observationPeriod <= 0 || observationHitThreshold <= 0 || banDuration <= 0)
			throw new IllegalArgumentException("Observation period, hits, and ban duration must be positive");

		this.observationPeriod = observationPeriod;
		this.observationHitThreshold = observationHitThreshold;
		this.banDuration = banDuration;

		observedNodes = new HashMap<>();
		bannedNodes = new HashMap<>();
	}

	/**
	 * Constructs a tracker with default parameters: 10 hits, 15-minute observation period,
	 * and 30-minute ban duration.
	 */
	public SuspiciousNodeTracker() {
		this(DEFAULT_OBSERVATION_PERIOD, DEFAULT_OBSERVATION_HITS, DEFAULT_BAN_DURATION);
	}

	public static SuspiciousNodeTracker disabled() {
		return new Disabled();
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
	public boolean isSuspicious(SocketAddress addr, Id expected) {
		if (bannedNodes.containsKey(addr.host()))
			return true;

		ObservationRecord ob = observedNodes.get(addr);
		if (ob == null)
			return false;

		if (expected != null)
			return !expected.equals(ob.lastId);
		else
			return true;
	}

	/**
	 * Checks if a node at the given address is either under observation or banned.
	 *
	 * @param addr The node's socket address (must not be null).
	 * @return true if the node is observed or banned, false otherwise.
	 * @throws NullPointerException if address is null.
	 */
	public boolean isSuspicious(SocketAddress addr) {
		return bannedNodes.containsKey(addr.host()) || observedNodes.containsKey(addr);
	}

	/**
	 * Checks if a host is currently banned.
	 *
	 * @param host The host address to check (must not be null).
	 * @return true if the host is banned, false otherwise.
	 * @throws NullPointerException if host is null.
	 */
	public boolean isBanned(String host) {
		return bannedNodes.containsKey(host);
	}

	/**
	 * Records an observation of a node with an inconsistent ID.
	 *
	 * <p>This method should be called when the same socket address is observed to have
	 * different node IDs across different interactions.</p>
	 *
	 * @param addr The node's socket address (must not be null).
	 * @param id The node ID observed.
	 * @throws NullPointerException if address is null.
	 */
	public void observe(SocketAddress addr, Id id) {
		observe(addr, id, SuspiciousActivity.INCONSISTENT_ID);
	}

	/**
	 * Records an observation of a node that sent a malformed message.
	 *
	 * <p>This method should be called when a node sends messages that cannot be properly
	 * parsed or violate the protocol specification.</p>
	 *
	 * @param addr The node's socket address (must not be null).
	 * @throws NullPointerException if address is null.
	 */
	public void observe(SocketAddress addr) {
		observe(addr, null, SuspiciousActivity.MALFORMED_MESSAGE);
	}

	/**
	 * Internal method to record an observation for a node.
	 *
	 * @param addr The node's socket address.
	 * @param id The node ID (maybe null for malformed messages).
	 * @param state The state of the observation (INCONSISTENT or MALFORMED_MESSAGE).
	 */
	private void observe(SocketAddress addr, Id id, SuspiciousActivity state) {
		long now = System.currentTimeMillis();

		observedNodes.compute(addr, (unused, ob) -> {
			if (ob == null) {
				log.debug("New observation for {}: id={}, state={}", addr, id, state);
				return new ObservationRecord(id, state,  now + observationPeriod);
			} else {
				ob.lastActivity = state;
				ob.hits++;
				ob.lastId = id;
				ob.expirationTime = now + (ob.hits >= observationHitThreshold ? banDuration : observationPeriod);

				log.debug("Updated observation for address {}: id={}, state={}, hits={}",
						addr, id, state, ob.hits);
				if (ob.hits >= observationHitThreshold)
					log.info("Node at {} marked suspicious: state={}, hits={}", addr.host(), state, ob.hits);

				return ob;
			}
		});
	}

	/**
	 * Returns the number of nodes currently under observation.
	 *
	 * @return the count of observed nodes
	 */
	public long getObservedSize() {
		return observedNodes.size();
	}

	/**
	 * Returns the number of hosts currently banned.
	 *
	 * @return the count of banned hosts
	 */
	public long getBannedSize() {
		return bannedNodes.size();
	}

	/**
	 * Removes expired entries and promotes nodes to the suspicious list if they exceed the hit threshold.
	 *
	 * <p><strong>Important:</strong> This method should be called periodically (recommended: every 2 minutes)
	 * to maintain the tracker's state and prevent memory leaks.</p>
	 */
	public void purge() {
		long now = System.currentTimeMillis();

		// Remove expired observed entries
		observedNodes.entrySet().removeIf(entry -> {
			boolean expired = now > entry.getValue().expirationTime;
			if (expired)
				log.debug("Removed expired observation for address {}", entry.getKey());
			return expired;
		});

		// Remove expired suspicious nodes
		bannedNodes.entrySet().removeIf(entry -> {
			boolean expired = now > entry.getValue();
			if (expired)
				log.debug("Removed expired suspicious node {}", entry.getKey());
			return expired;
		});

		// Promote nodes to suspicious.
		// But not remote from the observed list for continue to observe.
		for (Map.Entry<SocketAddress, ObservationRecord> entry : observedNodes.entrySet()) {
			ObservationRecord ob = entry.getValue();
			if (ob.hits >= observationHitThreshold) {
				String host = entry.getKey().host();
				bannedNodes.compute(host, (h, exp) -> {
					if (exp == null) {
						log.info("Promote the marked node {} to suspicious node", host);
						return ob.expirationTime;
					} else {
						log.debug("Extended suspicious for host {}", host);
						return Math.max(exp, ob.expirationTime);
					}
				});
			}
		}
	}

	/**
	 * Returns a string representation of the tracker’s state, including observed and suspicious nodes.
	 *
	 * @return A formatted string with details of observed and suspicious nodes.
	 */
	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder(96 + observedNodes.size() + 64 * bannedNodes.size() + 32);
		long now = System.currentTimeMillis();

		if (!observedNodes.isEmpty()) {
			repr.append("Observed[").append(observedNodes.size()).append("]:\n");
			observedNodes.forEach((addr, ob) ->
					repr.append("  ").append(addr).append(", ")
							.append(ob.lastActivity).append(", ")
							.append(ob.hits).append(", ")
							.append(Duration.ofMillis(ob.expirationTime - now)).append("\n"));
		}

		if (!bannedNodes.isEmpty()) {
			repr.append("Banned[").append(bannedNodes.size()).append("]:\n");
			bannedNodes.forEach((host, exp) ->
					repr.append("  ").append(host).append(", ").append(Duration.ofMillis(exp - now)).append("\n"));
			repr.append("\n");
		}

		return repr.isEmpty() ? "Empty" : repr.toString();
	}

	/**
	 * Disabled SuspiciousNodeTracker.
	 * @see SuspiciousNodeTracker
	 */
	private static class Disabled extends SuspiciousNodeTracker {
		private Disabled() {
			super();
		}

		@Override
		public boolean isSuspicious(SocketAddress addr, Id expected) {
			return false;
		}

		@Override
		public boolean isSuspicious(SocketAddress addr) {
			return false;
		}

		@Override
		public boolean isBanned(String host) {
			return false;
		}

		@Override
		public void observe(SocketAddress addr, Id id) {
		}

		@Override
		public void observe(SocketAddress addr) {
		}

		@Override
		public long getObservedSize() {
			return 0;
		}

		@Override
		public long getBannedSize() {
			return 0;
		}

		@Override
		public void purge() {
		}
	}
}