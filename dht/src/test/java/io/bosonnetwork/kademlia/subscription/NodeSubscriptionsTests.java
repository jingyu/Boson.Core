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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Subscription;
import io.bosonnetwork.SubscriptionListener;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.kademlia.exceptions.SubscriptionRefusedException;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.protocol.SubscriptionKind;
import io.bosonnetwork.kademlia.rpc.RpcCall;

class NodeSubscriptionsTests {
	/** One address family: a real client whose calls are captured, with one candidate node. */
	private static final class TestFamily implements NodeSubscriptions.Family {
		final List<RpcCall> sent = new ArrayList<>();
		final NodeInfo node;
		final SubscriptionClient client;

		TestFamily(String host) {
			node = NodeInfo.of(Id.random(), new InetSocketAddress(host, 39001));
			client = new SubscriptionClient(new SubscriptionClient.Host() {
				@Override
				public long now() {
					return 1_000_000;
				}

				@Override
				public Future<RpcCall> send(RpcCall call) {
					sent.add(call);
					return Future.succeededFuture(call);
				}

				@Override
				public Future<List<SubscriptionClient.Candidate>> lookup(Id target) {
					return Future.succeededFuture(List.of(new SubscriptionClient.Candidate(node, 1,
							SubscriptionClient.SUBSCRIPTIONS_SINCE)));
				}

				@Override
				public long setTimer(long delay, Runnable action) {
					return 1;
				}

				@Override
				public void cancelTimer(long id) {
				}

				@Override
				public boolean needsNatKeepalive() {
					return false;
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
				}
			});
		}

		@Override
		public Future<SubscriptionClient.Watch> subscribe(SubscriptionKind kind, Id target, int knownSeq,
				SubscriptionClient.Sink sink) {
			return Future.succeededFuture(client.subscribe(kind, target, knownSeq, sink));
		}

		@Override
		public void unsubscribe(SubscriptionClient.Watch watch) {
			watch.cancel();
		}

		RpcCall lastSubscribe() {
			return sent.stream().filter(c -> c.getRequest().getMethod() == Message.Method.SUBSCRIBE)
					.reduce((a, b) -> b).orElseThrow();
		}

		void accept() {
			invoke(lastSubscribe(), "respond", Message.class,
					Message.subscribeResponse(lastSubscribe().getTxid(), 1, 600));
		}

		void refuse() {
			invoke(lastSubscribe(), "fail", Throwable.class, new SubscriptionRefusedException("full"));
		}

		void notify(Value value) {
			Message msg = Message.notifyValueRequest(value);
			msg.setId(node.getId());
			assertTrue(client.onNotify(msg));
		}
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

	private Value localValue;
	private NodeSubscriptions subscriptions;
	private TestFamily v4;
	private TestFamily v6;
	private Signature.KeyPair owner;

	@BeforeEach
	void setUp() {
		localValue = null;
		owner = Signature.KeyPair.random();
		v4 = new TestFamily("12.1.1.1");
		v6 = new TestFamily("2606:4700::1");
		subscriptions = new NodeSubscriptions(new NodeSubscriptions.Host() {
			@Override
			public void runOnContext(Runnable action) {
				action.run();
			}

			@Override
			public Future<Value> localValue(Id id) {
				return Future.succeededFuture(localValue);
			}

			@Override
			public Future<List<PeerInfo>> localPeers(Id id) {
				return Future.succeededFuture(List.of());
			}
		});
	}

	private Value value(int seq) throws Exception {
		return Value.signedBuilder().key(owner).sequenceNumber(seq).data(("v" + seq).getBytes()).build();
	}

	@Test
	void testReadyOnTheFirstFamilyToAccept() throws Exception {
		Future<Subscription> future = subscriptions.subscribe(List.of(v4, v6), SubscriptionKind.VALUE,
				value(1).getId(), -1, v -> { });
		assertTrue(!future.isComplete());

		v6.accept();
		assertTrue(future.succeeded());
		assertEquals(1, future.result().getSubscribedNodes());

		v4.accept();
		assertEquals(2, future.result().getSubscribedNodes(), "counted across families");
	}

	/**
	 * Every family refusing is a network condition: the subscription is handed over with nothing watching
	 * yet, and kept - not torn down for the caller to rebuild.
	 */
	@Test
	void testSettlesWithNothingWatchingWhenEveryFamilyIsRefused() throws Exception {
		Future<Subscription> future = subscriptions.subscribe(List.of(v4, v6), SubscriptionKind.VALUE,
				value(1).getId(), -1, v -> { });

		v4.refuse();
		assertTrue(!future.isComplete(), "one family is not all of them");
		v6.refuse();
		assertTrue(future.succeeded());
		assertEquals(0, future.result().getSubscribedNodes());
		assertEquals(1, subscriptions.size(), "kept, still trying");
	}

	/**
	 * Only no family being able to run it at all - the node not running - fails a subscription.
	 */
	@Test
	void testFailsWhenNoFamilyCanRunIt() throws Exception {
		NodeSubscriptions.Family stopped = new NodeSubscriptions.Family() {
			@Override
			public Future<SubscriptionClient.Watch> subscribe(SubscriptionKind kind, Id target, int knownSeq,
					SubscriptionClient.Sink sink) {
				return Future.failedFuture(new IllegalStateException("DHT is not running"));
			}

			@Override
			public void unsubscribe(SubscriptionClient.Watch watch) {
			}
		};

		Future<Subscription> future = subscriptions.subscribe(List.of(stopped, stopped), SubscriptionKind.VALUE,
				value(1).getId(), -1, v -> { });
		assertTrue(future.failed());
		assertEquals(0, subscriptions.size());
	}

	@Test
	void testRecordsAreDeduplicatedAcrossFamiliesAndPerListener() throws Exception {
		List<Value> first = new ArrayList<>();
		List<Value> second = new ArrayList<>();
		Id target = value(1).getId();

		subscriptions.subscribe(List.of(v4, v6), SubscriptionKind.VALUE, target, -1, (SubscriptionListener<Value>) first::add);
		v4.accept();
		v4.notify(value(1));
		v6.notify(value(1));
		v6.notify(value(2));
		v4.notify(value(2));
		assertEquals(List.of(value(1), value(2)), first);

		// Joins holding version 2 already: nothing to catch up. Then both see version 3 once.
		SubscriptionListener<Value> listener = second::add;
		subscriptions.subscribe(List.of(v4, v6), SubscriptionKind.VALUE, target, 2, listener);
		assertTrue(second.isEmpty());
		subscriptions.valueStored(value(3)); // written to this node's own storage
		v4.notify(value(3));
		assertEquals(List.of(value(1), value(2), value(3)), first);
		assertEquals(List.of(value(3)), second);
	}

	@Test
	void testALateListenerIsCaughtUp() throws Exception {
		Id target = value(1).getId();
		subscriptions.subscribe(List.of(v4), SubscriptionKind.VALUE, target, -1, v -> { });
		v4.accept();
		v4.notify(value(5));

		List<Value> late = new ArrayList<>();
		Future<Subscription> future = subscriptions.subscribe(List.of(v4), SubscriptionKind.VALUE, target, -1, (SubscriptionListener<Value>) late::add);
		assertTrue(future.succeeded(), "joining a target already watched completes at once");
		assertEquals(List.of(value(5)), late);
	}

	@Test
	void testTheLocalValueIsDeliveredFirst() throws Exception {
		localValue = value(4);
		List<Value> got = new ArrayList<>();
		subscriptions.subscribe(List.of(v4), SubscriptionKind.VALUE, localValue.getId(), -1, (SubscriptionListener<Value>) got::add);
		assertEquals(List.of(value(4)), got);
	}

	@Test
	void testTheLastCancelReleasesTheNetworkSubscription() throws Exception {
		Id target = value(1).getId();
		Future<Subscription> first = subscriptions.subscribe(List.of(v4), SubscriptionKind.VALUE, target, -1, v -> { });
		v4.accept();
		Subscription a = first.result();
		Subscription b = subscriptions.subscribe(List.of(v4), SubscriptionKind.VALUE, target, -1, v -> { }).result();

		a.cancel();
		assertEquals(1, v4.client.size(), "still wanted by the other listener");
		b.cancel();
		assertEquals(0, v4.client.size());
		assertEquals(0, subscriptions.size());
		assertTrue(a.isCancelled() && b.isCancelled());
	}
}
