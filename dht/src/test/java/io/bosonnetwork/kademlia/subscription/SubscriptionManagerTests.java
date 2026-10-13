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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.kademlia.exceptions.MessageTooBigException;
import io.bosonnetwork.kademlia.exceptions.SubscriptionRefusedException;
import io.bosonnetwork.kademlia.exceptions.UnknownSubscriptionException;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.protocol.NotifyPeerRequest;
import io.bosonnetwork.kademlia.protocol.NotifyValueRequest;
import io.bosonnetwork.kademlia.protocol.SubscriptionKind;
import io.bosonnetwork.kademlia.rpc.CallRejectedException;
import io.bosonnetwork.kademlia.rpc.RpcCall;

class SubscriptionManagerTests {
	private long now;
	private List<RpcCall> sent;
	private List<long[]> timerTimes;
	private List<Runnable> timerActions;
	private Map<Id, Value> values;
	private Map<Id, List<PeerInfo>> peers;
	// Decides, per call, whether the RPC server refuses it: null to send, a reason to refuse.
	private Function<RpcCall, CallRejectedException.Reason> refuse;
	private SubscriptionManager manager;

	private final Id subscriber = Id.random();
	private final InetSocketAddress address = new InetSocketAddress("12.34.56.78", 39001);
	private Signature.KeyPair owner;
	private Id valueId;

	@BeforeEach
	void setUp() {
		now = 1_000_000;
		sent = new ArrayList<>();
		timerTimes = new ArrayList<>();
		timerActions = new ArrayList<>();
		values = new HashMap<>();
		peers = new HashMap<>();
		refuse = call -> null;
		owner = Signature.KeyPair.random();
		valueId = Id.of(owner.publicKey().bytes());

		manager = new SubscriptionManager(new SubscriptionManager.Host() {
			@Override
			public long now() {
				return now;
			}

			@Override
			public Future<RpcCall> send(RpcCall call) {
				sent.add(call);
				CallRejectedException.Reason reason = refuse.apply(call);
				if (reason == null)
					return Future.succeededFuture(call);

				// As RpcServer.reject does it: the call fails first, synchronously, then the future.
				CallRejectedException cause = new CallRejectedException(reason, "refused");
				fail(call, cause);
				return Future.failedFuture(cause);
			}

			@Override
			public Future<Value> getValue(Id id) {
				return Future.succeededFuture(values.get(id));
			}

			@Override
			public Future<List<PeerInfo>> getPeers(Id id) {
				return Future.succeededFuture(peers.getOrDefault(id, List.of()));
			}

			@Override
			public void setTimer(long delay, Runnable action) {
				timerTimes.add(new long[] { now + delay });
				timerActions.add(action);
			}
		});
	}

	/** Moves the clock and runs every timer that has come due, including ones those schedule. */
	private void advance(long millis) {
		now += millis;
		boolean ran;
		do {
			ran = false;
			for (int i = 0; i < timerTimes.size(); i++) {
				if (timerTimes.get(i)[0] <= now) {
					timerTimes.remove(i);
					Runnable action = timerActions.remove(i);
					action.run();
					ran = true;
					break;
				}
			}
		} while (ran);
	}

	private Value value(int seq) throws Exception {
		return Value.signedBuilder().key(owner).sequenceNumber(seq).data(("v" + seq).getBytes()).build();
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

	private static void ack(RpcCall call) {
		invoke(call, "respond", Message.class,
				Message.notifyResponse(call.getRequest().getMethod(), call.getTxid()));
	}

	private static void timeout(RpcCall call) {
		invoke(call, "updateState", RpcCall.State.class, RpcCall.State.TIMEOUT);
	}

	private static void fail(RpcCall call, Throwable cause) {
		invoke(call, "fail", Throwable.class, cause);
	}

	private static int seqOf(RpcCall call) {
		return call.getRequest().<NotifyValueRequest>getBody().getValue().getSequenceNumber();
	}

	private RpcCall last() {
		return sent.get(sent.size() - 1);
	}

	@Test
	void testLeaseIsClamped() throws Exception {
		assertEquals(SubscriptionManager.DEFAULT_LEASE,
				manager.subscribe(subscriber, address, SubscriptionKind.VALUE, Id.random(), -1, -1));
		assertEquals(SubscriptionManager.MIN_LEASE,
				manager.subscribe(subscriber, address, SubscriptionKind.VALUE, Id.random(), -1, 5));
		assertEquals(SubscriptionManager.MAX_LEASE,
				manager.subscribe(subscriber, address, SubscriptionKind.VALUE, Id.random(), -1, 65535));
	}

	@Test
	void testTheStoredValueIsPushedWhenNewerThanWhatTheSubscriberHolds() throws Exception {
		values.put(valueId, value(5));
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, 3, -1);

		assertEquals(1, sent.size());
		RpcCall call = sent.get(0);
		assertEquals(Message.Method.NOTIFY_VALUE, call.getRequest().getMethod());
		assertEquals(5, seqOf(call));
		assertEquals(subscriber, call.getTargetId());
		assertEquals(address, call.getTarget().getAddress());
		assertTrue(call.isLivenessNeutral(), "a push must not count towards the subscriber's liveness");
	}

	@Test
	void testNothingIsPushedToASubscriberThatIsUpToDate() throws Exception {
		values.put(valueId, value(5));
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, 5, -1);
		assertTrue(sent.isEmpty());
	}

	@Test
	void testAChangeIsPushedAndAKeepAliveIsNot() throws Exception {
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		assertTrue(sent.isEmpty());

		manager.valueStored(value(1));
		assertEquals(1, sent.size());
		ack(last());

		// The same record again - a re-announce - is not a change.
		advance(SubscriptionManager.PUSH_MIN_INTERVAL);
		manager.valueStored(value(1));
		assertEquals(1, sent.size());

		manager.valueStored(value(2));
		assertEquals(2, sent.size());
		assertEquals(2, seqOf(last()));
	}

	@Test
	void testABurstOfChangesCostsOnePushPerInterval() throws Exception {
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		manager.valueStored(value(1));
		assertEquals(1, sent.size());

		// While the first is in flight and within the interval, later versions only replace the pending one.
		manager.valueStored(value(2));
		manager.valueStored(value(3));
		manager.valueStored(value(4));
		assertEquals(1, sent.size());

		ack(sent.get(0));
		assertEquals(1, sent.size(), "the interval must hold even after the acknowledgement");

		advance(SubscriptionManager.PUSH_MIN_INTERVAL);
		assertEquals(2, sent.size());
		assertEquals(4, seqOf(last()), "only the latest version goes out");
	}

	@Test
	void testTheIntervalIsPerRecordNotPerSubscription() throws Exception {
		// Three instances of one service: one peer id, three fingerprints.
		Signature.KeyPair key = Signature.KeyPair.random();
		List<PeerInfo> instances = new ArrayList<>();
		for (int i = 0; i < 3; i++)
			instances.add(PeerInfo.builder().key(key).fingerprint(100 + i).sequenceNumber(1)
					.endpoint("tcp://203.0.113.10:" + (3000 + i)).build());
		Id peerId = instances.get(0).getId();
		peers.put(peerId, instances);

		manager.subscribe(subscriber, address, SubscriptionKind.PEER, peerId, -1, -1);

		// One in flight at a time, but three different instances need no interval between them.
		for (int i = 0; i < 3; i++) {
			assertEquals(i + 1, sent.size());
			assertEquals(Message.Method.NOTIFY_PEER, last().getRequest().getMethod());
			ack(last());
		}

		assertEquals(3, sent.size());
		assertEquals(3, sent.stream().map(c -> c.getRequest().<NotifyPeerRequest>getBody().getPeer().getFingerprint())
				.distinct().count());
	}

	@Test
	void testAPeerIsPushedOnlyWhenNewOrNewer() throws Exception {
		Signature.KeyPair key = Signature.KeyPair.random();
		PeerInfo v1 = PeerInfo.builder().key(key).fingerprint(7).sequenceNumber(1).endpoint("tcp://203.0.113.10:1").build();
		manager.subscribe(subscriber, address, SubscriptionKind.PEER, v1.getId(), -1, -1);

		manager.peerStored(v1);
		assertEquals(1, sent.size());
		ack(last());

		advance(SubscriptionManager.PUSH_MIN_INTERVAL);
		manager.peerStored(v1);
		assertEquals(1, sent.size(), "a re-announce of the same version is not a change");

		PeerInfo v2 = PeerInfo.builder().key(key).fingerprint(7).sequenceNumber(2).endpoint("tcp://203.0.113.10:2").build();
		manager.peerStored(v2);
		assertEquals(2, sent.size());
	}

	@Test
	void testRepeatedFailuresStallTheSubscriptionUntilItIsRenewed() throws Exception {
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		manager.valueStored(value(1));

		for (int i = 0; i < SubscriptionManager.MAX_CONSECUTIVE_FAILURES; i++) {
			assertEquals(i + 1, sent.size());
			timeout(last());
			advance(SubscriptionManager.PUSH_MIN_INTERVAL);
		}

		assertEquals(SubscriptionManager.MAX_CONSECUTIVE_FAILURES, sent.size());
		assertTrue(manager.get(subscriber, SubscriptionKind.VALUE, valueId).stalled);

		// Stalled: nothing more goes out, but the newest record is kept.
		manager.valueStored(value(2));
		advance(SubscriptionManager.PUSH_MIN_INTERVAL);
		assertEquals(SubscriptionManager.MAX_CONSECUTIVE_FAILURES, sent.size());
		assertEquals(1, manager.size(), "a failed push degrades the subscription, it does not remove it");

		// The renewal proves the subscriber reachable, and it says what it holds.
		values.put(valueId, value(2));
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		assertFalse(manager.get(subscriber, SubscriptionKind.VALUE, valueId).stalled);
		assertEquals(SubscriptionManager.MAX_CONSECUTIVE_FAILURES + 1, sent.size());
		assertEquals(2, seqOf(last()));
	}

	@Test
	void testUnknownSubscriptionDropsIt() throws Exception {
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		manager.valueStored(value(1));
		fail(last(), new UnknownSubscriptionException("gone"));
		assertEquals(0, manager.size());
	}

	@Test
	void testCancelAndLeaseExpiry() throws Exception {
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		assertTrue(manager.cancel(subscriber, SubscriptionKind.VALUE, valueId));
		assertFalse(manager.cancel(subscriber, SubscriptionKind.VALUE, valueId));
		assertEquals(0, manager.size());

		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, 120);
		advance(119_000);
		manager.sweep();
		assertEquals(1, manager.size());
		advance(1_000);
		manager.sweep();
		assertEquals(0, manager.size());
	}

	@Test
	void testARenewalFromANewAddressMovesTheSubscription() throws Exception {
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		InetSocketAddress moved = new InetSocketAddress("12.34.56.78", 40000);
		manager.subscribe(subscriber, moved, SubscriptionKind.VALUE, valueId, -1, -1);
		assertEquals(1, manager.size());

		manager.valueStored(value(1));
		assertEquals(moved, last().getTarget().getAddress());
	}

	/**
	 * A push in flight when the subscription moves went to where the subscriber no longer is. Waiting for it
	 * to time out would add a whole call timeout to the recovery from a NAT rebinding; it is abandoned instead
	 * and the record pushed to the new address at once.
	 */
	@Test
	void testAMoveAbandonsThePushInFlightToTheOldAddress() throws Exception {
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		manager.valueStored(value(1));
		RpcCall stale = last();
		assertEquals(address, stale.getTarget().getAddress());

		advance(SubscriptionManager.PUSH_MIN_INTERVAL);
		InetSocketAddress moved = new InetSocketAddress("12.34.56.78", 40000);
		manager.subscribe(subscriber, moved, SubscriptionKind.VALUE, valueId, -1, -1);
		assertEquals(2, sent.size(), "pushed again without waiting for the old push to time out");
		assertEquals(moved, last().getTarget().getAddress());

		// The abandoned push's timeout, when it comes, counts for nothing.
		timeout(stale);
		assertEquals(0, manager.get(subscriber, SubscriptionKind.VALUE, valueId).failures);
		ack(last());
		assertNull(manager.get(subscriber, SubscriptionKind.VALUE, valueId).pendingValue);
	}

	/**
	 * A mapping that lapsed and reopened on the same port: the subscriber cannot tell anything happened, so
	 * the storing node has to. A packet from the subscriber at the subscription's address resumes a stalled
	 * subscription at once, rather than at the next renewal.
	 */
	@Test
	void testAPacketFromTheSubscriberResumesAStalledSubscription() throws Exception {
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		manager.valueStored(value(1));
		for (int i = 0; i < SubscriptionManager.MAX_CONSECUTIVE_FAILURES; i++) {
			timeout(last());
			advance(SubscriptionManager.PUSH_MIN_INTERVAL);
		}
		assertTrue(manager.get(subscriber, SubscriptionKind.VALUE, valueId).stalled);
		int before = sent.size();

		// From somewhere else: not the mapping pushes go to, so no proof of anything.
		manager.heardFrom(subscriber, new InetSocketAddress("12.34.56.78", 40000));
		assertEquals(before, sent.size());
		// From the subscription's own address, by another node: not the subscriber.
		manager.heardFrom(Id.random(), address);
		assertEquals(before, sent.size());

		manager.heardFrom(subscriber, address);
		assertFalse(manager.get(subscriber, SubscriptionKind.VALUE, valueId).stalled);
		assertEquals(before + 1, sent.size());
		ack(last());
		assertNull(manager.get(subscriber, SubscriptionKind.VALUE, valueId).pendingValue);
	}

	@Test
	void testAPacketFromTheSubscriberResendsARetrySentIntoTheGap() throws Exception {
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		manager.valueStored(value(1));
		timeout(last());
		advance(SubscriptionManager.PUSH_MIN_INTERVAL);
		RpcCall retry = last();
		assertEquals(2, sent.size());

		// A first push in flight is left alone: nothing says it failed.
		manager.valueStored(value(2));

		advance(SubscriptionManager.PUSH_MIN_INTERVAL);
		manager.heardFrom(subscriber, address);
		assertEquals(3, sent.size(), "the retry in flight is resent now the path is open");
		timeout(retry);
		assertEquals(1, manager.get(subscriber, SubscriptionKind.VALUE, valueId).failures,
				"the abandoned retry's timeout counts for nothing");
		assertEquals(2, seqOf(last()), "and what goes out is the newest version");
	}

	@Test
	void testAFirstPushLongUnansweredIsResentWhenTheSubscriberIsHeardFrom() throws Exception {
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		manager.valueStored(value(1));
		assertEquals(1, sent.size());

		// Heard from at once: the push is simply on its way.
		manager.heardFrom(subscriber, address);
		assertEquals(1, sent.size());

		// Heard from long after: the push went into a gap the subscriber's packet has since closed.
		advance(SubscriptionManager.RESEND_WHEN_HEARD_AFTER);
		manager.heardFrom(subscriber, address);
		assertEquals(2, sent.size());
		assertEquals(1, seqOf(last()));
	}

	/**
	 * A record that does not fit one datagram is refused by the RPC server every time it is tried. Counted
	 * as an ordinary failure it would stall the subscription, and the renewal that resumes it would offer the
	 * record again, for ever. It is skipped instead, and stays skipped across renewals.
	 */
	@Test
	void testAnOversizedRecordIsSkippedNotRetried() throws Exception {
		values.put(valueId, value(1));
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		assertEquals(1, sent.size());
		fail(last(), new MessageTooBigException("too big"));

		SubscriptionManager.Subscription sub = manager.get(subscriber, SubscriptionKind.VALUE, valueId);
		assertEquals(0, sub.failures, "not a failure of the subscriber");
		assertFalse(sub.stalled);
		assertNull(sub.pendingValue);

		// A renewal says the subscriber holds nothing, and the store still holds the same value: not offered again.
		advance(SubscriptionManager.PUSH_MIN_INTERVAL);
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		assertEquals(1, sent.size());

		// A newer version is pushed as usual.
		manager.valueStored(value(2));
		assertEquals(2, sent.size());
		assertEquals(2, seqOf(last()));
	}

	@Test
	void testAnOversizedPeerRecordDoesNotHoldUpTheOthers() throws Exception {
		Signature.KeyPair key = Signature.KeyPair.random();
		PeerInfo big = PeerInfo.builder().key(key).fingerprint(1).sequenceNumber(1).endpoint("tcp://203.0.113.10:1").build();
		PeerInfo other = PeerInfo.builder().key(key).fingerprint(2).sequenceNumber(1).endpoint("tcp://203.0.113.10:2").build();
		peers.put(big.getId(), List.of(big, other));
		manager.subscribe(subscriber, address, SubscriptionKind.PEER, big.getId(), -1, -1);

		assertEquals(1, last().getRequest().<NotifyPeerRequest>getBody().getPeer().getFingerprint());
		fail(last(), new MessageTooBigException("too big"));
		assertEquals(2, last().getRequest().<NotifyPeerRequest>getBody().getPeer().getFingerprint(),
				"the next record goes out at once");
		ack(last());

		advance(SubscriptionManager.PUSH_MIN_INTERVAL);
		int before = sent.size();
		manager.subscribe(subscriber, address, SubscriptionKind.PEER, big.getId(), -1, -1);
		assertEquals(before, sent.size(), "a renewal does not offer the oversized record again");
	}

	private Value valueOf(Signature.KeyPair key, int seq) throws Exception {
		return Value.signedBuilder().key(key).sequenceNumber(seq).data(("v" + seq).getBytes()).build();
	}

	/**
	 * A full call table is this node's own congestion. The subscriber is charged nothing, and pushing stops
	 * for a while instead of every queued subscription finding the table full for itself.
	 */
	@Test
	void testANodeWideRefusalPausesPushingAndChargesNoOne() throws Exception {
		Signature.KeyPair otherOwner = Signature.KeyPair.random();
		Id otherSubscriber = Id.random();
		InetSocketAddress otherAddress = new InetSocketAddress("12.34.56.79", 39001);
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		manager.subscribe(otherSubscriber, otherAddress, SubscriptionKind.VALUE, valueOf(otherOwner, 1).getId(), -1, -1);

		refuse = call -> CallRejectedException.Reason.ACTIVE_CALLS;
		manager.valueStored(value(1));
		manager.valueStored(valueOf(otherOwner, 1));
		assertEquals(1, sent.size(), "one refusal pauses everything; the second subscription is not tried");

		SubscriptionManager.Subscription sub = manager.get(subscriber, SubscriptionKind.VALUE, valueId);
		assertEquals(0, sub.failures);
		assertFalse(sub.stalled);
		assertFalse(sub.inFlight);

		// Still full when the pause ends: refused again, and the pause doubles.
		advance(SubscriptionManager.PAUSE_MIN);
		assertEquals(2, sent.size());
		advance(SubscriptionManager.PAUSE_MIN);
		assertEquals(2, sent.size(), "the second pause is twice as long");

		// Room again: both go out, the refused one first.
		refuse = call -> null;
		advance(SubscriptionManager.PAUSE_MIN);
		assertEquals(4, sent.size());
		assertEquals(subscriber, sent.get(2).getTargetId());
		assertEquals(otherSubscriber, sent.get(3).getTargetId());
		assertEquals(0, manager.get(subscriber, SubscriptionKind.VALUE, valueId).failures);
	}

	/**
	 * The outbound throttle refuses on account of one host. Only that subscription waits; everyone else
	 * carries on.
	 */
	@Test
	void testAThrottleRefusalBacksOffOnlyThatSubscription() throws Exception {
		Signature.KeyPair otherOwner = Signature.KeyPair.random();
		Id otherSubscriber = Id.random();
		InetSocketAddress otherAddress = new InetSocketAddress("12.34.56.79", 39001);
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		manager.subscribe(otherSubscriber, otherAddress, SubscriptionKind.VALUE, valueOf(otherOwner, 1).getId(), -1, -1);

		refuse = call -> call.getTarget().getAddress().equals(address) ? CallRejectedException.Reason.OUTBOUND_THROTTLE : null;
		manager.valueStored(value(1));
		manager.valueStored(valueOf(otherOwner, 1));
		assertEquals(2, sent.size());
		assertEquals(otherSubscriber, last().getTargetId(), "the other subscriber is not held up");
		assertEquals(0, manager.get(subscriber, SubscriptionKind.VALUE, valueId).failures);

		refuse = call -> null;
		advance(SubscriptionManager.THROTTLE_BACKOFF - 1);
		assertEquals(2, sent.size());
		advance(1);
		assertEquals(3, sent.size());
		assertEquals(subscriber, last().getTargetId());
	}

	/**
	 * A peer subscription with several pending records tries one, is refused, and waits - the others do not
	 * rediscover the throttle one after another, and the refusal does not recurse into the queue.
	 */
	@Test
	void testAThrottledPeerSubscriptionTriesOneRecordAtATime() throws Exception {
		Signature.KeyPair key = Signature.KeyPair.random();
		List<PeerInfo> instances = new ArrayList<>();
		for (int i = 0; i < 3; i++)
			instances.add(PeerInfo.builder().key(key).fingerprint(100 + i).sequenceNumber(1)
					.endpoint("tcp://203.0.113.10:" + (3000 + i)).build());
		peers.put(instances.get(0).getId(), instances);

		refuse = call -> CallRejectedException.Reason.OUTBOUND_THROTTLE;
		manager.subscribe(subscriber, address, SubscriptionKind.PEER, instances.get(0).getId(), -1, -1);
		assertEquals(1, sent.size());

		advance(SubscriptionManager.THROTTLE_BACKOFF);
		assertEquals(2, sent.size(), "one attempt per back-off while refused");

		refuse = call -> null;
		advance(SubscriptionManager.THROTTLE_BACKOFF);
		for (int i = 0; i < 3; i++)
			ack(last());
		assertEquals(5, sent.size(), "then the three records go out, one in flight at a time");
	}

	@Test
	void testSubscribersPerTargetAreCapped() throws Exception {
		for (int i = 0; i < SubscriptionManager.MAX_SUBSCRIBERS_PER_TARGET; i++)
			manager.subscribe(Id.random(), new InetSocketAddress("12.0." + i + ".1", 39001), SubscriptionKind.VALUE, valueId, -1, -1);

		assertThrows(SubscriptionRefusedException.class, () ->
				manager.subscribe(Id.random(), new InetSocketAddress("13.0.0.1", 39001), SubscriptionKind.VALUE, valueId, -1, -1));

		// The same target of the other kind is a different topic.
		manager.subscribe(Id.random(), new InetSocketAddress("13.0.0.1", 39001), SubscriptionKind.PEER, valueId, -1, -1);
	}

	@Test
	void testSubscriptionsPerSourceUnitAreCapped() throws Exception {
		// Different ports, different ids, one IPv6 /64: one source.
		Id firstSubscriber = null;
		Id firstTarget = null;
		for (int i = 0; i < SubscriptionManager.MAX_SUBSCRIPTIONS_PER_SOURCE; i++) {
			Id sub = Id.random();
			Id target = Id.random();
			if (i == 0) {
				firstSubscriber = sub;
				firstTarget = target;
			}
			manager.subscribe(sub, new InetSocketAddress("2606:4700:1::" + Integer.toHexString(i + 1), 39001 + i),
					SubscriptionKind.VALUE, target, -1, -1);
		}

		assertThrows(SubscriptionRefusedException.class, () ->
				manager.subscribe(Id.random(), new InetSocketAddress("2606:4700:1::ffff", 39001), SubscriptionKind.VALUE, Id.random(), -1, -1));

		manager.subscribe(Id.random(), new InetSocketAddress("2606:4700:2::1", 39001), SubscriptionKind.VALUE, Id.random(), -1, -1);

		// Cancelling frees the allowance.
		assertTrue(manager.cancel(firstSubscriber, SubscriptionKind.VALUE, firstTarget));
		manager.subscribe(Id.random(), new InetSocketAddress("2606:4700:1::ffff", 39001), SubscriptionKind.VALUE,
				Id.random(), -1, -1);
	}

	@Test
	void testTheNodeWideCapRefuses() throws Exception {
		Id firstSubscriber = Id.random();
		Id firstTarget = Id.random();
		manager.subscribe(firstSubscriber, new InetSocketAddress("12.0.0.1", 39001), SubscriptionKind.VALUE,
				firstTarget, -1, -1);
		for (int i = 1; i < SubscriptionManager.MAX_SUBSCRIPTIONS; i++)
			manager.subscribe(Id.random(), new InetSocketAddress("12." + (i >> 8) + "." + (i & 0xff) + ".1", 39001),
					SubscriptionKind.VALUE, Id.random(), -1, -1);

		assertEquals(SubscriptionManager.MAX_SUBSCRIPTIONS, manager.size());
		assertThrows(SubscriptionRefusedException.class, () ->
				manager.subscribe(Id.random(), new InetSocketAddress("13.0.0.1", 39001), SubscriptionKind.VALUE, Id.random(), -1, -1));

		// A renewal of a subscription already held is not refused.
		manager.subscribe(firstSubscriber, new InetSocketAddress("12.0.0.1", 39001), SubscriptionKind.VALUE,
				firstTarget, -1, -1);
	}

	@Test
	void testPushesAreHeldToTheNodeWideRate() throws Exception {
		int subscriptions = SubscriptionManager.PUSH_BURST + 100;
		List<Id> targets = new ArrayList<>();
		for (int i = 0; i < subscriptions; i++) {
			Signature.KeyPair key = Signature.KeyPair.random();
			Value v = Value.signedBuilder().key(key).sequenceNumber(1).data("x".getBytes()).build();
			targets.add(v.getId());
			manager.subscribe(Id.random(), new InetSocketAddress("12." + (i >> 8) + "." + (i & 0xff) + ".1", 39001),
					SubscriptionKind.VALUE, v.getId(), -1, -1);
			manager.valueStored(v);
		}

		assertEquals(SubscriptionManager.PUSH_BURST, sent.size(), "the burst is the ceiling at once");

		advance(250);
		assertEquals(SubscriptionManager.PUSH_BURST + SubscriptionManager.PUSH_RATE / 4, sent.size(), 1,
				"then the rate");

		advance(1000);
		assertEquals(subscriptions, sent.size(), "and nothing is lost, only delayed");
	}

	@Test
	void testClearDropsEverything() throws Exception {
		manager.subscribe(subscriber, address, SubscriptionKind.VALUE, valueId, -1, -1);
		manager.clear();
		assertEquals(0, manager.size());
		assertNull(manager.get(subscriber, SubscriptionKind.VALUE, valueId));
	}
}
