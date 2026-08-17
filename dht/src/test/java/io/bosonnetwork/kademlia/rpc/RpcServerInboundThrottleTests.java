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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Vertx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.Identity;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.impl.Network;
import io.bosonnetwork.kademlia.impl.TestKadContext;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.security.Blacklist;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.vertx.BosonVerticle;

/**
 * The inbound throttle budgets unsolicited work, and only unsolicited work.
 * <p>
 * Two properties, pulling against each other, and the value is in holding both at once. A packet that
 * answers a call this node made - from the address that call was sent to - must not be charged, or our own
 * conversations would spend the budget meant for traffic we never asked for, and a response dropped that way
 * becomes a timeout recorded against a peer that answered correctly. But nothing weaker than that may earn
 * the exemption: the counter used to be cleared outright whenever a call was sent, which a sender could
 * provoke - an unsolicited request bearing an unknown id draws a ping - and so buy back the whole burst
 * roughly once per packet.
 * </p>
 * <p>
 * The suspicious-node detector is disabled throughout. These bursts are large enough to interest it, and a
 * ban would decide the outcome before the throttle did; what is under test here is the budget alone.
 * </p>
 */
@ExtendWith(VertxExtension.class)
public class RpcServerInboundThrottleTests {
	// Read from RpcServer rather than mirrored: this test lives in its package, and every count below is
	// derived from these so that retuning the throttle retunes the test with it.
	private static final int INBOUND_BURST = RpcServer.INBOUND_BURST_CAPACITY;
	private static final int INBOUND_LIMIT = RpcServer.INBOUND_LIMIT_PER_SECOND;
	private static final int OUTBOUND_BURST = RpcServer.OUTBOUND_BURST_CAPACITY;

	@SuppressWarnings("ConstantConditions")
	private static final InetAddress localInetAddr = AddressUtils.getDefaultRouteAddress(Inet4Address.class);
	@SuppressWarnings("ConstantConditions")
	private static final String localAddr = localInetAddr.getHostAddress();

	static class Node extends BosonVerticle {
		final Identity identity = new CryptoIdentity();
		final int port;

		/** Answer incoming requests, as any node does. */
		boolean answerRequests = true;
		final AtomicInteger receivedRequests = new AtomicInteger();
		final AtomicInteger receivedResponses = new AtomicInteger();

		private KadContext kadContext;
		private RpcServer rpcServer;

		Node(int port) {
			this.port = port;
		}

		@Override
		protected void prepare(Vertx vertx, Context context) {
			super.prepare(vertx, context);

			kadContext = new TestKadContext(context, identity, Network.IPv4).setDeveloperMode(false);
			rpcServer = new RpcServer(kadContext, localAddr, port, Blacklist.empty(), true, null);
			rpcServer.setMessageHandler(this::onMessage);
		}

		@Override
		protected Future<Void> deploy() {
			return rpcServer.start();
		}

		@Override
		protected Future<Void> undeploy() {
			if (rpcServer != null)
				return rpcServer.stop().andThen(ar -> rpcServer = null);
			else
				return Future.succeededFuture();
		}

		private void onMessage(Message message) {
			if (message.isRequest()) {
				receivedRequests.incrementAndGet();

				if (answerRequests) {
					Message response = Message.pingResponse(message.getTxid());
					response.setRemote(message.getId(), message.getRemoteAddress());
					rpcServer.sendMessage(response);
				}
			} else if (message.isResponse()) {
				// Only a response matched to one of our calls reaches a message handler, so this counts
				// exactly the packets the refund is supposed to cover.
				receivedResponses.incrementAndGet();
			}
		}

		/** An unsolicited request: no call of the recipient's is waiting for it. */
		void sendRequest(Node to) {
			runOnContext(v -> {
				Message request = Message.pingRequest();
				request.setRemote(to.identity.getId(), localInetAddr, to.port);
				rpcServer.sendMessage(request);
			});
		}

		/** A response answering nothing - its transaction id matches no call the recipient has out. */
		void sendStrayResponse(Node to) {
			runOnContext(v -> {
				Message response = Message.pingResponse(Message.pingRequest().getTxid());
				response.setRemote(to.identity.getId(), localInetAddr, to.port);
				rpcServer.sendMessage(response);
			});
		}

		/** A call, which the peer will answer - the traffic the refund exists for. */
		void sendCall(Node to) {
			runOnContext(v -> rpcServer.sendCall(new RpcCall(
					NodeInfo.of(to.identity.getId(), localAddr, to.port), Message.pingRequest())));
		}
	}

	/**
	 * Emits {@code count} messages in batches, so that the recipient's own reaction to them has time to
	 * interleave and each batch meets a budget the previous one has already moved. Firing them all in one
	 * turn of the event loop would measure the receive queue rather than the throttle.
	 */
	private static Future<Void> emit(Vertx vertx, int count, int batchSize, long gapMillis, Runnable emitOne) {
		Future<Void> chain = Future.succeededFuture();

		for (int sent = 0; sent < count; sent += batchSize) {
			final int batch = Math.min(batchSize, count - sent);
			chain = chain.compose(unused -> {
				for (int i = 0; i < batch; i++)
					emitOne.run();

				return vertx.timer(gapMillis).mapEmpty();
			});
		}

		return chain;
	}

	@Test
	@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
	public void testSendingACallCannotBuyBackTheBurst(Vertx vertx, VertxTestContext context) {
		// The finding, staged rather than raced: spend the sender's whole budget, then send it one call -
		// the ping any unknown id used to draw from DHT.received - and count what that single call bought.
		// It used to buy the entire burst back, roughly once per packet the sender chose to send.
		//
		// The sender answers nothing, so nothing it sends is solicited and no refund is due at any point.
		Node victim = new Node(8892);
		Node sender = new Node(8893);

		victim.answerRequests = false;
		sender.answerRequests = false;

		final int exhaust = INBOUND_BURST + INBOUND_BURST / 2;
		final int probe = INBOUND_BURST / 4;
		final AtomicInteger spent = new AtomicInteger();

		Future.all(vertx.deployVerticle(victim), vertx.deployVerticle(sender))
				.compose(unused -> emit(vertx, exhaust, 16, 5, () -> sender.sendRequest(victim)))
				.compose(unused -> vertx.timer(100))
				.compose(unused -> {
					int accepted = victim.receivedRequests.get();
					spent.set(accepted);

					context.verify(() -> {
						assertTrue(accepted <= INBOUND_BURST + INBOUND_LIMIT,
								"accepted " + accepted + " of " + exhaust + "; the budget did not close");
						// A budget that never opened would satisfy that while being just as wrong.
						assertTrue(accepted >= INBOUND_BURST / 2,
								"only " + accepted + " packets accepted; the budget is not opening at all");
					});

					// The one call. Nothing else happens in this window.
					victim.sendCall(sender);
					return vertx.timer(100);
				})
				.compose(unused -> emit(vertx, probe, 16, 5, () -> sender.sendRequest(victim)))
				.compose(unused -> vertx.timer(200))
				.compose(unused -> {
					context.verify(() -> {
						int admitted = victim.receivedRequests.get() - spent.get();
						// Only decay should have opened the budget in the meantime - one limit's worth a
						// second, against a window well under a second. A reset would have opened all of it.
						assertTrue(admitted <= probe / 4,
								"sending one call handed the source " + admitted + " of " + probe + " packets back");
					});

					return Future.all(vertx.undeploy(victim.deploymentID()), vertx.undeploy(sender.deploymentID()));
				})
				.onComplete(context.succeedingThenComplete());
	}

	@Test
	@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
	public void testAnsweredCallsDoNotConsumeTheSendersBudget(Vertx vertx, VertxTestContext context) {
		// The other half: a conversation we started must cost the peer nothing.
		//
		// Sized so that the difference is the whole margin. The peer first spends its budget down to one
		// probe plus a second of decay short of the ceiling, so with the refund every probe packet at the
		// end still fits. Then it answers a burst of our calls - which, refunded, changes nothing, and
		// unrefunded puts the total over the ceiling partway through the probe.
		//
		// The exchange is capped at the outbound burst because that is the most we can ask for in one go:
		// past it our own throttle spaces the calls out, and the peer's counter would decay underneath us.
		// One less than the capacity, because a burst admits one fewer packet than it is sized for - the
		// check fires at the capacity rather than past it.
		Node victim = new Node(8894);
		Node peer = new Node(8895);

		final int probe = INBOUND_BURST / 4;
		final int preload = INBOUND_BURST - probe - INBOUND_LIMIT;
		final int exchanges = OUTBOUND_BURST - 1;
		final AtomicInteger spent = new AtomicInteger();

		Future.all(vertx.deployVerticle(victim), vertx.deployVerticle(peer))
				.compose(unused -> emit(vertx, preload, 16, 5, () -> peer.sendRequest(victim)))
				.compose(unused -> vertx.timer(100))
				.compose(unused -> {
					spent.set(victim.receivedRequests.get());
					context.verify(() -> assertEquals(preload, spent.get(),
							"the preload alone is within the budget and should have been accepted whole"));

					return emit(vertx, exchanges, 16, 5, () -> victim.sendCall(peer));
				})
				.compose(unused -> vertx.timer(300))
				.compose(unused -> {
					context.verify(() -> assertEquals(exchanges, victim.receivedResponses.get(),
							"the peer answered every call; every answer should have been accepted"));

					return emit(vertx, probe, 16, 5, () -> peer.sendRequest(victim));
				})
				.compose(unused -> vertx.timer(300))
				.compose(unused -> {
					context.verify(() -> assertEquals(probe, victim.receivedRequests.get() - spent.get(),
							"answering our calls spent the peer's budget for its own traffic"));

					return Future.all(vertx.undeploy(victim.deploymentID()), vertx.undeploy(peer.deploymentID()));
				})
				.onComplete(context.succeedingThenComplete());
	}

	@Test
	@Timeout(value = 60, timeUnit = TimeUnit.SECONDS)
	public void testUnmatchedResponsesEarnNoRefund(Vertx vertx, VertxTestContext context) {
		// The refund is gated on the packet answering a call we made, from the address we sent it to. A
		// response matching no call proves nothing, so it is charged like any other packet - which this
		// pins by spending the budget on strays and then finding it spent.
		Node victim = new Node(8896);
		Node sender = new Node(8897);

		final int strays = INBOUND_BURST + INBOUND_LIMIT * 2;
		final int requests = INBOUND_LIMIT;

		Future.all(vertx.deployVerticle(victim), vertx.deployVerticle(sender))
				.compose(unused -> emit(vertx, strays, 16, 5,
						() -> sender.sendStrayResponse(victim)))
				.compose(unused -> emit(vertx, requests, 16, 5,
						() -> sender.sendRequest(victim)))
				.compose(unused -> vertx.timer(500))
				.compose(unused -> {
					context.verify(() -> assertTrue(victim.receivedRequests.get() < requests,
							"strays were refunded: all " + requests + " requests still got through"));

					return Future.all(vertx.undeploy(victim.deploymentID()), vertx.undeploy(sender.deploymentID()));
				})
				.onComplete(context.succeedingThenComplete());
	}
}
