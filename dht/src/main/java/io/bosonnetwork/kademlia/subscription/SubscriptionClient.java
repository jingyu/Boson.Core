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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.kademlia.exceptions.InvalidTokenException;
import io.bosonnetwork.kademlia.exceptions.KadException;
import io.bosonnetwork.kademlia.impl.ErrorCode;
import io.bosonnetwork.kademlia.protocol.FindNodeResponse;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.protocol.NotifyPeerRequest;
import io.bosonnetwork.kademlia.protocol.NotifyValueRequest;
import io.bosonnetwork.kademlia.protocol.SubscribeResponse;
import io.bosonnetwork.kademlia.protocol.SubscriptionKind;
import io.bosonnetwork.kademlia.rpc.RpcCall;
import io.bosonnetwork.kademlia.rpc.RpcCallListener;
import io.bosonnetwork.kademlia.security.SourceKey;

/**
 * The subscriber side of subscriptions, for one address family: which storing nodes watch each target for
 * us, and keeping them watching.
 * <p>
 * <b>Standalone.</b> A lookup is used as a black box to find the nodes closest to a target, with their
 * write tokens; everything after that - subscribing, renewing, keeping NAT mappings open, replacing nodes
 * that fail - is done here with plain calls. Nothing of it lives in a lookup or announce task.
 * </p>
 * <p>
 * <b>Which nodes.</b> {@link #SUBSCRIBE_NODES} of the closest, in distance order, each in a different
 * source unit. A publish reaches up to k nodes and any one honest storing node is enough, so a few cover
 * churn. More would not buy security: node ids are free, so a node wanting to sit next to a key can take
 * eight places as easily as four. Addresses are not free, and requiring distinct source units stops one
 * host with several ids near the key from taking every slot.
 * </p>
 * <p>
 * <b>Renewal and NAT.</b> A subscription is renewed every {@link #RENEW_INTERVAL}, bounded by how long the
 * token from the previous response stays valid. Separately, while this node may be behind NAT, every
 * storing node it depends on is pinged every {@link #NAT_KEEPALIVE_INTERVAL}: a mapping and its filter
 * belong to a pair of endpoints, so one packet per storing node keeps every subscription on it reachable,
 * however many there are. A mapping that lapses anyway costs latency, not data: the storing node stalls the
 * subscription, and the next renewal, which carries the sequence number held here, brings it up to date.
 * </p>
 * <p>
 * <b>Rebinding.</b> A NAT that loses a mapping may open the next one on a different port, and the storing
 * node goes on pushing to the old one. Every reply carries the endpoint the replier saw the request come
 * from, so the keepalive notices: when a storing node sees us somewhere other than where it accepted the
 * subscription, the subscription is moved at once rather than at the next renewal. Moving needs a token for
 * the new endpoint - the one held is bound to the old - so it is fetched from that node alone with a
 * token-only FIND_NODE, and presented in a fresh SUBSCRIBE. A renewal refused for its token takes the same
 * path.
 * </p>
 * <p>
 * <b>Notifications.</b> One is accepted only from a node holding the matching subscription for us - the
 * record names the target, the method the kind, and the transport has already authenticated the sender, so
 * no token or subscription id is needed. The record is validated off the event loop before it is
 * delivered; a node serving one that fails is reported as unproven misbehavior and replaced. Delivery is
 * deduplicated across the storing nodes: a value only when its sequence number rises, a peer record only
 * when its fingerprint is new or its sequence number rises.
 * </p>
 * <p>
 * Not thread-safe: owned by one DHT and driven from its event loop.
 * </p>
 */
public class SubscriptionClient {
	/** Storing nodes subscribed to for each target. */
	public static final int SUBSCRIBE_NODES = 4;
	/** Lease requested, in seconds. */
	public static final int REQUESTED_LEASE = SubscriptionManager.DEFAULT_LEASE;
	/**
	 * How often a subscription is renewed, in milliseconds. Must stay under the write token's lifetime -
	 * five minutes plus the previous-secret grace - since each renewal presents the token the last one
	 * returned.
	 */
	public static final long RENEW_INTERVAL = 4 * 60 * 1000;
	/**
	 * How often each storing node is pinged while this node may be behind NAT, in milliseconds.
	 * <p>
	 * NATs are asked to keep a UDP mapping at least two minutes (RFC 4787), but carrier-grade and mobile
	 * ones commonly drop it after 30 to 60 seconds and some sooner, and only outbound traffic is sure to
	 * refresh it. A keepalive at 30 seconds loses the race against a 30-second timer. No value suits every
	 * NAT.
	 * </p>
	 */
	public static final long NAT_KEEPALIVE_INTERVAL = 25 * 1000;
	/** How often the closest nodes are looked up again, since the closest set drifts, in milliseconds. */
	public static final long REHOME_INTERVAL = 15 * 60 * 1000;

	/** Tokens from a lookup older than this are not presented; a new lookup is run instead. */
	static final long CANDIDATES_FRESH = 4 * 60 * 1000;
	/** Least time between two lookups for one target. */
	static final long RELOOKUP_MIN_INTERVAL = 30 * 1000;
	/** How long a node that cannot hold subscriptions is left alone. */
	static final long EXCLUDE_UNSUPPORTED = 60 * 60 * 1000;
	/** How long a node that failed or refused a subscription is left alone. */
	static final long EXCLUDE_FAILED = 5 * 60 * 1000;
	/** Peer fingerprints remembered per target for deduplication. */
	static final int MAX_DELIVERED_PEERS = 256;

	/**
	 * A node found by a lookup, with the write token it issued for the target.
	 *
	 * @param node  the node.
	 * @param token its write token for the target; 0 if it issued none.
	 */
	public record Candidate(NodeInfo node, int token) { }

	/**
	 * What the client needs from the DHT that owns it.
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
		 * Finds the nodes closest to a target, with their write tokens.
		 *
		 * @param target the target.
		 * @return a future of the candidates, closest first.
		 */
		Future<List<Candidate>> lookup(Id target);

		/**
		 * Runs an action once, on the owner's context, after a delay.
		 *
		 * @param delay  the delay in milliseconds.
		 * @param action the action.
		 * @return a timer id.
		 */
		long setTimer(long delay, Runnable action);

		/**
		 * Cancels a timer.
		 *
		 * @param id the timer id.
		 */
		void cancelTimer(long id);

		/**
		 * Whether this node may be behind NAT, and so must keep its mappings to storing nodes open.
		 *
		 * @return true unless this node is known to be reachable at the address it binds.
		 */
		boolean needsNatKeepalive();

		/**
		 * Runs work off the event loop, completing back on it.
		 *
		 * @param task the work.
		 * @param <T>  the result type.
		 * @return a future of the result.
		 */
		<T> Future<T> executeBlocking(Callable<T> task);

		/**
		 * Reports a notification carrying a record that failed validation. The notification is a request,
		 * not the answer to a call of ours, so its source is unproven.
		 *
		 * @param notification the notification.
		 */
		void reportInvalid(Message notification);
	}

	/**
	 * Receives what a subscription delivers. Called on the owning DHT's context.
	 */
	public interface Sink {
		/**
		 * A value newer than any delivered before.
		 *
		 * @param value the value.
		 */
		default void onValue(Value value) { }

		/**
		 * A peer record whose fingerprint is new, or whose sequence number rose.
		 *
		 * @param peer the record.
		 */
		default void onPeer(PeerInfo peer) { }

		/**
		 * The number of storing nodes currently confirmed to be watching changed.
		 *
		 * @param subscribedNodes the number; 0 means nothing is watching for us at the moment.
		 */
		default void onStatus(int subscribedNodes) { }
	}

	private record Topic(SubscriptionKind kind, Id target) { }

	private static final class Slot {
		final NodeInfo node;
		final InetAddress unit;
		int token;
		boolean confirmed;
		boolean retokening;
		long renewTimer = -1;
		// Where the storing node saw our SUBSCRIBE come from: the mapping its pushes go to.
		InetSocketAddress observed;

		Slot(NodeInfo node, int token) {
			this.node = node;
			this.unit = SourceKey.of(node.getIpAddress());
			this.token = token;
		}
	}

	/**
	 * One subscription to a target, however many storing nodes carry it.
	 */
	public final class Watch {
		private final Topic topic;
		private final Sink sink;
		private final Promise<Void> ready;
		private final Map<Id, Slot> slots;
		private final Map<Id, Long> excluded;
		private final Map<Long, Integer> deliveredPeers;
		private int knownSeq;
		private List<Candidate> candidates;
		private long candidatesAt;
		private long lastLookupAt;
		private boolean lookupPending;
		private long lookupTimer = -1;
		private long rehomeTimer = -1;
		private int reportedStatus;
		private boolean cancelled;

		private Watch(Topic topic, int knownSeq, Sink sink) {
			this.topic = topic;
			this.sink = sink;
			this.ready = Promise.promise();
			this.slots = new LinkedHashMap<>();
			this.excluded = new HashMap<>();
			this.deliveredPeers = new LinkedHashMap<>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
					return size() > MAX_DELIVERED_PEERS;
				}
			};
			this.knownSeq = knownSeq;
			this.candidates = List.of();
		}

		/**
		 * Returns the watched id.
		 *
		 * @return the target.
		 */
		public Id target() {
			return topic.target();
		}

		/**
		 * Returns what is watched.
		 *
		 * @return the kind.
		 */
		public SubscriptionKind kind() {
			return topic.kind();
		}

		/**
		 * Returns a future completing once one storing node has accepted, and failing if none did.
		 *
		 * @return the future.
		 */
		public Future<Void> ready() {
			return ready.future();
		}

		/**
		 * Returns the number of storing nodes confirmed to be watching.
		 *
		 * @return the number.
		 */
		public int subscribedNodes() {
			return (int) slots.values().stream().filter(s -> s.confirmed).count();
		}

		/**
		 * Cancels the subscription on every storing node, best effort.
		 */
		public void cancel() {
			SubscriptionClient.this.cancel(this, true);
		}

		// Package-private, for tests.
		Set<Id> nodes() {
			return slots.keySet();
		}
	}

	private final Host host;
	private final Map<Topic, Watch> watches;
	private long keepaliveTimer = -1;

	private static final Logger log = LoggerFactory.getLogger(SubscriptionClient.class);

	/**
	 * Creates a client.
	 *
	 * @param host what the client needs from its owner.
	 */
	public SubscriptionClient(Host host) {
		this.host = host;
		this.watches = new HashMap<>();
	}

	/**
	 * Subscribes to a target.
	 * <p>
	 * One subscription per target and kind: a caller with several listeners for the same target merges them
	 * itself rather than opening several network subscriptions.
	 * </p>
	 *
	 * @param kind     what to watch.
	 * @param target   the value id or peer id.
	 * @param knownSeq values: the sequence number already held, or -1 for none.
	 * @param sink     where deliveries go.
	 * @return the subscription; its {@link Watch#ready()} future says whether any node accepted.
	 * @throws IllegalStateException if this client already watches the target for that kind.
	 */
	public Watch subscribe(SubscriptionKind kind, Id target, int knownSeq, Sink sink) {
		Topic topic = new Topic(kind, target);
		if (watches.containsKey(topic))
			throw new IllegalStateException("Already subscribed to " + kind + " " + target);

		Watch watch = new Watch(topic, kind == SubscriptionKind.VALUE ? knownSeq : -1, sink);
		watches.put(topic, watch);
		watch.rehomeTimer = host.setTimer(REHOME_INTERVAL, () -> rehome(watch));
		ensureKeepalive();
		lookup(watch);
		return watch;
	}

	/**
	 * Takes a NOTIFY_VALUE or NOTIFY_PEER.
	 * <p>
	 * Answers whether a matching subscription exists - synchronously, since that is what the reply says -
	 * and hands the record to validation and delivery.
	 * </p>
	 *
	 * @param notification the notification.
	 * @return true if this node holds a matching subscription with the sender, false if it should be told
	 *         there is none.
	 */
	public boolean onNotify(Message notification) {
		Id sender = notification.getId();
		Object record;
		Topic topic;
		if (notification.getMethod() == Message.Method.NOTIFY_VALUE) {
			Value value = notification.<NotifyValueRequest>getBody().getValue();
			record = value;
			topic = new Topic(SubscriptionKind.VALUE, value.getId());
		} else if (notification.getMethod() == Message.Method.NOTIFY_PEER) {
			PeerInfo peer = notification.<NotifyPeerRequest>getBody().getPeer();
			record = peer;
			topic = new Topic(SubscriptionKind.PEER, peer.getId());
		} else {
			throw new IllegalArgumentException("Not a notification: " + notification.getMethod());
		}

		Watch watch = watches.get(topic);
		// A slot that is not confirmed yet still counts: the storing node pushes what it holds as soon as it
		// accepts, and that push may overtake its answer.
		Slot slot = watch == null ? null : watch.slots.get(sender);
		if (slot == null)
			return false;

		host.executeBlocking(() -> record instanceof Value v ? v.isValid() : ((PeerInfo) record).isValid())
				.onComplete(ar -> {
					if (watch.cancelled)
						return;

					if (ar.failed() || !ar.result()) {
						log.warn("Node {} notified an invalid {} for {}, replacing it", sender, topic.kind(),
								topic.target());
						host.reportInvalid(notification);
						// The slot may have been replaced meanwhile; only a slot still held by that node goes.
						if (watch.slots.get(sender) == slot)
							drop(watch, slot, EXCLUDE_FAILED, false);
						replace(watch);
						return;
					}

					log.debug("Notified by {} of {} {}", sender, topic.kind(), topic.target());
					deliver(watch, record);
				});

		return true;
	}

	/**
	 * Drops every subscription without telling the storing nodes, for when the owning DHT stops. Their
	 * leases run out on their own.
	 */
	public void clear() {
		for (Watch watch : List.copyOf(watches.values()))
			cancel(watch, false);
		watches.clear();
	}

	/**
	 * Returns the number of targets watched.
	 *
	 * @return the count.
	 */
	public int size() {
		return watches.size();
	}

	private void deliver(Watch watch, Object record) {
		if (record instanceof Value value) {
			int seq = value.getSequenceNumber();
			if (seq <= watch.knownSeq)
				return;

			watch.knownSeq = seq;
			watch.sink.onValue(value);
		} else if (record instanceof PeerInfo peer) {
			Integer delivered = watch.deliveredPeers.get(peer.getFingerprint());
			if (delivered != null && peer.getSequenceNumber() <= delivered)
				return;

			watch.deliveredPeers.put(peer.getFingerprint(), peer.getSequenceNumber());
			watch.sink.onPeer(peer);
		}
	}

	private void cancel(Watch watch, boolean tellNodes) {
		if (watch.cancelled)
			return;

		watch.cancelled = true;
		watches.remove(watch.topic);
		cancelTimer(watch.rehomeTimer);
		cancelTimer(watch.lookupTimer);
		for (Slot slot : watch.slots.values()) {
			cancelTimer(slot.renewTimer);
			if (tellNodes && slot.confirmed) {
				Message request = Message.subscribeRequest(watch.topic.target(), watch.topic.kind(), 0, -1, 0);
				host.send(new RpcCall(slot.node, request));
			}
		}

		watch.slots.clear();
		watch.ready.tryFail(new CancellationException("Subscription cancelled"));
		if (watches.isEmpty()) {
			cancelTimer(keepaliveTimer);
			keepaliveTimer = -1;
		}
	}

	private void lookup(Watch watch) {
		if (watch.cancelled || watch.lookupPending)
			return;

		watch.lookupPending = true;
		watch.lastLookupAt = host.now();
		host.lookup(watch.topic.target()).onComplete(ar -> {
			watch.lookupPending = false;
			if (watch.cancelled)
				return;

			if (ar.succeeded()) {
				watch.candidates = ar.result();
				watch.candidatesAt = host.now();
			} else {
				log.debug("Lookup for the {} subscription to {} failed", watch.topic.kind(), watch.topic.target(),
						ar.cause());
			}

			fill(watch);
		});
	}

	/**
	 * Asks for a new lookup, now if the rate allows it, otherwise once it does.
	 */
	private void requestLookup(Watch watch) {
		if (watch.cancelled || watch.lookupPending || watch.lookupTimer != -1)
			return;

		long wait = watch.lastLookupAt + RELOOKUP_MIN_INTERVAL - host.now();
		if (wait <= 0) {
			lookup(watch);
			return;
		}

		watch.lookupTimer = host.setTimer(wait, () -> {
			watch.lookupTimer = -1;
			lookup(watch);
		});
	}

	/**
	 * Tops the watch up to {@link #SUBSCRIBE_NODES} from the candidates of the last lookup.
	 */
	private void fill(Watch watch) {
		long now = host.now();
		watch.excluded.values().removeIf(until -> until <= now);

		boolean fresh = now - watch.candidatesAt < CANDIDATES_FRESH;
		if (fresh) {
			Set<InetAddress> units = new HashSet<>();
			watch.slots.values().forEach(s -> units.add(s.unit));

			for (Candidate candidate : watch.candidates) {
				if (watch.slots.size() >= SUBSCRIBE_NODES)
					break;

				NodeInfo node = candidate.node();
				if (candidate.token() == 0 || watch.slots.containsKey(node.getId()) ||
						watch.excluded.containsKey(node.getId()) || node.getIpAddress() == null)
					continue;

				Slot slot = new Slot(node, candidate.token());
				if (!units.add(slot.unit))
					continue;

				watch.slots.put(node.getId(), slot);
				send(watch, slot);
			}
		}

		if (watch.slots.size() < SUBSCRIBE_NODES && !fresh)
			requestLookup(watch);

		// Nothing accepted and nothing left to try from this lookup: the caller hears now rather than when
		// some later lookup might succeed. A later one still can, and the watch keeps trying while it lives.
		if (watch.slots.isEmpty() && !watch.lookupPending)
			watch.ready.tryFail(new IllegalStateException("No node accepted the " +
					watch.topic.kind().name().toLowerCase() + " subscription to " + watch.topic.target()));
	}

	private void replace(Watch watch) {
		if (!watch.cancelled)
			fill(watch);
	}

	private void send(Watch watch, Slot slot) {
		int knownSeq = watch.topic.kind() == SubscriptionKind.VALUE ? watch.knownSeq : -1;
		Message request = Message.subscribeRequest(watch.topic.target(), watch.topic.kind(), slot.token, knownSeq,
				REQUESTED_LEASE);

		RpcCall call = new RpcCall(slot.node, request).addListener(new RpcCallListener() {
			@Override
			public void onStateChange(RpcCall c, RpcCall.State previous, RpcCall.State state) {
			}

			@Override
			public void onResponse(RpcCall c) {
				accepted(watch, slot, c.getResponse().getBody(), c.getResponse().getObserved());
			}

			@Override
			public void onError(RpcCall c) {
				rejected(watch, slot, c.getCause());
			}

			@Override
			public void onTimeout(RpcCall c) {
				rejected(watch, slot, null);
			}
		});

		host.send(call).onFailure(e -> rejected(watch, slot, e));
	}

	private void accepted(Watch watch, Slot slot, SubscribeResponse response, InetSocketAddress observed) {
		if (watch.cancelled || watch.slots.get(slot.node.getId()) != slot)
			return;

		slot.observed = observed;

		if (response != null && response.getToken() != 0)
			slot.token = response.getToken();

		slot.confirmed = true;
		int lease = response != null && response.getLease() > 0 ? response.getLease() : REQUESTED_LEASE;
		long renewIn = Math.min(RENEW_INTERVAL, lease * 1000L / 2);
		cancelTimer(slot.renewTimer);
		slot.renewTimer = host.setTimer(renewIn, () -> {
			slot.renewTimer = -1;
			if (!watch.cancelled && watch.slots.get(slot.node.getId()) == slot)
				send(watch, slot);
		});

		// Status first: whoever waits on ready should find the count already up to date.
		reportStatus(watch);
		watch.ready.tryComplete();
	}

	private void rejected(Watch watch, Slot slot, Throwable cause) {
		if (watch.cancelled || watch.slots.get(slot.node.getId()) != slot)
			return;

		// A node that accepted us before and now refuses the token is not refusing us: the token is bound to
		// the endpoint it was issued for, and ours has moved - a NAT rebinding. Keep the node, fetch a token
		// for where we are now.
		if (cause instanceof InvalidTokenException && slot.confirmed) {
			retoken(watch, slot);
			return;
		}

		long exclude;
		if (cause instanceof InvalidTokenException) {
			// Our token went stale, which says nothing against the node: try again with a fresh one.
			exclude = 0;
		} else if (cause instanceof KadException ke && ke.getCode() == ErrorCode.MethodUnknown.value()) {
			exclude = EXCLUDE_UNSUPPORTED;
		} else if (cause == null && !slot.confirmed) {
			// A node that predates subscriptions cannot parse the request at all, so it never answers.
			exclude = EXCLUDE_UNSUPPORTED;
		} else {
			exclude = EXCLUDE_FAILED;
		}

		log.debug("{} subscription to {} with {} failed: {}", watch.topic.kind(), watch.topic.target(),
				slot.node.getId(), cause == null ? "timeout" : cause.getMessage());
		drop(watch, slot, exclude, true);
		if (exclude == 0)
			requestLookup(watch);
		else
			replace(watch);
	}

	/**
	 * Moves a subscription on one storing node to wherever that node now sees us: fetches a token for our
	 * current endpoint from that node alone, and subscribes again with it.
	 */
	private void retoken(Watch watch, Slot slot) {
		if (slot.retokening)
			return;

		slot.retokening = true;
		Message request = Message.findNodeRequest(watch.topic.target(), false, false, true);
		RpcCall call = new RpcCall(slot.node, request).addListener(new RpcCallListener() {
			@Override
			public void onStateChange(RpcCall c, RpcCall.State previous, RpcCall.State state) {
				if (state.isFinal())
					slot.retokening = false;
			}

			@Override
			public void onResponse(RpcCall c) {
				if (watch.cancelled || watch.slots.get(slot.node.getId()) != slot)
					return;

				int token = c.getResponse().<FindNodeResponse>getBody().getToken();
				if (token == 0) {
					drop(watch, slot, EXCLUDE_FAILED, true);
					replace(watch);
					return;
				}

				slot.token = token;
				send(watch, slot);
			}

			@Override
			public void onError(RpcCall c) {
				if (!watch.cancelled && watch.slots.get(slot.node.getId()) == slot) {
					drop(watch, slot, EXCLUDE_FAILED, true);
					replace(watch);
				}
			}

			@Override
			public void onTimeout(RpcCall c) {
				onError(c);
			}
		});

		host.send(call).onFailure(e -> {
			slot.retokening = false;
			if (!watch.cancelled && watch.slots.get(slot.node.getId()) == slot) {
				drop(watch, slot, EXCLUDE_FAILED, true);
				replace(watch);
			}
		});
	}

	/**
	 * Takes the endpoint a storing node reports seeing us at. A different one from where it accepted the
	 * subscription means our NAT has moved us, and its pushes are going somewhere we no longer are.
	 */
	private void observedBy(Id nodeId, InetSocketAddress seen) {
		for (Watch watch : List.copyOf(watches.values())) {
			Slot slot = watch.slots.get(nodeId);
			if (slot == null || !slot.confirmed || slot.observed == null || slot.observed.equals(seen))
				continue;

			log.debug("{} now sees us at {} instead of {}, moving the {} subscription to {}", nodeId, seen,
					slot.observed, watch.topic.kind(), watch.topic.target());
			retoken(watch, slot);
		}
	}

	private void drop(Watch watch, Slot slot, long exclude, boolean quiet) {
		watch.slots.remove(slot.node.getId());
		cancelTimer(slot.renewTimer);
		if (exclude > 0)
			watch.excluded.put(slot.node.getId(), host.now() + exclude);
		if (!quiet && slot.confirmed) {
			Message request = Message.subscribeRequest(watch.topic.target(), watch.topic.kind(), 0, -1, 0);
			host.send(new RpcCall(slot.node, request));
		}
		reportStatus(watch);
	}

	/**
	 * Looks the target up again and moves the subscription to the nodes now closest.
	 */
	private void rehome(Watch watch) {
		watch.rehomeTimer = -1;
		if (watch.cancelled)
			return;

		watch.rehomeTimer = host.setTimer(REHOME_INTERVAL, () -> rehome(watch));
		// A lookup already under way - a replacement - will do; the next round catches up.
		if (watch.lookupPending)
			return;

		watch.lookupPending = true;
		watch.lastLookupAt = host.now();
		host.lookup(watch.topic.target()).onComplete(ar -> {
			watch.lookupPending = false;
			if (watch.cancelled)
				return;

			if (ar.succeeded() && !ar.result().isEmpty()) {
				watch.candidates = ar.result();
				watch.candidatesAt = host.now();

				// The nodes we would choose now; any other we hold is let go.
				Set<Id> wanted = new HashSet<>();
				Set<InetAddress> units = new HashSet<>();
				for (Candidate candidate : watch.candidates) {
					if (wanted.size() >= SUBSCRIBE_NODES)
						break;
					NodeInfo node = candidate.node();
					if (candidate.token() == 0 || watch.excluded.containsKey(node.getId()) || node.getIpAddress() == null)
						continue;
					if (units.add(SourceKey.of(node.getIpAddress())))
						wanted.add(node.getId());
				}

				for (Slot slot : new ArrayList<>(watch.slots.values())) {
					if (!wanted.contains(slot.node.getId()))
						drop(watch, slot, 0, false);
				}
			}

			fill(watch);
		});
	}

	private void ensureKeepalive() {
		if (keepaliveTimer != -1)
			return;

		keepaliveTimer = host.setTimer(NAT_KEEPALIVE_INTERVAL, this::keepalive);
	}

	/**
	 * Pings each storing node once, however many subscriptions it carries for us.
	 */
	private void keepalive() {
		keepaliveTimer = -1;
		if (watches.isEmpty())
			return;

		if (host.needsNatKeepalive()) {
			Map<Id, NodeInfo> nodes = new HashMap<>();
			for (Watch watch : watches.values()) {
				for (Slot slot : watch.slots.values()) {
					if (slot.confirmed)
						nodes.putIfAbsent(slot.node.getId(), slot.node);
				}
			}

			for (NodeInfo node : nodes.values()) {
				RpcCall ping = new RpcCall(node, Message.pingRequest()).addListener(new RpcCallListener() {
					@Override
					public void onStateChange(RpcCall c, RpcCall.State previous, RpcCall.State state) {
					}

					@Override
					public void onResponse(RpcCall c) {
						InetSocketAddress seen = c.getResponse().getObserved();
						if (seen != null)
							observedBy(node.getId(), seen);
					}
				});
				host.send(ping);
			}
		}

		ensureKeepalive();
	}

	private void reportStatus(Watch watch) {
		int n = watch.subscribedNodes();
		if (n == watch.reportedStatus)
			return;

		watch.reportedStatus = n;
		watch.sink.onStatus(n);
	}

	private void cancelTimer(long id) {
		if (id != -1)
			host.cancelTimer(id);
	}
}
