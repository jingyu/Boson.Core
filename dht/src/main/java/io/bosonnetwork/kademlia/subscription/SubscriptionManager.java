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

package io.bosonnetwork.kademlia.subscription;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import io.vertx.core.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.kademlia.exceptions.MessageTooBigException;
import io.bosonnetwork.kademlia.exceptions.SubscriptionRefusedException;
import io.bosonnetwork.kademlia.exceptions.UnknownSubscriptionException;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.protocol.SubscriptionKind;
import io.bosonnetwork.kademlia.rpc.CallRejectedException;
import io.bosonnetwork.kademlia.rpc.RpcCall;
import io.bosonnetwork.kademlia.rpc.RpcCallListener;
import io.bosonnetwork.kademlia.security.SourceKey;

/**
 * The storing side of subscriptions: who is watching what on this node, and telling them when it changes.
 * <p>
 * <b>Soft state only.</b> Nothing here is persisted. A subscriber renews on its own schedule and a renewal
 * the table has no record of simply creates the subscription again, carrying the sequence number the
 * subscriber holds - so a restart of this node heals within one renewal, and anything missed meanwhile is
 * pushed then.
 * </p>
 * <p>
 * <b>Standalone.</b> The only inputs are the three request handlers the DHT forwards (subscribe, cancel)
 * and the storage change events; the only output is a notification call through {@link Host#send}. None of
 * it runs inside a lookup or announce task.
 * </p>
 * <p>
 * <b>A push failure degrades the subscription, never the node.</b> Notifications are sent
 * {@link RpcCall#setLivenessNeutral liveness-neutral}, so a subscriber whose NAT mapping has lapsed is not
 * demoted, and eventually evicted, from the routing table by its own subscriptions. After
 * {@link #MAX_CONSECUTIVE_FAILURES} unacknowledged pushes the subscription stalls: it keeps the latest
 * record pending and pushes nothing more until the subscriber's next renewal proves it reachable again.
 * </p>
 * <p>
 * <b>Bounded.</b> Three quotas, all of which refuse rather than evict - eviction would let a flood push
 * honest subscribers out: per source unit, per target, and for the node. Pending state is at most one
 * record per subscription (one per fingerprint for peers, capped), so a node pushing slower than records
 * change falls behind in latency, never in memory.
 * </p>
 * <p>
 * <b>Damped.</b> A record - the value, or one peer instance - is pushed to a subscription at most once per
 * {@link #PUSH_MIN_INTERVAL}; changes in between only replace the pending record, so a burst of writes to
 * one key costs each subscriber one push per interval. One push per subscription is in flight at a time. All
 * pushes together are held to a node-wide rate.
 * </p>
 * <p>
 * Not thread-safe: owned by one DHT and driven from its event loop.
 * </p>
 */
public class SubscriptionManager {
	/** Lease granted when the subscriber asks for none, in seconds. */
	public static final int DEFAULT_LEASE = 10 * 60;
	/** Shortest lease granted, in seconds. A shorter request is raised to this. */
	public static final int MIN_LEASE = 60;
	/** Longest lease granted, in seconds. A longer request is shortened to this. */
	public static final int MAX_LEASE = 10 * 60;

	/** Subscriptions one source unit may hold on this node. */
	public static final int MAX_SUBSCRIPTIONS_PER_SOURCE = 64;
	/** Subscribers one target may have on this node. */
	public static final int MAX_SUBSCRIBERS_PER_TARGET = 64;
	/** Subscriptions this node holds in total. */
	public static final int MAX_SUBSCRIPTIONS = 8192;

	/** Least time between two pushes to one subscription, in milliseconds. */
	public static final long PUSH_MIN_INTERVAL = 1000;
	/** Unacknowledged pushes in a row after which a subscription stalls until renewed. */
	public static final int MAX_CONSECUTIVE_FAILURES = 3;
	/**
	 * How long a push may go unanswered before a packet from the subscriber is taken to mean it was lost, in
	 * milliseconds. Well above a round trip, so a push merely on its way is not sent twice.
	 */
	static final long RESEND_WHEN_HEARD_AFTER = 1000;
	/**
	 * How long pushing stops when the RPC server refuses one for want of a free call slot, in milliseconds:
	 * the shortest pause, doubling while refusals continue, up to {@link #PAUSE_MAX}.
	 */
	static final long PAUSE_MIN = 250;
	/** The longest pause after the RPC server refuses a push for want of a free call slot, in milliseconds. */
	static final long PAUSE_MAX = 2000;
	/**
	 * How long one subscription waits after the outbound throttle refuses a push to its subscriber's host,
	 * in milliseconds.
	 */
	static final long THROTTLE_BACKOFF = 1000;
	/** Node-wide push rate, per second. */
	public static final int PUSH_RATE = 200;
	/** Node-wide push burst. */
	public static final int PUSH_BURST = 400;

	/**
	 * Peer fingerprints tracked per subscription, pending and acknowledged each.
	 * <p>
	 * A target with more instances than this is still served, with two costs. Acknowledged state is kept for
	 * the most recently used fingerprints only, so a record whose state was forgotten can be pushed once
	 * more - harmless, since the subscriber deduplicates, but a wasted push. And at most this many records
	 * wait to go out at once: a record arriving while the pending set is full is deferred, and picked up
	 * from storage as soon as the set has drained to half rather than at the next renewal - once per
	 * deferral, so on a target with many more instances than this, what one refill cannot fit still waits
	 * for the renewal.
	 * </p>
	 * <p>
	 * A bound on memory rather than a tuning knob: every subscription carries these maps, so the cap times
	 * {@link #MAX_SUBSCRIPTIONS} is the worst case. Subscriptions are for watching a service, not for
	 * enumerating a fleet - FIND_PEER itself returns at most eight records.
	 * </p>
	 */
	static final int MAX_TRACKED_PEERS = 64;

	/**
	 * What the manager needs from the DHT that owns it. An interface rather than the DHT itself so that the
	 * manager can be exercised without a network.
	 */
	public interface Host {
		/**
		 * Returns the current time.
		 *
		 * @return epoch milliseconds.
		 */
		long now();

		/**
		 * Sends a call.
		 *
		 * @param call the call.
		 * @return a future settling when the call has been handed to the network or refused.
		 */
		Future<RpcCall> send(RpcCall call);

		/**
		 * Reads the value stored under an id.
		 *
		 * @param id the value id.
		 * @return a future of the value, or of null if none is stored.
		 */
		Future<Value> getValue(Id id);

		/**
		 * Reads the peer records stored under an id.
		 *
		 * @param id the peer id.
		 * @return a future of the records, empty if none is stored.
		 */
		Future<List<PeerInfo>> getPeers(Id id);

		/**
		 * Runs an action once, on the owner's context, after a delay.
		 *
		 * @param delay  the delay in milliseconds.
		 * @param action the action.
		 */
		void setTimer(long delay, Runnable action);
	}

	record Key(Id subscriber, SubscriptionKind kind, Id target) { }

	record Topic(SubscriptionKind kind, Id target) { }

	static final class Subscription {
		final Key key;
		final Topic topic;
		InetSocketAddress address;
		InetAddress source;
		long expiresAt;
		long eligibleAt;
		// Not before this, whatever is pending: set when the outbound throttle refuses a push to this host.
		long backoffUntil;

		// VALUE: the highest sequence number the subscriber holds, and the newer value waiting to go out.
		int ackedSeq = -1;
		Value pendingValue;

		// PEER: per fingerprint, the same two things.
		final Map<Long, Integer> ackedPeers = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
				return size() > MAX_TRACKED_PEERS;
			}
		};
		final LinkedHashMap<Long, PeerInfo> pendingPeers = new LinkedHashMap<>();
		// Records that do not fit one datagram on this node's address family, and so can never be pushed:
		// the value's sequence number, and per fingerprint the peer's. Kept apart from what was acknowledged,
		// which a renewal resets, so that no renewal offers them again. A newer version is tried as usual.
		int undeliverableSeq = -1;
		final Map<Long, Integer> undeliverablePeers = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
				return size() > MAX_TRACKED_PEERS;
			}
		};
		// When each fingerprint was last pushed: the damping interval is per record, not per subscription,
		// so a target's other instances are not held up by one that just changed.
		final Map<Long, Long> peerPushedAt = new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
				return size() > MAX_TRACKED_PEERS;
			}
		};
		long valuePushedAt;

		// A record was turned away because the pending set was full; re-read storage once it drains.
		boolean peersDeferred;

		boolean inFlight;
		long inFlightSince;
		// Bumped when a push in flight is abandoned; its outcome, when it arrives, is ignored.
		int generation;
		boolean stalled;
		boolean queued;
		boolean removed;
		int failures;

		Subscription(Key key, InetSocketAddress address, InetAddress source, long expiresAt) {
			this.key = key;
			this.topic = new Topic(key.kind(), key.target());
			this.address = address;
			this.source = source;
			this.expiresAt = expiresAt;
		}

		boolean hasPending() {
			return key.kind() == SubscriptionKind.VALUE ? pendingValue != null : !pendingPeers.isEmpty();
		}

		boolean canPush() {
			return !removed && !stalled && !inFlight && hasPending();
		}

		/**
		 * When the earliest pending record may be pushed: each record at most once per
		 * {@link #PUSH_MIN_INTERVAL}.
		 */
		long nextEligible() {
			if (key.kind() == SubscriptionKind.VALUE)
				return Math.max(backoffUntil, valuePushedAt + PUSH_MIN_INTERVAL);

			long next = Long.MAX_VALUE;
			for (Long fingerprint : pendingPeers.keySet())
				next = Math.min(next, peerPushedAt.getOrDefault(fingerprint, 0L) + PUSH_MIN_INTERVAL);
			return Math.max(backoffUntil, next);
		}

		/**
		 * The pending peer record to push now, or null if every one was pushed too recently.
		 */
		PeerInfo eligiblePeer(long now) {
			for (Map.Entry<Long, PeerInfo> e : pendingPeers.entrySet()) {
				if (peerPushedAt.getOrDefault(e.getKey(), 0L) + PUSH_MIN_INTERVAL <= now)
					return e.getValue();
			}
			return null;
		}
	}

	/** A token bucket, in milliseconds. */
	static final class RateLimiter {
		private final double perMilli;
		private final double capacity;
		private double tokens;
		private long last;

		RateLimiter(int perSecond, int burst, long now) {
			this.perMilli = perSecond / 1000.0;
			this.capacity = burst;
			this.tokens = burst;
			this.last = now;
		}

		private void refill(long now) {
			if (now > last) {
				tokens = Math.min(capacity, tokens + (now - last) * perMilli);
				last = now;
			}
		}

		boolean tryAcquire(long now) {
			refill(now);
			if (tokens < 1)
				return false;

			tokens -= 1;
			return true;
		}

		long nextAvailableAt(long now) {
			refill(now);
			return tokens >= 1 ? now : now + (long) Math.ceil((1 - tokens) / perMilli);
		}

		/** Gives back a token taken for something that did not happen. */
		void refund() {
			tokens = Math.min(capacity, tokens + 1);
		}
	}

	private final Host host;
	private final Map<Key, Subscription> subscriptions;
	private final Map<Topic, Set<Subscription>> byTopic;
	private final Map<Id, Set<Subscription>> bySubscriber;
	private final Map<InetAddress, Integer> perSource;
	private final ArrayDeque<Subscription> ready;
	private final PriorityQueue<Subscription> waiting;
	private final RateLimiter limiter;
	private long armedAt = Long.MAX_VALUE;
	// Pushing is paused until this, after the RPC server refused a push for want of a free call slot.
	private long pausedUntil;
	private long pauseBackoff = PAUSE_MIN;
	// Set while pump() runs, so that a send failing synchronously cannot run it again from inside itself.
	private boolean pumping;

	private static final Logger log = LoggerFactory.getLogger(SubscriptionManager.class);

	/**
	 * Creates a manager.
	 *
	 * @param host what the manager needs from its owner.
	 */
	public SubscriptionManager(Host host) {
		this.host = host;
		this.subscriptions = new HashMap<>();
		this.byTopic = new HashMap<>();
		this.bySubscriber = new HashMap<>();
		this.perSource = new HashMap<>();
		this.ready = new ArrayDeque<>();
		this.waiting = new PriorityQueue<>(Comparator.comparingLong(s -> s.eligibleAt));
		this.limiter = new RateLimiter(PUSH_RATE, PUSH_BURST, host.now());
	}

	/**
	 * Creates or renews a subscription.
	 * <p>
	 * A renewal also resynchronises: the subscriber says which sequence number it holds, a stalled
	 * subscription resumes, and whatever is stored and newer than what the subscriber holds is pushed. That
	 * is what makes a lost notification late rather than lost.
	 * </p>
	 * <p>
	 * The caller has verified the token for {@code address}, which is what makes it safe to move an existing
	 * subscription to a new address here: a NAT rebinding, proven by the subscriber receiving there.
	 * </p>
	 *
	 * @param subscriber     the subscriber's node id.
	 * @param address        the address the request arrived from, where notifications will go.
	 * @param kind           what is watched.
	 * @param target         the value id or peer id.
	 * @param knownSeq       values: the sequence number the subscriber holds, or -1 for none.
	 * @param requestedLease the requested lease in seconds, or -1 for the default.
	 * @return the granted lease, in seconds.
	 * @throws SubscriptionRefusedException if a quota is reached.
	 */
	public int subscribe(Id subscriber, InetSocketAddress address, SubscriptionKind kind, Id target,
			int knownSeq, int requestedLease) throws SubscriptionRefusedException {
		int lease = requestedLease < 0 ? DEFAULT_LEASE : Math.max(MIN_LEASE, Math.min(MAX_LEASE, requestedLease));
		long now = host.now();
		Key key = new Key(subscriber, kind, target);
		InetAddress source = SourceKey.of(address.getAddress());

		Subscription sub = subscriptions.get(key);
		if (sub == null) {
			if (subscriptions.size() >= MAX_SUBSCRIPTIONS)
				throw new SubscriptionRefusedException("Subscription limit of this node reached");

			Set<Subscription> subs = byTopic.get(new Topic(kind, target));
			if (subs != null && subs.size() >= MAX_SUBSCRIBERS_PER_TARGET)
				throw new SubscriptionRefusedException("Subscriber limit of the target reached");

			if (perSource.getOrDefault(source, 0) >= MAX_SUBSCRIPTIONS_PER_SOURCE)
				throw new SubscriptionRefusedException("Subscription limit of the source reached");

			sub = new Subscription(key, address, source, now + lease * 1000L);
			subscriptions.put(key, sub);
			byTopic.computeIfAbsent(sub.topic, t -> new LinkedHashSet<>()).add(sub);
			bySubscriber.computeIfAbsent(subscriber, t -> new LinkedHashSet<>()).add(sub);
			perSource.merge(source, 1, Integer::sum);
			log.debug("New {} subscription to {} from {}@{}", kind, target, subscriber, address);
		} else {
			if (!sub.source.equals(source)) {
				if (perSource.getOrDefault(source, 0) >= MAX_SUBSCRIPTIONS_PER_SOURCE)
					throw new SubscriptionRefusedException("Subscription limit of the source reached");

				releaseSource(sub.source);
				perSource.merge(source, 1, Integer::sum);
				sub.source = source;
			}

			if (!sub.address.equals(address)) {
				log.debug("{} subscription to {} from {} moved {} -> {}", kind, target, subscriber, sub.address, address);
				// A push in flight went to where the subscriber no longer is, and would only time out. Abandon
				// it and push again at once, rather than wait out the timeout for an answer that cannot come.
				if (sub.inFlight) {
					sub.generation++;
					sub.inFlight = false;
				}
			}

			sub.address = address;
			sub.expiresAt = now + lease * 1000L;
			sub.failures = 0;
			sub.stalled = false;
		}

		if (kind == SubscriptionKind.VALUE) {
			// The subscriber's word is authoritative about what it holds: after a restart it may hold less
			// than it once acknowledged, and it must then be sent the current value again.
			sub.ackedSeq = knownSeq;
			if (sub.pendingValue != null && sub.pendingValue.getSequenceNumber() <= knownSeq)
				sub.pendingValue = null;
		}

		resync(sub);
		schedule(sub);
		return lease;
	}

	/**
	 * Cancels a subscription.
	 * <p>
	 * No token is needed: the request authenticates as the subscriber's node id, which is the only party
	 * that can name this subscription, and a cancel only releases state.
	 * </p>
	 *
	 * @param subscriber the subscriber's node id.
	 * @param kind       what was watched.
	 * @param target     the value id or peer id.
	 * @return true if there was such a subscription.
	 */
	public boolean cancel(Id subscriber, SubscriptionKind kind, Id target) {
		Subscription sub = subscriptions.get(new Key(subscriber, kind, target));
		if (sub == null)
			return false;

		remove(sub);
		log.debug("Cancelled {} subscription to {} from {}", kind, target, subscriber);
		return true;
	}

	/**
	 * Takes a value storage now holds.
	 *
	 * @param value the effective stored value.
	 */
	public void valueStored(Value value) {
		Set<Subscription> subs = byTopic.get(new Topic(SubscriptionKind.VALUE, value.getId()));
		if (subs == null)
			return;

		for (Subscription sub : subs)
			offer(sub, value);
	}

	/**
	 * Takes a peer record storage now holds.
	 *
	 * @param peer the effective stored record.
	 */
	public void peerStored(PeerInfo peer) {
		Set<Subscription> subs = byTopic.get(new Topic(SubscriptionKind.PEER, peer.getId()));
		if (subs == null)
			return;

		for (Subscription sub : subs)
			offer(sub, peer, true);
	}

	/**
	 * Whether a node holds any subscription here: the cheap test to run before {@link #heardFrom}.
	 *
	 * @param nodeId the node id.
	 * @return true if it is a subscriber.
	 */
	public boolean isSubscriber(Id nodeId) {
		return bySubscriber.containsKey(nodeId);
	}

	/**
	 * Takes a packet from a subscriber, which proves the path from it open - and so, through a NAT, the
	 * path back to it.
	 * <p>
	 * This is how a subscription recovers from a mapping that lapsed and was opened again on the same port,
	 * which the subscriber cannot notice: nothing about it looks different from its side. Pushes into the
	 * gap have been timing out, and the retries follow the call timeout rather than the mapping, so without
	 * this a gap somewhat longer than the keepalive stalls the subscription until its next renewal - minutes.
	 * With it, the subscriber's next keepalive resumes a stalled subscription and resends a retry that was
	 * sent into the gap.
	 * </p>
	 * <p>
	 * Only a packet from the subscription's own address counts: that is the mapping pushes go to. The source
	 * address is unproven, so a replayed packet can do the same - which buys an attacker at most a few pushes
	 * before the subscription stalls again, sent to the subscriber that asked for them, under the node-wide
	 * rate.
	 * </p>
	 *
	 * @param nodeId the sender's node id.
	 * @param from   the address the packet came from.
	 */
	public void heardFrom(Id nodeId, InetSocketAddress from) {
		Set<Subscription> own = bySubscriber.get(nodeId);
		if (own == null || from == null)
			return;

		for (Subscription sub : List.copyOf(own)) {
			if (!sub.address.equals(from))
				continue;

			if (sub.stalled) {
				log.debug("{} subscription to {} from {} resumes: heard from {}", sub.key.kind(), sub.key.target(),
						nodeId, from);
				sub.stalled = false;
				sub.failures = 0;
				schedule(sub);
			} else if (sub.inFlight && (sub.failures > 0 || host.now() - sub.inFlightSince >= RESEND_WHEN_HEARD_AFTER)) {
				// A push still unanswered while the subscriber is plainly reachable was most likely sent into
				// the gap - before its packet reopened the mapping. Resend now the path is open, rather than
				// wait out the call timeout; a duplicate is dropped at the subscriber.
				log.debug("{} subscription to {} from {}: resending the push in flight, the path is open again",
						sub.key.kind(), sub.key.target(), nodeId);
				sub.generation++;
				sub.inFlight = false;
				schedule(sub);
			}
		}
	}

	/**
	 * Drops subscriptions whose lease has run out.
	 */
	public void sweep() {
		long now = host.now();
		// Collected first: remove() edits the maps being walked.
		List<Subscription> expired = subscriptions.values().stream().filter(s -> s.expiresAt <= now).toList();
		for (Subscription sub : expired) {
			log.debug("{} subscription to {} from {} expired", sub.key.kind(), sub.key.target(), sub.key.subscriber());
			remove(sub);
		}
	}

	/**
	 * Drops every subscription, for when the owning DHT stops.
	 */
	public void clear() {
		subscriptions.values().forEach(s -> s.removed = true);
		subscriptions.clear();
		byTopic.clear();
		bySubscriber.clear();
		perSource.clear();
		ready.clear();
		waiting.clear();
	}

	/**
	 * Returns the number of subscriptions held.
	 *
	 * @return the count.
	 */
	public int size() {
		return subscriptions.size();
	}

	/**
	 * Returns the number of subscribers of one target.
	 *
	 * @param kind   what is watched.
	 * @param target the value id or peer id.
	 * @return the count.
	 */
	public int subscribers(SubscriptionKind kind, Id target) {
		Set<Subscription> subs = byTopic.get(new Topic(kind, target));
		return subs == null ? 0 : subs.size();
	}

	// Package-private, for tests.
	Subscription get(Id subscriber, SubscriptionKind kind, Id target) {
		return subscriptions.get(new Key(subscriber, kind, target));
	}

	private void remove(Subscription sub) {
		if (sub.removed)
			return;

		sub.removed = true;
		subscriptions.remove(sub.key);
		Set<Subscription> subs = byTopic.get(sub.topic);
		if (subs != null) {
			subs.remove(sub);
			if (subs.isEmpty())
				byTopic.remove(sub.topic);
		}

		Set<Subscription> own = bySubscriber.get(sub.key.subscriber());
		if (own != null) {
			own.remove(sub);
			if (own.isEmpty())
				bySubscriber.remove(sub.key.subscriber());
		}

		releaseSource(sub.source);
		// Left in the queues if it is queued: both skip a removed subscription when they reach it.
	}

	private void releaseSource(InetAddress source) {
		perSource.computeIfPresent(source, (k, n) -> n > 1 ? n - 1 : null);
	}

	private void resync(Subscription sub) {
		resync(sub, true);
	}

	/**
	 * Offers the subscription whatever storage holds for its target.
	 *
	 * @param sub      the subscription.
	 * @param mayDefer whether peer records that find the pending set full arm a refill. Not for a refill's
	 *                 own reading - see {@link #refillIfDeferred}.
	 */
	private void resync(Subscription sub, boolean mayDefer) {
		if (sub.key.kind() == SubscriptionKind.VALUE) {
			host.getValue(sub.key.target()).onSuccess(value -> {
				if (value != null && !sub.removed)
					offer(sub, value);
			}).onFailure(e -> log.warn("Can not read value {} for a subscription", sub.key.target(), e));
		} else {
			host.getPeers(sub.key.target()).onSuccess(peers -> {
				if (peers != null && !sub.removed)
					peers.forEach(peer -> offer(sub, peer, mayDefer));
			}).onFailure(e -> log.warn("Can not read peers {} for a subscription", sub.key.target(), e));
		}
	}

	private void offer(Subscription sub, Value value) {
		int seq = value.getSequenceNumber();
		if (seq <= sub.ackedSeq || seq == sub.undeliverableSeq)
			return;

		if (sub.pendingValue != null && sub.pendingValue.getSequenceNumber() >= seq)
			return;

		sub.pendingValue = value;
		schedule(sub);
	}

	private void offer(Subscription sub, PeerInfo peer, boolean mayDefer) {
		long fingerprint = peer.getFingerprint();
		int seq = peer.getSequenceNumber();
		Integer acked = sub.ackedPeers.get(fingerprint);
		if (acked != null && seq <= acked)
			return;

		Integer undeliverable = sub.undeliverablePeers.get(fingerprint);
		if (undeliverable != null && seq <= undeliverable)
			return;

		PeerInfo pending = sub.pendingPeers.get(fingerprint);
		if (pending != null && pending.getSequenceNumber() >= seq)
			return;

		if (pending == null && sub.pendingPeers.size() >= MAX_TRACKED_PEERS) {
			log.debug("Too many pending peer records for subscription to {} from {}, deferring {}",
					sub.key.target(), sub.key.subscriber(), fingerprint);
			if (mayDefer)
				sub.peersDeferred = true;
			return;
		}

		sub.pendingPeers.put(fingerprint, peer);
		schedule(sub);
	}

	private void schedule(Subscription sub) {
		if (sub.queued || !sub.canPush())
			return;

		sub.queued = true;
		sub.eligibleAt = sub.nextEligible();
		if (host.now() < sub.eligibleAt) {
			waiting.add(sub);
			arm();
		} else {
			ready.add(sub);
			pump();
		}
	}

	/**
	 * Sends what the rate allows, then arranges to be called again when more is due.
	 */
	void pump() {
		// A send can fail synchronously, inside the loop below, and its handling schedules again. That must
		// not start a second loop over the same queues: what it added is picked up by the loop running.
		if (pumping)
			return;

		pumping = true;
		try {
			drain();
		} finally {
			pumping = false;
		}

		arm();
	}

	private void drain() {
		long now = host.now();
		while (!waiting.isEmpty() && waiting.peek().eligibleAt <= now)
			ready.add(waiting.poll());

		while (!ready.isEmpty()) {
			// Checked on every turn: a refusal for want of a call slot, inside the push just made, pauses
			// everything rather than letting each queued subscription find the node full for itself.
			if (now < pausedUntil)
				break;

			Subscription sub = ready.peek();
			if (!sub.canPush()) {
				ready.poll().queued = false;
				continue;
			}

			// Pending state can change while queued; a record that is not yet due goes back to wait.
			if (sub.nextEligible() > now) {
				ready.poll();
				sub.eligibleAt = sub.nextEligible();
				waiting.add(sub);
				continue;
			}

			if (!limiter.tryAcquire(now))
				break;

			ready.poll().queued = false;
			push(sub, now);
		}
	}

	private void arm() {
		long now = host.now();
		long next = Long.MAX_VALUE;
		if (!ready.isEmpty())
			next = Math.max(limiter.nextAvailableAt(now), pausedUntil);
		if (!waiting.isEmpty())
			next = Math.min(next, Math.max(waiting.peek().eligibleAt, pausedUntil));

		// One timer, for the earliest thing due. A later one already armed is left to fire harmlessly.
		if (next == Long.MAX_VALUE || next >= armedAt)
			return;

		long at = next;
		armedAt = at;
		host.setTimer(Math.max(1, at - now), () -> {
			if (armedAt == at)
				armedAt = Long.MAX_VALUE;
			pump();
		});
	}

	private void push(Subscription sub, long now) {
		Message request;
		Object record;
		// The stamp this push replaces, put back if the push is refused before it leaves: a push that was
		// never sent must not hold its record to the interval as if it had been.
		long previousStamp;
		if (sub.key.kind() == SubscriptionKind.VALUE) {
			record = sub.pendingValue;
			request = Message.notifyValueRequest(sub.pendingValue);
			previousStamp = sub.valuePushedAt;
			sub.valuePushedAt = now;
		} else {
			PeerInfo peer = sub.eligiblePeer(now);
			record = peer;
			request = Message.notifyPeerRequest(peer);
			Long stamp = sub.peerPushedAt.put(peer.getFingerprint(), now);
			previousStamp = stamp != null ? stamp : 0;
		}

		sub.inFlight = true;
		sub.inFlightSince = now;
		InetSocketAddress to = sub.address;
		int generation = sub.generation;
		log.debug("Pushing {} of {} to {}@{}", describe(record), sub.key.target(), sub.key.subscriber(), to);

		// One settlement per push, whichever of the call's outcome and a refused send arrives first - and
		// none at all for a push abandoned since.
		boolean[] settled = new boolean[] { false };
		RpcCall call = new RpcCall(NodeInfo.of(sub.key.subscriber(), sub.address), request)
				.setLivenessNeutral(true)
				.addListener(new RpcCallListener() {
					@Override
					public void onStateChange(RpcCall c, RpcCall.State previous, RpcCall.State state) {
					}

					@Override
					public void onResponse(RpcCall c) {
						if (!settled[0] && sub.generation == generation) {
							settled[0] = true;
							acknowledged(sub, record);
						}
					}

					@Override
					public void onTimeout(RpcCall c) {
						if (!settled[0] && sub.generation == generation) {
							settled[0] = true;
							failed(sub, to, "timeout");
						}
					}

					@Override
					public void onError(RpcCall c) {
						if (settled[0] || sub.generation != generation)
							return;

						settled[0] = true;
						if (c.getCause() instanceof UnknownSubscriptionException) {
							log.debug("{} has no {} subscription to {} any more, dropping it", sub.key.subscriber(),
									sub.key.kind(), sub.key.target());
							sub.inFlight = false;
							remove(sub);
						} else if (c.getCause() instanceof MessageTooBigException) {
							undeliverable(sub, record);
						} else if (c.getCause() instanceof CallRejectedException rejected) {
							refused(sub, record, previousStamp, rejected.getReason());
						} else {
							failed(sub, to, String.valueOf(c.getCause()));
						}
					}

					@Override
					public void onCancel(RpcCall c) {
						if (!settled[0] && sub.generation == generation) {
							settled[0] = true;
							// Cancelled on our side - a stop - which says nothing about the subscriber.
							sub.inFlight = false;
							schedule(sub);
						}
					}
				});

		host.send(call).onSuccess(c -> pauseBackoff = PAUSE_MIN).onFailure(e -> {
			if (!settled[0] && sub.generation == generation) {
				settled[0] = true;
				if (e instanceof MessageTooBigException)
					undeliverable(sub, record);
				else if (e instanceof CallRejectedException rejected)
					refused(sub, record, previousStamp, rejected.getReason());
				else
					failed(sub, to, e.getMessage());
			}
		});
	}

	/**
	 * Takes a push the RPC server refused to send because this node is at one of its own limits.
	 * <p>
	 * Nothing here is about the subscriber, so nothing is charged to it: the push never left. Counting it
	 * as a failure would let our own congestion stall healthy subscriptions, and a stall is only lifted by
	 * the subscriber's traffic or its renewal - so a busy minute here would become minutes of delay there.
	 * </p>
	 * <p>
	 * What happens instead depends on which limit refused:
	 * </p>
	 * <ul>
	 *   <li>A full active-call table is the whole node's congestion. Every other push would be refused just
	 *       the same, so pushing stops for a while - {@link #PAUSE_MIN}, doubling while refusals continue,
	 *       up to {@link #PAUSE_MAX} - and this subscription goes back to the front of the queue.</li>
	 *   <li>The outbound throttle refuses on account of one destination: too much is queued for this
	 *       subscriber's host. Only this subscription waits, {@link #THROTTLE_BACKOFF}, and every record of
	 *       it, so that another pending peer record does not rediscover the same throttle at once. Pausing
	 *       everything for one host would hold every other subscriber up behind it.</li>
	 * </ul>
	 * <p>
	 * Either way the retry comes from a timer, never from here: a refusal arrives synchronously, inside the
	 * send, and pushing again from inside it would recurse into the queue being drained.
	 * </p>
	 */
	private void refused(Subscription sub, Object record, long previousStamp, CallRejectedException.Reason reason) {
		sub.inFlight = false;
		limiter.refund();
		if (record instanceof Value)
			sub.valuePushedAt = previousStamp;
		else
			sub.peerPushedAt.put(((PeerInfo) record).getFingerprint(), previousStamp);

		if (sub.removed)
			return;

		long now = host.now();
		if (reason == CallRejectedException.Reason.ACTIVE_CALLS) {
			pausedUntil = now + pauseBackoff;
			log.debug("No free call slot for a push to {}, pausing pushes for {}ms", sub.key.subscriber(), pauseBackoff);
			pauseBackoff = Math.min(pauseBackoff * 2, PAUSE_MAX);
			if (!sub.queued) {
				sub.queued = true;
				ready.addFirst(sub);
			}
		} else {
			sub.backoffUntil = now + THROTTLE_BACKOFF;
			log.debug("Push to {}@{} refused by the outbound throttle, retrying in {}ms", sub.key.subscriber(),
					sub.address, THROTTLE_BACKOFF);
			schedule(sub);
		}

		// The loop that sent it, if it is still running, arms on its way out; a refusal arriving later does it.
		if (!pumping)
			arm();
	}

	/**
	 * Gives up on a record the RPC server refused to send because it does not fit one datagram.
	 * <p>
	 * Unlike every other failure this one is permanent: the same record is refused however often it is
	 * tried. Counted as an ordinary failure it would stall the subscription, and the renewal that resumes
	 * it would offer the record again - so one oversized record under a watched id would keep every
	 * subscription to it stalled. It is dropped instead, remembered so that no renewal offers it again, and
	 * the subscription carries on with whatever else is pending; a newer version is pushed as usual.
	 * </p>
	 * <p>
	 * The limits on values and peer records are derived so that a notification always fits, so reaching
	 * this means a record stored without passing them - one older than the limits, say - or a defect in
	 * the derivation. Hence a warning.
	 * </p>
	 */
	private void undeliverable(Subscription sub, Object record) {
		sub.inFlight = false;
		if (sub.removed)
			return;

		log.warn("{} of {} does not fit one datagram and cannot be pushed to {}, skipping it", describe(record),
				sub.key.target(), sub.key.subscriber());
		if (record instanceof Value value) {
			sub.undeliverableSeq = value.getSequenceNumber();
			if (sub.pendingValue != null && sub.pendingValue.getSequenceNumber() == value.getSequenceNumber())
				sub.pendingValue = null;
		} else if (record instanceof PeerInfo peer) {
			long fingerprint = peer.getFingerprint();
			sub.undeliverablePeers.merge(fingerprint, peer.getSequenceNumber(), Math::max);
			PeerInfo pending = sub.pendingPeers.get(fingerprint);
			if (pending != null && pending.getSequenceNumber() <= peer.getSequenceNumber())
				sub.pendingPeers.remove(fingerprint);
			refillIfDeferred(sub);
		}

		schedule(sub);
	}

	/**
	 * Takes a subscriber's answer to a push. It says the subscription exists and the notification arrived,
	 * not that the record was accepted - the subscriber validates afterwards and discards a bad record
	 * without saying so. So it settles one thing only: this version need not be pushed again.
	 */
	private void acknowledged(Subscription sub, Object record) {
		log.debug("{} of {} acknowledged by {}", describe(record), sub.key.target(), sub.key.subscriber());
		sub.inFlight = false;
		sub.failures = 0;
		if (record instanceof Value value) {
			sub.ackedSeq = Math.max(sub.ackedSeq, value.getSequenceNumber());
			if (sub.pendingValue != null && sub.pendingValue.getSequenceNumber() <= sub.ackedSeq)
				sub.pendingValue = null;
		} else if (record instanceof PeerInfo peer) {
			long fingerprint = peer.getFingerprint();
			int seq = sub.ackedPeers.merge(fingerprint, peer.getSequenceNumber(), Math::max);
			PeerInfo pending = sub.pendingPeers.get(fingerprint);
			if (pending != null && pending.getSequenceNumber() <= seq)
				sub.pendingPeers.remove(fingerprint);
			refillIfDeferred(sub);
		}

		schedule(sub);
	}

	/**
	 * Picks up the peer records turned away while the pending set was full, once it has drained to half -
	 * from storage, which still holds them, so nothing had to be remembered beyond the fact. Without this
	 * they would wait for the next renewal.
	 * <p>
	 * One refill per deferral, and records a refill itself cannot fit do not arm another. Storage is read
	 * whole, so on a target with many more instances than {@link #MAX_TRACKED_PEERS} a refill also offers
	 * records whose acknowledged state was forgotten; letting refills chain would push those again and
	 * again, for as long as the subscription lived. What one refill leaves over waits for the renewal.
	 * </p>
	 */
	private void refillIfDeferred(Subscription sub) {
		if (!sub.peersDeferred || sub.pendingPeers.size() > MAX_TRACKED_PEERS / 2)
			return;

		sub.peersDeferred = false;
		resync(sub, false);
	}

	private void failed(Subscription sub, InetSocketAddress to, String reason) {
		sub.inFlight = false;
		if (sub.removed)
			return;

		log.debug("Push of {} to {}@{} failed ({}), {} in a row", sub.key.target(), sub.key.subscriber(),
				to, reason, sub.failures + 1);
		if (++sub.failures >= MAX_CONSECUTIVE_FAILURES) {
			sub.stalled = true;
			log.debug("{} subscription to {} from {}@{} stalled after {} failed pushes ({}), waiting for renewal",
					sub.key.kind(), sub.key.target(), sub.key.subscriber(), sub.address, sub.failures, reason);
			return;
		}

		schedule(sub);
	}

	private static String describe(Object record) {
		if (record instanceof Value value)
			return "value seq " + value.getSequenceNumber();
		PeerInfo peer = (PeerInfo) record;
		return "peer " + peer.getFingerprint() + " seq " + peer.getSequenceNumber();
	}

	// Package-private, for tests: drops removed subscriptions still sitting in the queues.
	int queued() {
		int n = 0;
		for (Iterator<Subscription> it = ready.iterator(); it.hasNext(); )
			if (!it.next().removed)
				n++;
		for (Subscription s : waiting)
			if (!s.removed)
				n++;
		return n;
	}
}
