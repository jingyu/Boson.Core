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

package io.bosonnetwork.kademlia.rpc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.impl.Network;
import io.bosonnetwork.kademlia.impl.TestKadContext;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.security.Blacklist;

/**
 * What happens to a call after it is admitted: the queue the outbound throttle parks it in, and the budget
 * that keeps calls we were provoked into making out of the slots the tasks need.
 * <p>
 * The queue used to be unbounded in both directions. Its wait grows with the number of calls already
 * waiting on the same target and nothing capped the count, so at a high concurrency - where lookups converge
 * on the same near nodes by construction - the last call to a hot target was scheduled minutes out. And a
 * parked call was in no map at all: not counted against the active-call ceiling, holding its caller's slot
 * with no timeout of its own, and left unsettled when the server stopped.
 * </p>
 * <p>
 * Every count below is derived from the constants on {@link RpcServer} rather than mirrored, so that
 * retuning the throttle retunes the test with it.
 * </p>
 */
@ExtendWith(VertxExtension.class)
public class RpcServerOutboundThrottleTests {
	private static final int OUTBOUND_BURST = RpcServer.OUTBOUND_BURST_CAPACITY;
	private static final int OUTBOUND_LIMIT = RpcServer.OUTBOUND_LIMIT_PER_SECOND;
	private static final int HORIZON = RpcServer.RPC_CALL_TIMEOUT_MAX;

	/**
	 * The most calls one target can have waiting before the horizon refuses the next: the burst, which
	 * costs no wait at all, plus a horizon's worth of the sustained rate.
	 */
	private static final int QUEUE_BOUND = OUTBOUND_BURST + OUTBOUND_LIMIT * HORIZON / 1000;

	private static RpcServer newServer(Context vertxContext, int port) {
		// Developer mode disables both throttles, which is the whole subject here.
		KadContext kadContext = new TestKadContext(vertxContext, new CryptoIdentity(), Network.IPv4)
				.setDeveloperMode(false);
		return new RpcServer(kadContext, "127.0.0.1", port, Blacklist.empty(), true, null);
	}

	/**
	 * A target nothing is listening at, so a call sent to it simply sits in the pending table.
	 * <p>
	 * A real key rather than {@code Id.random()}: the request is encrypted to the target id, so random
	 * bytes are only sometimes a decodable public key and the test would fail at random.
	 * </p>
	 */
	private static NodeInfo deadTarget(int port) {
		return NodeInfo.of(new CryptoIdentity().getId(), "127.0.0.1", port);
	}

	private static RpcCall callTo(NodeInfo target) {
		return new RpcCall(target, Message.pingRequest());
	}

	/**
	 * The queue has an end. Past the point where a call would wait longer than we would ever wait for its
	 * answer, it is failed rather than parked - so the caller finds out now, while it can still do something
	 * about it, instead of holding a slot for a call that has not been sent.
	 */
	@Test
	@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
	void testTheDelayQueueEndsAtTheCallHorizon(Vertx vertx, VertxTestContext context) {
		Context vertxContext = vertx.getOrCreateContext();
		RpcServer server = newServer(vertxContext, 39204);
		NodeInfo target = deadTarget(39294);

		vertxContext.runOnContext(unused -> server.start().onSuccess(v -> {
			// One turn of the event loop, deliberately: no parked timer can fire while this loop runs, so
			// what it measures is the bound on the queue rather than a race with its own releases.
			RpcCall refused = null;
			Future<RpcCall> refusedFuture = null;
			int refusedAt = 0;

			for (int i = 1; i <= QUEUE_BOUND * 2 && refused == null; i++) {
				RpcCall call = callTo(target);
				Future<RpcCall> sending = server.sendCall(call);
				if (sending.failed()) {
					refused = call;
					refusedFuture = sending;
					refusedAt = i;
				}
			}

			final RpcCall refusedCall = refused;
			final Future<RpcCall> refusal = refusedFuture;
			final int refusedIndex = refusedAt;
			final int parked = server.delayedCallCount();

			context.verify(() -> {
				assertNotNull(refusedCall,
						"nothing was refused in " + (QUEUE_BOUND * 2) + " calls; the queue has no end");
				assertTrue(parked > 0, "nothing was ever parked; the outbound throttle did not engage");

				// Not the burst: the calls that cost no wait must all go out.
				assertTrue(refusedIndex > OUTBOUND_BURST,
						"refused call " + refusedIndex + ", inside the burst of " + OUTBOUND_BURST);
				// And not the active-call ceiling either, which is well above anything reached here.
				assertTrue(refusedIndex <= QUEUE_BOUND + 1,
						"refused call " + refusedIndex + ", past the horizon bound of " + QUEUE_BOUND);
				assertTrue(parked <= QUEUE_BOUND,
						parked + " calls parked, more than the horizon allows (" + QUEUE_BOUND + ")");

				// The caller has to be able to tell this from the ceiling: one says the node is at its
				// configured limit, the other says this particular target is.
				assertInstanceOf(CallRejectedException.class, refusedCall.getCause());
				assertTrue(refusedCall.getCause().getMessage().contains("horizon"),
						"refused for the wrong reason: " + refusedCall.getCause().getMessage());
				assertEquals(RpcCall.State.ERROR, refusedCall.getState(),
						"a refused call must reach its caller as an error, not sit unsent");
				// One cause for both, so a task reading the call and a caller reading the future are told
				// the same thing.
				assertSame(refusedCall.getCause(), refusal.cause(),
						"the call and its future must fail with the same cause");
			});

			server.stop().onComplete(context.succeedingThenComplete());
		}).onFailure(context::failNow));
	}

	/**
	 * A parked call is settled when the server stops.
	 * <p>
	 * This is the sharper half of the same omission. A call in flight was already cancelled here; a parked
	 * one was not, and it is in a worse position - it has not been sent, it has no timeout timer yet, and
	 * the timer that would release it goes down with the context. Its caller waits forever, which for
	 * {@code DHT.doBootstrap} means an in-progress flag that blocks every later bootstrap for the life of
	 * the node.
	 * </p>
	 */
	@Test
	@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
	void testStopSettlesAParkedCall(Vertx vertx, VertxTestContext context) {
		Context vertxContext = vertx.getOrCreateContext();
		RpcServer server = newServer(vertxContext, 39205);
		NodeInfo target = deadTarget(39295);

		RpcCall parked = callTo(target);
		Promise<RpcCall.State> ended = Promise.promise();
		parked.addListener(new RpcCallListener() {
			@Override
			public void onStateChange(RpcCall c, RpcCall.State previous, RpcCall.State state) {
				if (state.isFinal())
					ended.tryComplete(state);
			}
		});

		vertxContext.runOnContext(unused -> server.start().onSuccess(v -> {
			// Spend the burst exactly, so the next call is the first one that has to wait. The burst is
			// what the throttle admits, so it takes all of them to spend it.
			for (int i = 0; i < OUTBOUND_BURST; i++)
				server.sendCall(callTo(target));

			server.sendCall(parked);

			context.verify(() -> {
				assertEquals(1, server.delayedCallCount(), "the call past the burst should have been parked");
				assertEquals(RpcCall.State.UNSENT, parked.getState(), "a parked call has not been sent");
			});

			server.stop().onFailure(context::failNow);
		}).onFailure(context::failNow));

		ended.future().onComplete(context.succeeding(state -> {
			context.verify(() -> {
				assertEquals(RpcCall.State.CANCELED, state,
						"stopping the server must settle what the throttle was holding back");
				assertEquals(0, server.delayedCallCount(), "and let go of it");
			});
			context.completeNow();
		}));
	}

	/**
	 * The other end of the park: a call the throttle held back is released and sent, and the queue lets go
	 * of it when it goes. A queue that released calls without removing them would satisfy every other test
	 * here while growing without bound.
	 */
	@Test
	@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
	void testParkedCallsAreReleasedAndTheQueueShrinks(Vertx vertx, VertxTestContext context) {
		Context vertxContext = vertx.getOrCreateContext();
		RpcServer server = newServer(vertxContext, 39206);
		NodeInfo target = deadTarget(39296);

		RpcCall parked = callTo(target);

		vertxContext.runOnContext(unused -> server.start().onSuccess(v -> {
			for (int i = 0; i < OUTBOUND_BURST; i++)
				server.sendCall(callTo(target));

			server.sendCall(parked);
			context.verify(() -> assertEquals(1, server.delayedCallCount(), "precondition: it was parked"));

			// Long enough for the throttle's own decay to open the budget again: the wait for the first
			// call past the burst is one decay tick plus a share of a second.
			vertx.timer(3000).compose(t -> {
				context.verify(() -> {
					assertEquals(0, server.delayedCallCount(), "the queue kept a call it had already released");
					assertNotEquals(RpcCall.State.UNSENT, parked.getState(),
							"the parked call was never sent after its wait");
				});

				return server.stop();
			}).onComplete(context.succeedingThenComplete());
		}).onFailure(context::failNow));
	}

	/**
	 * The calls arriving traffic provokes get a sub-budget, and cannot spend the table the tasks are sized
	 * against.
	 * <p>
	 * One target throughout, so the accounting is what is under test rather than the host's willingness to
	 * route to a hundred loopback addresses. Some of these calls are parked rather than sent, which is
	 * exactly right: the budget is about how many of them are outstanding, and a parked call is outstanding.
	 * </p>
	 */
	@Test
	@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
	void testTheUnsolicitedBudgetIsSeparateFromTheTable(Vertx vertx, VertxTestContext context) {
		Context vertxContext = vertx.getOrCreateContext();
		RpcServer server = newServer(vertxContext, 39207);
		NodeInfo target = deadTarget(39297);

		vertxContext.runOnContext(unused -> server.start().onSuccess(v -> {
			final int budget = server.maxUnsolicitedCalls;

			// Our own calls are not charged to it, whatever else they cost.
			server.sendCall(callTo(target));
			context.verify(() -> {
				assertTrue(budget > 0 && budget < server.maxActiveCalls,
						"the sub-budget must be a share of the table, not all of it or none");
				assertEquals(0, server.unsolicitedCallCount(), "a call of ours was charged to the ping budget");
			});

			for (int i = 0; i < budget; i++)
				server.sendCall(callTo(target).setUnsolicited(true));

			RpcCall refused = callTo(target).setUnsolicited(true);
			Future<RpcCall> refusal = server.sendCall(refused);

			// And the property the budget exists for: with the reactive side at its ceiling, a call of ours
			// is still accepted.
			RpcCall ours = callTo(target);
			Future<RpcCall> accepted = server.sendCall(ours);

			context.verify(() -> {
				assertEquals(budget, server.unsolicitedCallCount(),
						"the budget is not counting what it admitted");
				assertTrue(refusal.failed(), "the budget admitted more than it holds");
				assertInstanceOf(CallRejectedException.class, refused.getCause());
				assertTrue(refused.getCause().getMessage().contains("Unsolicited"),
						"refused for the wrong reason: " + refused.getCause().getMessage());
				assertEquals(RpcCall.State.ERROR, refused.getState());
				assertSame(refused.getCause(), refusal.cause(),
						"the call and its future must fail with the same cause");
				assertFalse(accepted.failed(), "a call of ours was refused for a budget it does not draw on");
				assertNotEquals(RpcCall.State.ERROR, ours.getState());
			});

			server.stop().onSuccess(stopped -> context.verify(() ->
					assertEquals(0, server.unsolicitedCallCount(),
							"the budget must be given back as the calls settle")
			)).onComplete(context.succeedingThenComplete());
		}).onFailure(context::failNow));
	}
}
