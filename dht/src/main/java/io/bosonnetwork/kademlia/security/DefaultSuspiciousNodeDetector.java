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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.vertx.core.net.SocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;

/**
 * Detects and manages suspicious nodes by monitoring inconsistent node IDs and malformed messages.
 * Sources are observed for a specified period and suppressed when they exceed a hit threshold.
 *
 * <p><strong>Two tiers, because the source address of a UDP packet is not verified.</strong> A sender
 * chooses what to put in the IP header, so any counter keyed on the source address can be aimed at a third
 * party. This class therefore separates what it can attribute from what it cannot, and gives them different
 * powers:</p>
 * <ul>
 *   <li><b>Unproven</b> ({@link #malformedMessage}, {@link #inconsistent}, {@link #observed}) - the packet
 *       arrived unsolicited, or failed before anything about it could be checked. It may still suppress the
 *       source, but briefly: {@code suppressionDuration}, doubling for each repeat inside one observation
 *       period, capped. Holding a source down therefore costs sustained traffic rather than one burst, and a
 *       source suppressed by mistake recovers on its own.</li>
 *   <li><b>Proven</b> ({@link #misbehaved}) - the packet answered a call this node made, from the address
 *       that call was sent to. Nobody else can put traffic there, so this cannot be aimed, and it earns the
 *       full {@code banDuration}.</li>
 * </ul>
 *
 * <p>The distinction is about <em>proof of source</em>, not about severity. A malformed packet may well be
 * an attack, but it is an attack we cannot attribute, and punishing an unattributable event as if it were
 * attributable is what lets one sender silence another. A long ban from a short burst is leverage: it is
 * worth far more to whoever aims it than it costs to produce.</p>
 *
 * <p><strong>Accounting unit:</strong> sources are counted per {@link SourceKey} - IPv4 /32, IPv6 /64 - so
 * that a sender holding one IPv6 allocation cannot draw an unlimited supply of fresh budgets.</p>
 *
 * <p><strong>What is counted, and why it is the address.</strong> Node ids are free Ed25519 keypairs, so
 * nothing can be limited by counting ids - an attacker mints more. An address is a resource somebody had to
 * acquire. So the budgets are charged to the source, including the Sybil budget itself: {@link #observed}
 * charges a source for each new identity it presents, which is the one place the free resource is
 * deliberately measured against the costly one.</p>
 *
 * <p><strong>Thread Safety:</strong> This class is designed for single-threaded use and is NOT thread-safe.
 * It should be used in a single-threaded environment or externally synchronized if used in a
 * multithreaded context.</p>
 *
 * <p>Bans and observations expire lazily on read ({@link #isBanned(String)}), so their accuracy does not
 * depend on how often {@link #purge()} runs. {@link #purge()} only reclaims memory by dropping
 * already-expired entries; calling it roughly every minute is sufficient.</p>
 */
public class DefaultSuspiciousNodeDetector implements SuspiciousNodeDetector {
	private static final int SUSPICIOUS_OBSERVATION_HITS = 8;
	private static final int SUSPICIOUS_HITS_THRESHOLD = 32;
	private static final long DEFAULT_OBSERVATION_PERIOD = 15 * 60 * 1000;
	private static final long DEFAULT_BAN_DURATION = 30 * 60 * 1000;
	private static final long DEFAULT_SUPPRESSION_DURATION = 60 * 1000;

	/**
	 * Ceiling on the escalated suppression of an unproven source, as a multiple of the base duration.
	 * <p>
	 * Three doublings: base, 2x, 4x, 8x. Enough that sustained abuse converges on something close to a ban,
	 * while a source suppressed by mistake is never held for the order of time a proven ban lasts. Without a
	 * ceiling the escalation is just a slower path back to the leverage this tiering exists to remove.
	 * </p>
	 */
	private static final int MAX_SUPPRESSION_ESCALATION = 8;

	/**
	 * Caps on the two tables.
	 * <p>
	 * An unproven record is cheap to create by design - that is the point of the tier - so the observation
	 * table is exactly the thing a sender can grow without limit, one entry per source it names. Expiry
	 * alone does not bound it: at any interesting packet rate the arrivals inside one observation period
	 * outrun it. Both tables are therefore hard-capped and evict least-recently-used.
	 * </p>
	 * <p>
	 * Eviction fails open - a dropped record forgets hits, a dropped ban lifts early. That is the correct
	 * direction to fail: forgetting costs this node some enforcement, whereas the alternative to eviction is
	 * unbounded memory on the event loop.
	 * </p>
	 * <p>
	 * Package-private rather than private so the test that proves the cap engages can derive its input from
	 * it. A test that hardcodes the number instead either fails when the cap is retuned, or - worse - keeps
	 * passing while feeding fewer sources than the cap and proving nothing.
	 * </p>
	 */
	static final int MAX_OBSERVED_SOURCES = 8192;
	static final int MAX_BANNED_SOURCES = 2048;

	private final long observationPeriod;
	private final int observationHitThreshold;
	private final long banDuration;
	private final long suppressionDuration;

	private final Map<String, ObservationRecord> observedNodes;
	private final Map<String, Long> bannedNodes;
	/**
	 * The last id seen at each {@code ip:port}, for identity-churn accounting only.
	 * <p>
	 * Keyed on the endpoint rather than on the accountable source, and that difference is the whole point.
	 * Aggregating ports away is right for rate - a port costs nothing, so it must not buy a fresh budget -
	 * but it is wrong for identity, because peers that share an egress address are not one peer. Several
	 * nodes behind one NAT, one datacenter host, or inside one IPv6 /64 legitimately present many different
	 * ids from a single source, and counting that as churn suppresses an entire neighborhood.
	 * </p>
	 * <p>
	 * So the question asked here is the narrow one: did the identity at <em>this endpoint</em> change? A
	 * sender that also varies its port escapes it, exactly as it escaped the per-{@code ip:port} tracking
	 * this replaces - but a varied port yields no routing-table position either, so there is nothing gained
	 * by the evasion.
	 * </p>
	 */
	private final Map<String, Id> endpointIds;

	private static final Logger log = LoggerFactory.getLogger(DefaultSuspiciousNodeDetector.class);

	/**
	 * Represents the type of suspicious behavior observed from a node.
	 * <p>
	 * {@link #INCONSISTENT} and {@link #IDENTITY_CHURN} are the same instability seen from opposite sides,
	 * and the difference decides what the node does about it:
	 * </p>
	 * <ul>
	 *   <li>{@code INCONSISTENT} - <i>same id, wrong address</i>. A response came from somewhere other than
	 *       where the call was sent, or the routing table holds this id at a different address. Honest
	 *       cause: a NAT rebinding. Counts against the source and nothing more.</li>
	 *   <li>{@code IDENTITY_CHURN} - <i>same address, different id</i>. Honest cause: a node restarting
	 *       under a new key. This one also costs the stale entry its place in the routing table, because a
	 *       binding that changes is not the long-lived contact the table exists to hold.</li>
	 * </ul>
	 */
	enum SuspiciousActivity {
		/** Indicates a node id arriving from an address that contradicts what is already known. */
		INCONSISTENT,
		/** Indicates a malformed message received from the node. */
		MALFORMED_MESSAGE,
		/** Indicates an endpoint presenting a different node id than it presented before. */
		IDENTITY_CHURN
	}

	static class ObservationRecord {
		/**
		 * The last id this source presented in a <em>proven</em> observation. Only the same-id scan reads
		 * this, and it is kept separate from {@link DefaultSuspiciousNodeDetector#endpointIds} for exactly that reason: a field an unverified
		 * sender can write is a field it can use to point the scan at somebody else.
		 */
		private Id lastId;
		private SuspiciousActivity lastActivity;
		private int hits;
		private int escalation;
		private long expirationTime;

		public ObservationRecord(long expiration) {
			this.hits = 0;
			this.escalation = 0;
			this.expirationTime = expiration;
		}
	}

	/**
	 * A least-recently-used map with a hard capacity.
	 * <p>
	 * Access order rather than insertion order: a source that keeps offending should outlive a flood of
	 * sources seen once, which is the opposite of what insertion order would do.
	 * </p>
	 *
	 * @param <V> the value type.
	 */
	private static class BoundedMap<V> extends LinkedHashMap<String, V> {
		private static final long serialVersionUID = 1L;

		private final int capacity;

		BoundedMap(int capacity) {
			super(16, 0.75f, true);
			this.capacity = capacity;
		}

		@Override
		protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
			return size() > capacity;
		}
	}

	/**
	 * Constructs a detector with custom observation and ban parameters.
	 *
	 * @param observationPeriod Duration (in milliseconds) to observe a node before resetting or banning.
	 * @param observationHitThreshold Number of suspicious events required to act on a source.
	 * @param banDuration Duration (in milliseconds) a node remains banned after proven misbehavior.
	 * @param suppressionDuration Base duration (in milliseconds) an unproven source is suppressed for,
	 *        doubling on each repeat within one observation period.
	 * @throws IllegalArgumentException if any parameter is non-positive.
	 */
	protected DefaultSuspiciousNodeDetector(long observationPeriod, int observationHitThreshold,
			long banDuration, long suppressionDuration) {
		if (observationPeriod <= 0 || observationHitThreshold <= 0 || banDuration <= 0 || suppressionDuration <= 0)
			throw new IllegalArgumentException("Observation period, hits, ban and suppression durations must be positive");

		this.observationPeriod = observationPeriod;
		this.observationHitThreshold = observationHitThreshold;
		this.banDuration = banDuration;
		this.suppressionDuration = suppressionDuration;

		observedNodes = new BoundedMap<>(MAX_OBSERVED_SOURCES);
		bannedNodes = new BoundedMap<>(MAX_BANNED_SOURCES);
		endpointIds = new BoundedMap<>(MAX_OBSERVED_SOURCES);
	}

	/**
	 * Constructs a detector with default parameters: 32 hits, 15-minute observation period, 30-minute ban
	 * for proven misbehavior and a 1-minute base suppression for unproven sources.
	 */
	protected DefaultSuspiciousNodeDetector() {
		this(DEFAULT_OBSERVATION_PERIOD, SUSPICIOUS_HITS_THRESHOLD, DEFAULT_BAN_DURATION, DEFAULT_SUPPRESSION_DURATION);
	}

	/**
	 * Checks if a host is currently suppressed or banned.
	 *
	 * @param host The host address to check (must not be null).
	 * @return true if the host is banned, false otherwise.
	 * @throws NullPointerException if host is null.
	 */
	@Override
	public boolean isBanned(String host) {
		// Lazy expiry: a ban stops taking effect at its deadline regardless of when purge() runs,
		// so ban accuracy does not depend on the purge interval. purge() only reclaims memory.
		Long expiration = bannedNodes.get(SourceKey.of(host));
		return expiration != null && System.currentTimeMillis() < expiration;
	}

	/**
	 * Records a malformed message from an unverified source.
	 *
	 * <p>This method should be called when a node sends messages that cannot be properly parsed or violate
	 * the protocol specification. The source is unproven - nothing about the packet establishes that the
	 * sender receives traffic at the address it claims - so this can only suppress, never ban.</p>
	 *
	 * @param addr The node's socket address (must not be null).
	 * @throws NullPointerException if address is null.
	 */
	@Override
	public void malformedMessage(SocketAddress addr) {
		observe(addr, null, SuspiciousActivity.MALFORMED_MESSAGE, false);
	}

	/**
	 * Records an inconsistent id or address from an unverified source.
	 *
	 * @param addr The node's socket address (must not be null).
	 * @param id The node ID observed.
	 * @throws NullPointerException if address is null.
	 */
	@Override
	public void inconsistent(SocketAddress addr, Id id) {
		observe(addr, id, SuspiciousActivity.INCONSISTENT, false);
	}

	/**
	 * Records misbehavior by a node that answered a call this node made, from the address that call was
	 * sent to.
	 *
	 * <p>That round trip is what separates this from {@link #inconsistent}: the address is not merely
	 * claimed, it demonstrably receives our traffic, so the evidence cannot have been aimed at a third
	 * party by a sender forging its source. Only this entry point can produce a full ban.</p>
	 *
	 * @param addr The node's socket address (must not be null).
	 * @param id The node ID observed.
	 * @throws NullPointerException if address is null.
	 */
	@Override
	public void misbehaved(SocketAddress addr, Id id) {
		observe(addr, id, SuspiciousActivity.INCONSISTENT, true);
	}

	/**
	 * Record an observation for a source.
	 *
	 * @param addr The node's socket address.
	 * @param id The node ID (maybe null for malformed messages).
	 * @param activity The activity of the observation.
	 * @param proven Whether the source address was demonstrated by a completed round trip.
	 */
	private void observe(SocketAddress addr, Id id, SuspiciousActivity activity, boolean proven) {
		String source = SourceKey.of(addr.hostAddress());
		if (isBannedSource(source))
			return;

		long now = System.currentTimeMillis();
		observedNodes.compute(source, (unused, ob) -> {
			if (ob == null) {
				log.trace("New observation for {}: id={}, activity={}, proven={}", source, id, activity, proven);
				ob = new ObservationRecord(now + observationPeriod);
			}

			ob.lastActivity = activity;
			// Only a proven observation may write the id the same-id scan reads.
			if (proven && id != null)
				ob.lastId = id;

			hit(source, ob, now, proven, activity);
			ob.expirationTime = now + observationPeriod;
			return ob;
		});

		if (proven && id != null)
			banSourcesClaiming(id, now);
	}

	/**
	 * Records that a source presented a node id, counting a hit only when the id changed.
	 * <p>
	 * This is the Sybil budget, and it is the one place where counting the free resource against the costly
	 * one is the right thing to do: node ids are free Ed25519 keypairs, so an attacker mints as many as it
	 * likes, but it has to present them from somewhere. Charging id churn to the source puts a ceiling on
	 * how many identities one source can rotate through before it is told to slow down.
	 * </p>
	 * <p>
	 * Detection is per endpoint, the charge is per source - see {@link DefaultSuspiciousNodeDetector#endpointIds} for why the two must
	 * differ. Unproven, so it can only suppress: the address a request arrives from is chosen by whoever
	 * sent it, and this is reachable from unsolicited requests. Note the churn id is deliberately not the id
	 * the same-id scan reads - see {@link ObservationRecord#lastId}.
	 * </p>
	 *
	 * @param addr The node's socket address (must not be null).
	 * @param id The node ID observed.
	 * @return the id this endpoint presented before, or null if it is consistent with what it presented
	 *         last time, if this is its first sighting, or if the source is already suppressed.
	 */
	@Override
	public Id observed(SocketAddress addr, Id id) {
		if (id == null)
			return null;

		String source = SourceKey.of(addr.hostAddress());
		if (isBannedSource(source))
			return null;

		Id previous = endpointIds.put(addr.hostAddress() + ':' + addr.port(), id);
		// Nothing to compare against on a first sighting, and a stable identity is the normal case.
		if (previous == null || previous.equals(id))
			return null;

		log.trace("Endpoint {} changed id: {} -> {}", addr, previous, id);

		long now = System.currentTimeMillis();
		observedNodes.compute(source, (unused, ob) -> {
			if (ob == null)
				ob = new ObservationRecord(now + observationPeriod);

			ob.lastActivity = SuspiciousActivity.IDENTITY_CHURN;
			hit(source, ob, now, false, SuspiciousActivity.IDENTITY_CHURN);
			ob.expirationTime = now + observationPeriod;
			return ob;
		});

		return previous;
	}

	/**
	 * Counts one hit against a source and acts if it has reached the threshold.
	 *
	 * @param source the accountable source.
	 * @param ob the source's observation record.
	 * @param now the current time in milliseconds.
	 * @param proven whether the source address was demonstrated by a completed round trip.
	 * @param activity the activity being counted.
	 */
	private void hit(String source, ObservationRecord ob, long now, boolean proven, SuspiciousActivity activity) {
		if (++ob.hits < observationHitThreshold)
			return;

		if (proven) {
			log.info("Node at {} marked suspicious: activity={}, hits={}", source, activity, ob.hits);
			banSource(source, now + banDuration);
		} else {
			// Escalate before use so the first suppression is one base duration, not two.
			ob.escalation = ob.escalation == 0 ? 1 : Math.min(ob.escalation * 2, MAX_SUPPRESSION_ESCALATION);
			long duration = suppressionDuration * ob.escalation;
			log.info("Source {} suppressed for {}ms: activity={}, hits={}", source, duration, activity, ob.hits);
			banSource(source, now + duration);
		}

		// Keep the record, reset the hits. The record is what remembers the escalation level, and it has to
		// outlive the suppression it caused or every repeat would start again from the base duration. It
		// expires with the observation period like any other, so a source that goes quiet for one period is
		// forgiven completely.
		ob.hits = 0;
	}

	/**
	 * Bans every proven source that presents the given id, once enough of them do.
	 * <p>
	 * One id answering from several addresses that all demonstrably receive our traffic is a real signal -
	 * it is one operator running the same identity across a fleet, positioning it in the routing tables.
	 * The scan only sees ids written by proven observations, which is what keeps it from being pointed at
	 * anyone: an unverified sender cannot put its chosen id on someone else's address.
	 * </p>
	 *
	 * @param id the id observed.
	 * @param now the current time in milliseconds.
	 */
	private void banSourcesClaiming(Id id, long now) {
		List<String> sources = new ArrayList<>(SUSPICIOUS_OBSERVATION_HITS);
		for (Map.Entry<String, ObservationRecord> entry : observedNodes.entrySet()) {
			if (id.equals(entry.getValue().lastId))
				sources.add(entry.getKey());
		}

		if (sources.size() < SUSPICIOUS_OBSERVATION_HITS)
			return;

		for (String source : sources) {
			log.info("Id {} marked suspicious, ban related source {}", id, source);
			observedNodes.remove(source);
			banSource(source, now + banDuration);
		}
	}

	private boolean isBannedSource(String source) {
		Long expiration = bannedNodes.get(source);
		return expiration != null && System.currentTimeMillis() < expiration;
	}

	private void banSource(String source, long expirationTime) {
		bannedNodes.compute(source, (h, exp) -> {
			if (exp == null) {
				log.info("Promote the marked node {} to suspicious node", source);
				return expirationTime;
			}

			log.debug("Extended suspicious for source {}", source);
			// Never shorten. A brief suppression of an unproven source must not cut short a ban that proven
			// misbehavior earned, and the two tiers reach this method from independent paths.
			return Math.max(exp, expirationTime);
		});
	}

	/**
	 * Returns the number of sources currently under observation.
	 *
	 * @return the count of observed sources
	 */
	@Override
	public long getObservedSize() {
		return observedNodes.size();
	}

	/**
	 * Returns the number of sources currently banned or suppressed.
	 *
	 * @return the count of banned sources
	 */
	@Override
	public long getBannedSize() {
		return bannedNodes.size();
	}

	/**
	 * Removes expired entries.
	 *
	 * <p><strong>Important:</strong> This method should be called periodically (recommended: every minute)
	 * to reclaim memory. It is <em>not</em> required for ban/observation accuracy - those expire lazily on
	 * read - so the exact interval only trades memory footprint against scan frequency. It is also not what
	 * bounds either table; the capacity caps do that.</p>
	 */
	@Override
	public void purge() {
		long now = System.currentTimeMillis();

		// Remove expired observed entries
		observedNodes.entrySet().removeIf(entry -> {
			boolean expired = now > entry.getValue().expirationTime;
			if (expired)
				log.debug("Removed expired observation for source {}", entry.getKey());
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
	@Override
	public void clear() {
		observedNodes.clear();
		bannedNodes.clear();
		endpointIds.clear();
	}

	/**
	 * Returns a string representation of the detector's state, including observed and suspicious nodes.
	 *
	 * @return A formatted string with details of observed and suspicious nodes.
	 */
	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder(96 + observedNodes.size() + 64 * bannedNodes.size() + 32);
		long now = System.currentTimeMillis();

		if (!observedNodes.isEmpty()) {
			repr.append("Observed[").append(observedNodes.size()).append("]:\n");
			observedNodes.forEach((source, ob) ->
					repr.append("  ").append(source).append(", ")
							.append(ob.lastActivity).append(", ")
							.append(ob.hits).append(", ")
							.append(Duration.ofMillis(ob.expirationTime - now)).append("\n"));
		}

		if (!bannedNodes.isEmpty()) {
			repr.append("Banned[").append(bannedNodes.size()).append("]:\n");
			bannedNodes.forEach((source, exp) ->
					repr.append("  ").append(source).append(", ").append(Duration.ofMillis(exp - now)).append("\n"));
			repr.append("\n");
		}

		return repr.isEmpty() ? "Empty" : repr.toString();
	}
}
