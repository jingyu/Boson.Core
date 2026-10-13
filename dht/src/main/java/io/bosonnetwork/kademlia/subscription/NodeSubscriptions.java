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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Subscription;
import io.bosonnetwork.SubscriptionListener;
import io.bosonnetwork.Value;
import io.bosonnetwork.kademlia.protocol.SubscriptionKind;

/**
 * The node-level view of subscriptions: one per target however many listeners want it, across both
 * address families, and including what this node writes to its own storage.
 * <p>
 * A {@link SubscriptionClient} watches a target once per address family and refuses a second watch of the
 * same target, so listeners are merged here. Each listener still sees its own sequence: a record is
 * delivered to it only when newer than what it already had, and one joining late is caught up from the last
 * record seen.
 * </p>
 * <p>
 * Records reach this from three sources - each family's storing nodes, and this node's own storage, which
 * holds the target whenever this node is among the closest to it - and are deduplicated across all three.
 * </p>
 * <p>
 * Not thread-safe: every method must be called on the owner's context, and deliveries are made there.
 * </p>
 */
public class NodeSubscriptions {
	/** Peer records remembered per target, to catch up a listener that joins late. */
	static final int MAX_CACHED_PEERS = 256;

	/**
	 * One address family's DHT, as far as subscriptions go.
	 */
	public interface Family {
		/**
		 * Subscribes on this family.
		 *
		 * @param kind     what to watch.
		 * @param target   the value id or peer id.
		 * @param knownSeq values: the sequence number already held, or -1 for none.
		 * @param sink     where deliveries go; called on the family's own context.
		 * @return a future of the family's subscription, as soon as it exists.
		 */
		Future<SubscriptionClient.Watch> subscribe(SubscriptionKind kind, Id target, int knownSeq,
				SubscriptionClient.Sink sink);

		/**
		 * Cancels a family's subscription.
		 *
		 * @param watch the subscription.
		 */
		void unsubscribe(SubscriptionClient.Watch watch);
	}

	/**
	 * What this needs from the node that owns it.
	 */
	public interface Host {
		/**
		 * Runs an action on the owner's context.
		 *
		 * @param action the action.
		 */
		void runOnContext(Runnable action);

		/**
		 * Reads the value this node itself stores under an id.
		 *
		 * @param id the value id.
		 * @return a future of the value, or of null.
		 */
		Future<Value> localValue(Id id);

		/**
		 * Reads the peer records this node itself stores under an id.
		 *
		 * @param id the peer id.
		 * @return a future of the records.
		 */
		Future<List<PeerInfo>> localPeers(Id id);
	}

	private record Topic(SubscriptionKind kind, Id target) { }

	private final Host host;
	private final Map<Topic, Hub> hubs;

	private static final Logger log = LoggerFactory.getLogger(NodeSubscriptions.class);

	/**
	 * Creates the node-level subscriptions.
	 *
	 * @param host what this needs from its owner.
	 */
	public NodeSubscriptions(Host host) {
		this.host = host;
		this.hubs = new HashMap<>();
	}

	/**
	 * Subscribes a listener.
	 *
	 * @param families the address families to subscribe on; none fails the subscription.
	 * @param kind     what to watch.
	 * @param target   the value id or peer id.
	 * @param knownSeq values: the sequence number the listener already holds, or -1 for none.
	 * @param listener the listener.
	 * @return a future of the subscription, completing once a storing node on some family is watching, or
	 *         once every family's first attempt has run its course without one - the subscription then goes
	 *         on trying, and its count says when a node is watching. It fails only when no family can carry
	 *         it at all: the node is not running.
	 */
	public Future<Subscription> subscribe(List<Family> families, SubscriptionKind kind, Id target, int knownSeq,
			SubscriptionListener<?> listener) {
		if (families.isEmpty())
			return Future.failedFuture(new IllegalStateException("No DHT available"));

		Topic topic = new Topic(kind, target);
		Hub hub = hubs.get(topic);
		boolean created = hub == null;
		if (created) {
			hub = new Hub(topic);
			hubs.put(topic, hub);
		}

		// Joined before the hub opens: a family can fail synchronously while opening and close the hub, and a
		// listener joining after that would wait on a future nothing completes.
		Handle handle = new Handle(hub, listener, kind == SubscriptionKind.VALUE ? knownSeq : -1);
		hub.join(handle);
		if (created)
			hub.open(families);

		return handle.ready.future();
	}

	/**
	 * Takes a value this node's storage now holds.
	 *
	 * @param value the effective stored value.
	 */
	public void valueStored(Value value) {
		Hub hub = hubs.get(new Topic(SubscriptionKind.VALUE, value.getId()));
		if (hub != null) {
			log.debug("This node's storage holds value {} seq {}", value.getId(), value.getSequenceNumber());
			hub.offer(value);
		}
	}

	/**
	 * Takes a peer record this node's storage now holds.
	 *
	 * @param peer the effective stored record.
	 */
	public void peerStored(PeerInfo peer) {
		Hub hub = hubs.get(new Topic(SubscriptionKind.PEER, peer.getId()));
		if (hub != null)
			hub.offer(peer);
	}

	/**
	 * Cancels every subscription, for when the node stops.
	 */
	public void clear() {
		for (Hub hub : List.copyOf(hubs.values()))
			hub.close(new CancellationException("Node stopped"));
		hubs.clear();
	}

	/**
	 * Returns the number of targets watched.
	 *
	 * @return the count.
	 */
	public int size() {
		return hubs.size();
	}

	/**
	 * One target, shared by every listener that wants it.
	 */
	private final class Hub {
		private final Topic topic;
		private final List<Handle> handles;
		private final List<Family> families;
		private final Map<Family, SubscriptionClient.Watch> watches;
		private final Map<Family, Integer> status;
		private final Map<Long, PeerInfo> peers;
		private Value latest;
		private int familiesSettled;
		private int familiesGone;
		private boolean ready;
		private boolean closed;
		private int reportedStatus;

		private Hub(Topic topic) {
			this.topic = topic;
			this.handles = new ArrayList<>();
			this.families = new ArrayList<>();
			this.watches = new HashMap<>();
			this.status = new HashMap<>();
			this.peers = new LinkedHashMap<>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, PeerInfo> eldest) {
					return size() > MAX_CACHED_PEERS;
				}
			};
		}

		private void open(List<Family> families) {
			this.families.addAll(families);

			// What this node stores itself is delivered at once, and never pushed to us by anyone else.
			if (topic.kind() == SubscriptionKind.VALUE) {
				host.localValue(topic.target()).onSuccess(value -> host.runOnContext(() -> {
					if (value != null)
						offer(value);
				}));
			} else {
				host.localPeers(topic.target()).onSuccess(list -> host.runOnContext(() -> {
					if (list != null)
						list.forEach(this::offer);
				}));
			}

			for (Family family : families) {
				// Always from nothing: the per-listener sequence is enforced below, and subscribing from
				// the first listener's sequence would leave a later one holding less with nothing to catch
				// it up from.
				SubscriptionClient.Sink sink = new SubscriptionClient.Sink() {
					@Override
					public void onValue(Value value) {
						host.runOnContext(() -> offer(value));
					}

					@Override
					public void onPeer(PeerInfo peer) {
						host.runOnContext(() -> offer(peer));
					}

					@Override
					public void onStatus(int subscribedNodes) {
						host.runOnContext(() -> familyStatus(family, subscribedNodes));
					}
				};

				family.subscribe(topic.kind(), topic.target(), -1, sink).onComplete(ar -> host.runOnContext(() -> {
					if (ar.failed()) {
						familyGone(ar.cause());
						return;
					}

					SubscriptionClient.Watch watch = ar.result();
					if (closed) {
						family.unsubscribe(watch);
						return;
					}

					watches.put(family, watch);
					watch.ready().onComplete(r -> host.runOnContext(() -> {
						if (r.succeeded())
							familySettled();
						else
							familyGone(r.cause());
					}));
				}));
			}
		}

		private void join(Handle handle) {
			if (closed) {
				handle.cancelled = true;
				handle.ready.tryFail(new IllegalStateException("Subscription closed"));
				return;
			}

			handles.add(handle);
			if (ready)
				handle.ready.tryComplete(handle);

			// Catch the new listener up from what has been seen already.
			if (latest != null)
				handle.offer(latest);
			for (PeerInfo peer : List.copyOf(peers.values()))
				handle.offer(peer);
		}

		private void leave(Handle handle) {
			handles.remove(handle);
			if (handles.isEmpty())
				close(null);
		}

		private void becomeReady() {
			if (ready || closed)
				return;

			ready = true;
			for (Handle handle : List.copyOf(handles))
				handle.ready.tryComplete(handle);
		}

		/**
		 * A family's first attempt has run its course - with a node watching, or with none and still trying.
		 * Ready once every family's has: waiting longer would only keep the caller waiting on a network that
		 * may not come back soon, and the status tells it when one does.
		 */
		private void familySettled() {
			if (closed)
				return;

			familiesSettled++;
			if (familiesSettled + familiesGone >= families.size())
				becomeReady();
		}

		/**
		 * A family cannot carry the subscription at all - its DHT is not running, or stopped. Not a network
		 * condition, which only ever changes the count: this is the node going away. With no family left the
		 * subscription ends, and a listener still waiting is told why.
		 */
		private void familyGone(Throwable cause) {
			if (closed)
				return;

			familiesGone++;
			if (familiesGone >= families.size()) {
				log.debug("{} subscription to {} has no address family left to run on", topic.kind(), topic.target(),
						cause);
				close(cause);
			} else if (familiesSettled + familiesGone >= families.size()) {
				becomeReady();
			}
		}

		private void close(Throwable cause) {
			if (closed)
				return;

			closed = true;
			hubs.remove(topic, this);
			for (Map.Entry<Family, SubscriptionClient.Watch> e : watches.entrySet())
				e.getKey().unsubscribe(e.getValue());
			watches.clear();

			for (Handle handle : List.copyOf(handles)) {
				handle.cancelled = true;
				handle.ready.tryFail(cause != null ? cause : new CancellationException("Subscription cancelled"));
			}
			handles.clear();
		}

		private void offer(Value value) {
			if (closed)
				return;

			if (latest == null || value.getSequenceNumber() > latest.getSequenceNumber()) {
				log.debug("Value {} seq {} is new to this node's listeners", value.getId(), value.getSequenceNumber());
				latest = value;
			}

			for (Handle handle : List.copyOf(handles))
				handle.offer(value);
		}

		private void offer(PeerInfo peer) {
			if (closed)
				return;

			PeerInfo cached = peers.get(peer.getFingerprint());
			if (cached == null || peer.getSequenceNumber() > cached.getSequenceNumber())
				peers.put(peer.getFingerprint(), peer);

			for (Handle handle : List.copyOf(handles))
				handle.offer(peer);
		}

		private void familyStatus(Family family, int subscribedNodes) {
			if (closed)
				return;

			status.put(family, subscribedNodes);
			// One family with a node watching is enough to be ready; the other may take longer, or never.
			if (subscribedNodes > 0)
				becomeReady();

			int total = subscribedNodes();
			if (total == reportedStatus)
				return;

			reportedStatus = total;
			for (Handle handle : List.copyOf(handles))
				handle.status(total);
		}

		private int subscribedNodes() {
			return status.values().stream().mapToInt(Integer::intValue).sum();
		}
	}

	/**
	 * One listener's subscription.
	 */
	private final class Handle implements Subscription {
		private final Hub hub;
		private final SubscriptionListener<Object> listener;
		private final Promise<Subscription> ready;
		private final Map<Long, Integer> deliveredPeers;
		private int knownSeq;
		private volatile boolean cancelled;

		@SuppressWarnings("unchecked")
		private Handle(Hub hub, SubscriptionListener<?> listener, int knownSeq) {
			this.hub = hub;
			this.listener = (SubscriptionListener<Object>) listener;
			this.ready = Promise.promise();
			this.deliveredPeers = new LinkedHashMap<>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
					return size() > MAX_CACHED_PEERS;
				}
			};
			this.knownSeq = knownSeq;
		}

		private void offer(Value value) {
			if (cancelled || value.getSequenceNumber() <= knownSeq)
				return;

			knownSeq = value.getSequenceNumber();
			deliver(value);
		}

		private void offer(PeerInfo peer) {
			if (cancelled)
				return;

			Integer delivered = deliveredPeers.get(peer.getFingerprint());
			if (delivered != null && peer.getSequenceNumber() <= delivered)
				return;

			deliveredPeers.put(peer.getFingerprint(), peer.getSequenceNumber());
			deliver(peer);
		}

		private void deliver(Object record) {
			try {
				listener.onUpdate(record);
			} catch (Exception e) {
				log.error("Subscription listener for {} failed", hub.topic.target(), e);
			}
		}

		private void status(int subscribedNodes) {
			if (cancelled)
				return;

			try {
				listener.onStatusChange(subscribedNodes);
			} catch (Exception e) {
				log.error("Subscription listener for {} failed", hub.topic.target(), e);
			}
		}

		@Override
		public Id getTarget() {
			return hub.topic.target();
		}

		@Override
		public int getSubscribedNodes() {
			return cancelled ? 0 : hub.reportedStatus;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}

		@Override
		public CompletableFuture<Void> cancel() {
			CompletableFuture<Void> future = new CompletableFuture<>();
			host.runOnContext(() -> {
				if (!cancelled) {
					cancelled = true;
					ready.tryFail(new CancellationException("Subscription cancelled"));
					hub.leave(this);
				}
				future.complete(null);
			});
			return future;
		}
	}
}
