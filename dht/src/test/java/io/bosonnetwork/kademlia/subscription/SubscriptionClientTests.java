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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.kademlia.exceptions.SubscriptionRefusedException;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.protocol.SubscribeRequest;
import io.bosonnetwork.kademlia.protocol.SubscriptionKind;
import io.bosonnetwork.kademlia.rpc.RpcCall;

class SubscriptionClientTests {
	private long now;
	private List<RpcCall> sent;
	private Map<Long, long[]> timers;
	private Map<Long, Runnable> timerActions;
	private long nextTimer;
	private List<SubscriptionClient.Candidate> candidates;
	private int lookups;
	private boolean behindNat;
	private List<Message> reported;
	private SubscriptionClient client;

	private List<Value> values;
	private List<PeerInfo> peers;
	private List<Integer> statuses;
	private SubscriptionClient.Sink sink;

	@BeforeEach
	void setUp() {
		now = 1_000_000;
		sent = new ArrayList<>();
		timers = new HashMap<>();
		timerActions = new HashMap<>();
		candidates = new ArrayList<>();
		reported = new ArrayList<>();
		behindNat = true;
		values = new ArrayList<>();
		peers = new ArrayList<>();
		statuses = new ArrayList<>();
		sink = new SubscriptionClient.Sink() {
			@Override
			public void onValue(Value value) {
				values.add(value);
			}

			@Override
			public void onPeer(PeerInfo peer) {
				peers.add(peer);
			}

			@Override
			public void onStatus(int subscribedNodes) {
				statuses.add(subscribedNodes);
			}
		};

		client = new SubscriptionClient(new SubscriptionClient.Host() {
			@Override
			public long now() {
				return now;
			}

			@Override
			public Future<RpcCall> send(RpcCall call) {
				sent.add(call);
				return Future.succeededFuture(call);
			}

			@Override
			public Future<List<SubscriptionClient.Candidate>> lookup(Id target) {
				lookups++;
				return Future.succeededFuture(List.copyOf(candidates));
			}

			@Override
			public long setTimer(long delay, Runnable action) {
				long id = nextTimer++;
				timers.put(id, new long[] { now + delay });
				timerActions.put(id, action);
				return id;
			}

			@Override
			public void cancelTimer(long id) {
				timers.remove(id);
				timerActions.remove(id);
			}

			@Override
			public boolean needsNatKeepalive() {
				return behindNat;
			}

			@Override
			public <T> Future<T> executeBlocking(Callable<T> task) {
				try {
					return Future.succeededFuture(task.call());
				} catch (Exception e) {
					return Future.failedFuture(e);
				}
			}

			@Override
			public void reportInvalid(Message notification) {
				reported.add(notification);
			}
		});
	}

	private void advance(long millis) {
		now += millis;
		boolean ran;
		do {
			ran = false;
			for (Map.Entry<Long, long[]> e : new ArrayList<>(timers.entrySet())) {
				if (e.getValue()[0] <= now) {
					timers.remove(e.getKey());
					Runnable action = timerActions.remove(e.getKey());
					action.run();
					ran = true;
					break;
				}
			}
		} while (ran);
	}

	private SubscriptionClient.Candidate candidate(String host, int token) {
		return new SubscriptionClient.Candidate(NodeInfo.of(Id.random(), new InetSocketAddress(host, 39001)), token);
	}

	private static void invoke(RpcCall call, String name, Class<?> argType, Object arg) {
		try {
			Method m = RpcCall.class.getDeclaredMethod(name, argType);
			m.setAccessible(true);
			m.invoke(call, arg);
		} catch (Exception e) {
			throw new RuntimeException(name + " failed", e);
		}
	}

	private static void accept(RpcCall call, int token, int lease) {
		invoke(call, "respond", Message.class, Message.subscribeResponse(call.getTxid(), token, lease));
	}

	private static void refuse(RpcCall call) {
		invoke(call, "fail", Throwable.class, new SubscriptionRefusedException("full"));
	}

	private static void timeout(RpcCall call) {
		invoke(call, "updateState", RpcCall.State.class, RpcCall.State.TIMEOUT);
	}

	private List<RpcCall> subscribes() {
		return sent.stream().filter(c -> c.getRequest().getMethod() == Message.Method.SUBSCRIBE).toList();
	}

	private List<RpcCall> subscribesTo(Id node) {
		return subscribes().stream().filter(c -> c.getTargetId().equals(node)).toList();
	}

	private static SubscribeRequest body(RpcCall call) {
		return call.getRequest().getBody();
	}

	private Message notification(Id sender, Value value) {
		Message msg = Message.notifyValueRequest(value);
		msg.setId(sender);
		msg.setRemote(sender, new InetSocketAddress("12.1.1.1", 39001));
		return msg;
	}

	private Message notification(Id sender, PeerInfo peer) {
		Message msg = Message.notifyPeerRequest(peer);
		msg.setId(sender);
		msg.setRemote(sender, new InetSocketAddress("12.1.1.1", 39001));
		return msg;
	}

	@Test
	void testSubscribesToTheClosestNodesInDistinctSourceUnits() {
		candidates.add(candidate("12.1.1.1", 11));
		candidates.add(candidate("12.1.1.1", 12)); // same host as the closest: skipped
		candidates.add(candidate("12.1.1.2", 13));
		candidates.add(candidate("12.1.1.3", 0));  // no token: skipped
		candidates.add(candidate("12.1.1.4", 15));
		candidates.add(candidate("12.1.1.5", 16));
		candidates.add(candidate("12.1.1.6", 17));

		Id target = Id.random();
		client.subscribe(SubscriptionKind.VALUE, target, 7, sink);

		List<RpcCall> calls = subscribes();
		assertEquals(SubscriptionClient.SUBSCRIBE_NODES, calls.size());
		assertEquals(List.of(11, 13, 15, 16), calls.stream().map(c -> body(c).getToken()).toList());
		for (RpcCall call : calls) {
			assertEquals(target, body(call).getTarget());
			assertEquals(SubscriptionKind.VALUE, body(call).getKind());
			assertEquals(7, body(call).getKnownSequenceNumber());
			assertEquals(SubscriptionClient.REQUESTED_LEASE, body(call).getLease());
		}
	}

	@Test
	void testAcceptanceCompletesAndRenewsWithTheReturnedToken() {
		candidates.add(candidate("12.1.1.1", 11));
		SubscriptionClient.Watch watch = client.subscribe(SubscriptionKind.VALUE, Id.random(), -1, sink);
		assertFalse(watch.ready().isComplete());

		accept(subscribes().get(0), 999, 600);
		assertTrue(watch.ready().succeeded());
		assertEquals(1, watch.subscribedNodes());
		assertEquals(List.of(1), statuses);

		advance(SubscriptionClient.RENEW_INTERVAL);
		List<RpcCall> calls = subscribes();
		assertEquals(2, calls.size());
		assertEquals(999, body(calls.get(1)).getToken(), "a renewal presents the token the last one returned");
	}

	@Test
	void testARenewalCarriesWhatHasBeenDeliveredSince() throws Exception {
		SubscriptionClient.Candidate node = candidate("12.1.1.1", 11);
		candidates.add(node);
		Signature.KeyPair owner = Signature.KeyPair.random();
		Value v3 = Value.signedBuilder().key(owner).sequenceNumber(3).data("x".getBytes()).build();

		client.subscribe(SubscriptionKind.VALUE, v3.getId(), -1, sink);
		accept(subscribes().get(0), 999, 600);
		assertTrue(client.onNotify(notification(node.node().getId(), v3)));

		advance(SubscriptionClient.RENEW_INTERVAL);
		assertEquals(3, body(subscribes().get(1)).getKnownSequenceNumber());
	}

	@Test
	void testARefusedNodeIsReplacedByTheNextCandidate() {
		for (int i = 1; i <= 6; i++)
			candidates.add(candidate("12.1.1." + i, 10 + i));

		client.subscribe(SubscriptionKind.VALUE, Id.random(), -1, sink);
		assertEquals(4, subscribes().size());

		refuse(subscribes().get(0));
		assertEquals(5, subscribes().size());
		assertEquals(15, body(subscribes().get(4)).getToken());

		// A node that never answers - one predating subscriptions - is replaced too.
		timeout(subscribes().get(1));
		assertEquals(6, subscribes().size());
		assertEquals(16, body(subscribes().get(5)).getToken());
	}

	@Test
	void testReadyFailsWhenNoNodeAccepts() {
		candidates.add(candidate("12.1.1.1", 11));
		SubscriptionClient.Watch watch = client.subscribe(SubscriptionKind.VALUE, Id.random(), -1, sink);
		refuse(subscribes().get(0));
		assertTrue(watch.ready().failed());
	}

	@Test
	void testReadyFailsWhenTheLookupFindsNothing() {
		SubscriptionClient.Watch watch = client.subscribe(SubscriptionKind.VALUE, Id.random(), -1, sink);
		assertTrue(watch.ready().failed());
	}

	@Test
	void testNotificationsAreAcceptedOnlyFromSubscribedNodesAndDeduplicated() throws Exception {
		SubscriptionClient.Candidate a = candidate("12.1.1.1", 11);
		SubscriptionClient.Candidate b = candidate("12.1.1.2", 12);
		candidates.add(a);
		candidates.add(b);

		Signature.KeyPair owner = Signature.KeyPair.random();
		Value v1 = Value.signedBuilder().key(owner).sequenceNumber(1).data("1".getBytes()).build();
		Value v2 = Value.signedBuilder().key(owner).sequenceNumber(2).data("2".getBytes()).build();
		client.subscribe(SubscriptionKind.VALUE, v1.getId(), -1, sink);

		assertFalse(client.onNotify(notification(Id.random(), v1)), "a node we did not subscribe to");
		assertFalse(client.onNotify(notification(a.node().getId(),
				Value.signedBuilder().data("other".getBytes()).build())), "a target we do not watch");

		// Accepted before the SUBSCRIBE answer: the initial push can overtake it.
		assertTrue(client.onNotify(notification(a.node().getId(), v2)));
		assertTrue(client.onNotify(notification(b.node().getId(), v2)));
		assertTrue(client.onNotify(notification(b.node().getId(), v1)));
		assertEquals(List.of(v2), values, "each version once, never an older one");
	}

	@Test
	void testAnInvalidRecordIsReportedAndItsNodeReplaced() throws Exception {
		for (int i = 1; i <= 5; i++)
			candidates.add(candidate("12.1.1." + i, 10 + i));

		Signature.KeyPair owner = Signature.KeyPair.random();
		Value good = Value.signedBuilder().key(owner).sequenceNumber(4).data("good".getBytes()).build();
		Value forged = Value.of(good.getPublicKey(), null, null, null, 5, Random.randomBytes(64), "evil".getBytes());

		SubscriptionClient.Watch watch = client.subscribe(SubscriptionKind.VALUE, good.getId(), -1, sink);
		Id liar = candidates.get(0).node().getId();
		assertTrue(watch.nodes().contains(liar));

		assertTrue(client.onNotify(notification(liar, forged)));
		assertTrue(values.isEmpty());
		assertEquals(1, reported.size());
		assertFalse(watch.nodes().contains(liar));
		assertEquals(SubscriptionClient.SUBSCRIBE_NODES, watch.nodes().size(), "replaced by the next candidate");
	}

	@Test
	void testPeersAreDeduplicatedPerFingerprint() throws Exception {
		SubscriptionClient.Candidate a = candidate("12.1.1.1", 11);
		candidates.add(a);
		Signature.KeyPair key = Signature.KeyPair.random();
		PeerInfo p1 = PeerInfo.builder().key(key).fingerprint(1).sequenceNumber(1).endpoint("tcp://203.0.113.10:1").build();
		PeerInfo p2 = PeerInfo.builder().key(key).fingerprint(2).sequenceNumber(1).endpoint("tcp://203.0.113.10:2").build();
		PeerInfo p1v2 = PeerInfo.builder().key(key).fingerprint(1).sequenceNumber(2).endpoint("tcp://203.0.113.10:3").build();

		client.subscribe(SubscriptionKind.PEER, p1.getId(), -1, sink);
		Id node = a.node().getId();
		client.onNotify(notification(node, p1));
		client.onNotify(notification(node, p2));
		client.onNotify(notification(node, p1));
		client.onNotify(notification(node, p1v2));
		assertEquals(List.of(p1, p2, p1v2), peers);
	}

	@Test
	void testTheNatKeepalivePingsEachStoringNodeOnce() {
		for (int i = 1; i <= 4; i++)
			candidates.add(candidate("12.1.1." + i, 10 + i));

		// Two targets on the same four nodes: still one ping per node.
		client.subscribe(SubscriptionKind.VALUE, Id.random(), -1, sink);
		client.subscribe(SubscriptionKind.PEER, Id.random(), -1, sink);
		subscribes().forEach(c -> accept(c, 1, 600));

		advance(SubscriptionClient.NAT_KEEPALIVE_INTERVAL);
		List<RpcCall> pings = sent.stream().filter(c -> c.getRequest().getMethod() == Message.Method.PING).toList();
		assertEquals(4, pings.size());
		assertEquals(4, pings.stream().map(RpcCall::getTargetId).collect(Collectors.toSet()).size());

		// Not behind NAT: nothing to keep open.
		behindNat = false;
		advance(SubscriptionClient.NAT_KEEPALIVE_INTERVAL);
		assertEquals(4, sent.stream().filter(c -> c.getRequest().getMethod() == Message.Method.PING).count());
	}

	@Test
	void testCancelTellsTheConfirmedNodesAndStops() {
		candidates.add(candidate("12.1.1.1", 11));
		candidates.add(candidate("12.1.1.2", 12));
		SubscriptionClient.Watch watch = client.subscribe(SubscriptionKind.VALUE, Id.random(), -1, sink);
		accept(subscribes().get(0), 1, 600); // the second never answers

		watch.cancel();
		List<RpcCall> cancels = subscribes().stream().filter(c -> body(c).isCancel()).toList();
		assertEquals(1, cancels.size(), "only a node that accepted has anything to release");
		assertEquals(0, body(cancels.get(0)).getToken());
		assertEquals(0, client.size());

		int before = sent.size();
		advance(SubscriptionClient.REHOME_INTERVAL);
		assertEquals(before, sent.size(), "no renewal, keepalive or re-lookup after a cancel");
	}

	@Test
	void testRehomingMovesToTheNodesNowClosest() {
		SubscriptionClient.Candidate old = candidate("12.1.1.1", 11);
		candidates.add(old);
		SubscriptionClient.Watch watch = client.subscribe(SubscriptionKind.VALUE, Id.random(), -1, sink);
		accept(subscribes().get(0), 1, 600);
		int lookupsBefore = lookups;

		// The closest set has moved on: four new nodes, the old one gone.
		candidates.clear();
		for (int i = 2; i <= 5; i++)
			candidates.add(candidate("12.1.1." + i, 10 + i));

		// Renewals keep the old slot confirmed along the way.
		for (long t = 0; t < SubscriptionClient.REHOME_INTERVAL; t += SubscriptionClient.RENEW_INTERVAL) {
			advance(Math.min(SubscriptionClient.RENEW_INTERVAL, SubscriptionClient.REHOME_INTERVAL - t));
			subscribes().stream().filter(c -> c.getState() == RpcCall.State.UNSENT && !body(c).isCancel())
					.forEach(c -> accept(c, 1, 600));
		}

		assertTrue(lookups > lookupsBefore);
		Set<Id> expected = candidates.stream().map(c -> c.node().getId()).collect(Collectors.toSet());
		assertEquals(expected, watch.nodes());
		assertEquals(1, subscribesTo(old.node().getId()).stream().filter(c -> body(c).isCancel()).count(),
				"the node let go is told");
	}
}
