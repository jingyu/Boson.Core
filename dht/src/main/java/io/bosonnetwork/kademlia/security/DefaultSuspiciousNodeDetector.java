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

package io.bosonnetwork.kademlia.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.vertx.core.net.SocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;

/**
 * Detect and manages suspicious nodes in a Kademlia DHT network by monitoring inconsistent node IDs
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
public class DefaultSuspiciousNodeDetector implements SuspiciousNodeDetector {
	private static final int SUSPICIOUS_OBSERVATION_HITS = 8;
	private static final int SUSPICIOUS_HITS_THRESHOLD = 32;
	private static final long DEFAULT_OBSERVATION_PERIOD = 15 * 60 * 1000;
	private static final long DEFAULT_BAN_DURATION = 30 * 60 * 1000;

	private final long observationPeriod;
	private final int observationHitThreshold;
	private final long banDuration;

	private final Map<SocketAddress, ObservationRecord> observedNodes;
	private final Map<String, Long> bannedNodes;

	private static final Logger log = LoggerFactory.getLogger(DefaultSuspiciousNodeDetector.class);

	/**
	 * Represents the type of suspicious behavior observed from a node.
	 */
	enum SuspiciousActivity {
		/** Indicates no suspicious behavior observed. */
		NONE,
		/** Indicates an inconsistent node ID or address. */
		INCONSISTENT,
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
			this.hits = activity == SuspiciousActivity.NONE ? 0 : 1;
			this.expirationTime = expiration;
		}
	}

	/**
	 * Constructs a detector with custom observation and ban parameters.
	 *
	 * @param observationPeriod Duration (in milliseconds) to observe a node before resetting or banning.
	 * @param observationHitThreshold Number of suspicious events required to ban a node.
	 * @param banDuration Duration (in milliseconds) a node remains banned after detection.
	 * @throws IllegalArgumentException if any parameter is non-positive.
	 */
	protected DefaultSuspiciousNodeDetector(long observationPeriod, int observationHitThreshold, long banDuration) {
		if (observationPeriod <= 0 || observationHitThreshold <= 0 || banDuration <= 0)
			throw new IllegalArgumentException("Observation period, hits, and ban duration must be positive");

		this.observationPeriod = observationPeriod;
		this.observationHitThreshold = observationHitThreshold;
		this.banDuration = banDuration;

		observedNodes = new HashMap<>();
		bannedNodes = new HashMap<>();
	}

	/**
	 * Constructs a detector with default parameters: 10 hits, 15-minute observation period,
	 * and 30-minute ban duration.
	 */
	protected DefaultSuspiciousNodeDetector() {
		this(DEFAULT_OBSERVATION_PERIOD, SUSPICIOUS_HITS_THRESHOLD, DEFAULT_BAN_DURATION);
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
	@Override
	public boolean isSuspicious(SocketAddress addr, Id expected) {
		if (bannedNodes.containsKey(addr.hostAddress()))
			return true;

		ObservationRecord ob = observedNodes.get(addr);
		if (ob == null)
			return false;

		if (expected != null)
			return !expected.equals(ob.lastId);
		else
			return ob.hits >= SUSPICIOUS_OBSERVATION_HITS;
	}

	/**
	 * Checks if a node at the given address is either under observation or banned.
	 *
	 * @param addr The node's socket address (must not be null).
	 * @return true if the node is observed or banned, false otherwise.
	 * @throws NullPointerException if address is null.
	 */
	@Override
	public boolean isSuspicious(SocketAddress addr) {
		return isSuspicious(addr, null);
	}

	/**
	 * Checks if a host is currently banned.
	 *
	 * @param host The host address to check (must not be null).
	 * @return true if the host is banned, false otherwise.
	 * @throws NullPointerException if host is null.
	 */
	@Override
	public boolean isBanned(String host) {
		return bannedNodes.containsKey(host);
	}

	@Override
	public Id lastKnownId(SocketAddress addr) {
		ObservationRecord ob = observedNodes.get(addr);
		return ob == null ? null : ob.lastId;
	}

	/**
	 * Records an observation of a node with an inconsistent ID.
	 *
	 * @param addr The node's socket address (must not be null).
	 * @param id The node ID observed.
	 * @throws NullPointerException if address is null.
	 */
	@Override
	public void observe(SocketAddress addr, Id id) {
		observe(addr, id, SuspiciousActivity.NONE);
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
	@Override
	public void malformedMessage(SocketAddress addr) {
		observe(addr, null, SuspiciousActivity.MALFORMED_MESSAGE);
	}

	/**
	 * Records an observation of a node that inconsistent id or address.
	 *
	 * <p>This method should be called when a node has an inconsistent id or address.</p>
	 *
	 * @param addr The node's socket address (must not be null).
	 * @param id The node ID observed.
	 * @throws NullPointerException if address is null.
	 */
	@Override
	public void inconsistent(SocketAddress addr, Id id) {
		observe(addr, id, SuspiciousActivity.INCONSISTENT);
	}

	/**
	 * Record an observation for a node.
	 *
	 * @param addr The node's socket address.
	 * @param id The node ID (maybe null for malformed messages).
	 * @param activity The activity of the observation.
	 */
	private void observe(SocketAddress addr, Id id, SuspiciousActivity activity) {
		if (isBanned(addr.hostAddress()))
			return;

		long now = System.currentTimeMillis();
		observedNodes.compute(addr, (unused, ob) -> {
			if (ob == null) {
				log.trace("New observation for {}: id={}, activity={}", addr, id, activity);
				return new ObservationRecord(id, activity,  now + observationPeriod);
			} else {
				if (activity != SuspiciousActivity.NONE || !Objects.equals(id, ob.lastId))
					ob.hits++;

				if (ob.hits >= observationHitThreshold) {
					log.info("Node at {} marked suspicious: activity={}, hits={}", addr.hostAddress(), activity, ob.hits);
					banNode(addr.hostAddress(), now + banDuration);
					return null;
				} else {
					ob.lastActivity = activity;
					ob.lastId = id;
					ob.expirationTime = now + observationPeriod;

					log.trace("Updated observation for address {}: id={}, state={}, hits={}", addr, id, activity, ob.hits);
					return ob;
				}
			}
		});

		if (activity != SuspiciousActivity.NONE) {
			String host = addr.hostAddress();
			SocketAddress hostAddress = SocketAddress.inetSocketAddress(0, host);
			observedNodes.compute(hostAddress, (unused, ob) -> {
				if (ob == null) {
					log.trace("New observation for host {}: activity={}", host, activity);
					return new ObservationRecord(null, activity,  now + observationPeriod);
				} else {
					ob.hits++;
					if (ob.hits >= observationHitThreshold) {
						log.info("Host {} marked suspicious: activity={}, hits={}", host, activity, ob.hits);
						banNode(host, now + banDuration);
						return null;
					} else {
						ob.lastActivity = activity;
						ob.expirationTime = now + observationPeriod;

						log.trace("Updated observation for host {}: state={}, hits={}", host, activity, ob.hits);
						return ob;
					}
				}
			});

			if (id != null) {
				List<SocketAddress> addresses = new ArrayList<>(8);
				for (Map.Entry<SocketAddress, ObservationRecord> entry : observedNodes.entrySet()) {
					if (entry.getValue().lastId != null && entry.getValue().lastId.equals(id))
						addresses.add(entry.getKey());
				}

				if (addresses.size() >= SUSPICIOUS_OBSERVATION_HITS) {
					addresses.forEach(a -> {
						log.info("Id {} marked suspicious, ban related host {}", id, host);
						observedNodes.remove(a);
						observedNodes.remove(SocketAddress.inetSocketAddress(0, a.hostAddress()));
						banNode(a.hostAddress(), now + banDuration);
					});
				}
			}
		}
	}

	private void banNode(String host, long expirationTime) {
		bannedNodes.compute(host, (h, exp) -> {
			if (exp == null)
				log.info("Promote the marked node {} to suspicious node", host);
			else
				log.debug("Extended suspicious for host {}", host);

			return expirationTime;
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
	 * to maintain the detector's state and prevent memory leaks.</p>
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
	}

	/**
	 * Removes all observed and banned nodes.
	 */
	public void clear() {
		observedNodes.clear();
		bannedNodes.clear();
	}

	/**
	 * Returns a string representation of the detector’s state, including observed and suspicious nodes.
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
}