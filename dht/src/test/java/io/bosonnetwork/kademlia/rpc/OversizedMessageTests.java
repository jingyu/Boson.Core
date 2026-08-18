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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

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
import io.bosonnetwork.Identity;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoBox;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.exceptions.KadException;
import io.bosonnetwork.kademlia.exceptions.MessageTooBigException;
import io.bosonnetwork.kademlia.impl.ErrorCode;
import io.bosonnetwork.kademlia.impl.Network;
import io.bosonnetwork.kademlia.impl.TestKadContext;
import io.bosonnetwork.kademlia.protocol.Error;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.security.Blacklist;
import io.bosonnetwork.vertx.BosonVerticle;

/**
 * Covers the size check on the send path: {@code RpcServer} measures the datagram it is about to send
 * and refuses to send one that does not fit.
 * <p>
 * Everything else that bounds a message is applied while the message is being built - a per-field limit
 * on a value or a peer, a per-entry estimate for a node list - so nothing else in the module ever sees
 * the bytes that actually go on the wire. This is the only check that measures, which makes it the one
 * that still holds when an estimate is wrong or when a record predates the limit that was supposed to
 * bound it.
 * </p>
 * <p>
 * Sending anyway is not a lesser evil: an oversized datagram is fragmented, a fragmented UDP datagram is
 * lost entirely if any one fragment is lost, and middleboxes drop fragments outright - so it would work
 * on the paths that need it least and fail silently on the rest.
 * </p>
 */
@ExtendWith(VertxExtension.class)
public class OversizedMessageTests {
	private static final String localAddr = "127.0.0.1";

	/**
	 * At roughly 48 bytes an entry this is about 1900 bytes, comfortably past the 1400-byte IPv4
	 * budget. Built from ordinary nodes on purpose: the guard has to catch a message that every field
	 * limit in the module considers legal, because that is the case no other check covers.
	 */
	private static final int TOO_MANY_NODES = 40;

	static class TestNode extends BosonVerticle {
		final Identity identity = new CryptoIdentity();
		final String host;
		final int port;
		final NodeInfo nodeInfo;

		RpcServer rpcServer;
		Consumer<Message> requestHandler = request -> { };
		int receivedRequests = 0;

		TestNode(String host, int port) {
			this.host = host;
			this.port = port;
			this.nodeInfo = NodeInfo.of(identity.getId(), host, port);
		}

		@Override
		protected void prepare(Vertx vertx, Context context) {
			super.prepare(vertx, context);

			rpcServer = new RpcServer(new TestKadContext(context, identity, Network.IPv4),
					host, port, Blacklist.empty(), true, null);
			rpcServer.setMessageHandler(message -> {
				if (message.isRequest()) {
					receivedRequests++;
					requestHandler.accept(message);
				}
			});
		}

		@Override
		protected Future<Void> deploy() {
			return rpcServer.start();
		}

		@Override
		protected Future<Void> undeploy() {
			return rpcServer != null ? rpcServer.stop().andThen(ar -> rpcServer = null) : Future.succeededFuture();
		}

		/** Runs the call on this node's own context, the way the DHT would. */
		Future<RpcCall> send(RpcCall call) {
			Promise<RpcCall> promise = Promise.promise();
			runOnContext(v -> rpcServer.sendCall(call).onComplete(promise));
			return promise.future();
		}

		void reply(Message request, Message response) {
			response.setRemote(request.getId(), request.getRemoteAddress());
			runOnContext(v -> rpcServer.sendMessage(response));
		}
	}

	private static Message oversizedResponse(long txid) {
		List<NodeInfo> nodes = new ArrayList<>(TOO_MANY_NODES);
		for (int i = 0; i < TOO_MANY_NODES; i++)
			nodes.add(NodeInfo.of(Id.random(), "203.0.113.10", 39001 + i));

		return Message.findNodeResponse(txid, nodes, null, null);
	}

	/** Completes when the call reaches a final state, whichever one it is. */
	private static Future<RpcCall> settled(RpcCall call) {
		Promise<RpcCall> promise = Promise.promise();
		call.addListener((c, previous, state) -> {
			if (state.isFinal())
				promise.tryComplete(c);
		});
		return promise.future();
	}

	private static Future<Void> withNodes(Vertx vertx, BiFunction<TestNode, TestNode, Future<Void>> body) {
		TestNode node1 = new TestNode(localAddr, 39301);
		TestNode node2 = new TestNode(localAddr, 39302);

		return Future.all(vertx.deployVerticle(node1), vertx.deployVerticle(node2))
				.compose(unused -> body.apply(node1, node2))
				.eventually(() -> Future.all(vertx.undeploy(node1.deploymentID()), vertx.undeploy(node2.deploymentID())))
				.mapEmpty();
	}

	/**
	 * The size check runs before encryption, so that a message which cannot be sent does not cost an
	 * encryption first. That makes what it checks a derivation of the datagram size - sender id, nonce,
	 * MAC and the serialized message - rather than the datagram itself.
	 * <p>
	 * A derivation is only as correct as the envelope it assumes, and nothing else in the module would
	 * notice if that envelope changed: the guard would keep passing and simply stop bounding anything,
	 * which is the failure this whole check exists to prevent. So the arithmetic is pinned here, against
	 * the encryption actually used rather than against a restatement of it.
	 * </p>
	 */
	@Test
	void theDerivedDatagramSizeMatchesTheEncryptedEnvelope() throws Exception {
		Identity identity = new CryptoIdentity();
		// A real key pair, not Id.random(): encryption converts the recipient id to a curve point.
		Id recipient = new CryptoIdentity().getId();

		for (int payload : new int[] { 1, 64, 512, 1024 }) {
			byte[] plain = Random.randomBytes(payload);
			assertEquals(plain.length + CryptoBox.Nonce.BYTES + CryptoBox.MAC_BYTES,
					identity.encrypt(recipient, plain).length,
					"RpcServer adds Id.BYTES to this to decide whether a message fits one datagram");
		}
	}

	/**
	 * A responder that cannot answer within one datagram says so, rather than sending nothing. Dropping
	 * the response silently would cost the requester a full timeout and read, from its side, exactly
	 * like this node being unreachable - which is the wrong lesson to teach it about a node that is up
	 * and answering.
	 */
	@Test
	@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
	void anOversizedResponseIsReportedAsMessageTooBig(Vertx vertx, VertxTestContext context) {
		withNodes(vertx, (node1, node2) -> {
			node2.requestHandler = request -> node2.reply(request, oversizedResponse(request.getTxid()));

			RpcCall call = new RpcCall(node2.nodeInfo, Message.findNodeRequest(Id.random(), true, false, false));
			Future<RpcCall> settled = settled(call);

			return node1.send(call).compose(unused -> settled).map(done -> {
				context.verify(() -> {
					assertEquals(RpcCall.State.ERROR, done.getState(), "an oversized response must not be silence");

					Message response = done.getResponse();
					assertTrue(response.isError());
					assertEquals(ErrorCode.MessageTooBig.value(), response.<Error>getBody().getCode(),
							"the error code the protocol reserves for this must be the one that is sent");
					assertEquals(Message.Method.FIND_NODE, response.getMethod(),
							"the substitute answers the request it replaces, or it is discarded as a wrong-method reply");

					// The round trip in full: the requester gets the typed exception back, not a bare
					// code it would have to compare by hand.
					KadException cause = assertInstanceOf(MessageTooBigException.class, done.getCause());
					assertEquals(ErrorCode.MessageTooBig.value(), cause.getCode());
				});
				return (Void) null;
			});
		}).onComplete(context.succeedingThenComplete());
	}

	/**
	 * The other direction, and the one with no remote party to inform: a request this node cannot send
	 * fails locally, so the caller learns now instead of waiting out a timeout for a datagram that was
	 * never going to leave. The value here is the case the guard exists for - one stored before the
	 * limits that would have refused it, reconstructed through {@code of()} exactly as the storage layer
	 * reconstructs it.
	 */
	@Test
	@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
	void anOversizedRequestFailsInsteadOfBeingSent(Vertx vertx, VertxTestContext context) {
		withNodes(vertx, (node1, node2) -> {
			Value legacy = Value.of(Id.random(), Random.randomBytes(2048));
			assertFalse(legacy.isValid(), "precondition: no node would accept this value today");

			RpcCall call = new RpcCall(node2.nodeInfo, Message.storeValueRequest(legacy, 0x12345678, 9));

			return node1.send(call).transform(ar -> {
				context.verify(() -> {
					assertTrue(ar.failed(), "a request that cannot fit a datagram must not report itself sent");

					KadException cause = assertInstanceOf(MessageTooBigException.class, ar.cause());
					assertEquals(ErrorCode.MessageTooBig.value(), cause.getCode(),
							"the local failure carries the same code the remote party would have been told");

					assertEquals(0, node2.receivedRequests, "nothing may have been put on the wire");
				});
				return Future.succeededFuture((Void) null);
			});
		}).onComplete(context.succeedingThenComplete());
	}
}
