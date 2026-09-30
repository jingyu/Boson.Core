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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.impl.Network;
import io.bosonnetwork.kademlia.impl.TestKadContext;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.security.Blacklist;

/**
 * A liveness-neutral call's outcome must not reach the accounting that decides whether a node - the target,
 * or this one - is alive.
 * <p>
 * The case it exists for is a subscription notification pushed to a subscriber whose NAT mapping has lapsed.
 * Were its timeout reported, the DHT would count it against the subscriber's routing table entry, and since a
 * dead entry is removed even without a replacement, a subscriber behind a flaky NAT would be evicted by its own
 * subscriptions. Were its send reported, an unanswered push would also count towards this node deciding its own
 * socket had gone deaf.
 * </p>
 */
@ExtendWith(VertxExtension.class)
class RpcServerLivenessNeutralTests {
	@Test
	@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
	void testANeutralCallReachesNeitherTheSentNorTheTimeoutHandler(Vertx vertx, VertxTestContext context) {
		Context vertxContext = vertx.getOrCreateContext();
		KadContext kadContext = new TestKadContext(vertxContext, new CryptoIdentity(), Network.IPv4)
				.setDeveloperMode(false);
		RpcServer server = new RpcServer(kadContext, "127.0.0.1", 39211, Blacklist.empty(), true, null);

		List<RpcCall> sentReported = new CopyOnWriteArrayList<>();
		List<RpcCall> timeoutReported = new CopyOnWriteArrayList<>();
		server.setCallSentHandler(sentReported::add);
		server.setCallTimeoutHandler(timeoutReported::add);

		// Nothing listens at the target, so both calls time out. A real key: the request is encrypted to it.
		Id target = new CryptoIdentity().getId();
		RpcCall ordinary = new RpcCall(NodeInfo.of(target, "127.0.0.1", 39298), Message.pingRequest());
		RpcCall neutral = new RpcCall(NodeInfo.of(target, "127.0.0.1", 39298),
				Message.notifyValueRequest(Value.immutableBuilder().data("x".getBytes()).build()))
				.setLivenessNeutral(true);

		Promise<Void> ordinaryEnded = Promise.promise();
		Promise<Void> neutralEnded = Promise.promise();
		ordinary.addListener(finalState(ordinaryEnded));
		neutral.addListener(finalState(neutralEnded));

		vertxContext.runOnContext(unused -> server.start()
				.compose(v -> server.sendCall(ordinary))
				.compose(v -> server.sendCall(neutral))
				.onFailure(context::failNow));

		Future.all(ordinaryEnded.future(), neutralEnded.future()).onComplete(context.succeeding(v -> {
			context.verify(() -> {
				assertEquals(RpcCall.State.TIMEOUT, ordinary.getState());
				assertEquals(RpcCall.State.TIMEOUT, neutral.getState());
				assertEquals(List.of(ordinary), sentReported, "only the ordinary call is reported as sent");
				assertEquals(List.of(ordinary), timeoutReported, "only the ordinary call's timeout is reported");
			});
			server.stop().onComplete(ar -> context.completeNow());
		}));
	}

	private static RpcCallListener finalState(Promise<Void> ended) {
		return new RpcCallListener() {
			@Override
			public void onStateChange(RpcCall c, RpcCall.State previous, RpcCall.State state) {
				if (state.isFinal())
					ended.tryComplete();
			}
		};
	}
}
