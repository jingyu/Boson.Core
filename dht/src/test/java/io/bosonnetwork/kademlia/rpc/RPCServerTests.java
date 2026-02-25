/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import net.datafaker.Faker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.Network;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.protocol.FindPeerRequest;
import io.bosonnetwork.kademlia.protocol.FindValueRequest;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.security.Blacklist;
import io.bosonnetwork.kademlia.security.SuspiciousNodeDetector;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.vertx.BosonVerticle;

@ExtendWith(VertxExtension.class)
public class RPCServerTests {
	private static final int TEST_MESSAGES = 1000;

	private static final Faker faker = new Faker();

	@SuppressWarnings("ConstantConditions")
	private static final String localAddr = AddressUtils.getDefaultRouteAddress(Inet4Address.class).getHostAddress();

	private final static Map<Id, Value> values = new HashMap<>();
	private final static Map<Id, List<PeerInfo>> peers = new HashMap<>();

	static class TestNode extends BosonVerticle {
		final Identity identity;
		final String host;
		final int port;
		KadContext kadContext;
		RpcServer rpcServer;

		private final NodeInfo nodeInfo;

		private boolean simulateAbnormal;

		int sendFailed = 0;

		int sentMessages = 0;
		int receivedMessages = 0;

		int sentRequests = 0;
		int receivedRequests = 0;

		int sentResponses = 0;
		int receivedResponses = 0;

		int sentErrors = 0;
		int receivedErrors = 0;

		int sentUnknowns = 0;

		int timeoutCalls = 0;
		int manualTimeouts = 0;

		public TestNode(String host, int port) {
			this.host = host;
			this.port = port;

			this.identity = new CryptoIdentity();
			this.nodeInfo = new NodeInfo(identity.getId(), host, port);

			this.simulateAbnormal = false;
		}

		public Network getNetwork() {
			return Network.IPv4;
		}

		@Override
		protected void prepare(Vertx vertx, Context context) {
			super.prepare(vertx, context);

			kadContext = new KadContext(vertx, context, identity, getNetwork(), null);
			rpcServer = new RpcServer(kadContext, host, port, Blacklist.empty(), SuspiciousNodeDetector.disabled(), true, null);
			rpcServer.setMessageHandler(this::onMessage);
			rpcServer.setCallTimeoutHandler(this::callTimeout);
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

		public NodeInfo getNodeInfo() {
			return nodeInfo;
		}

		public void setSimulateAbnormal(boolean simulateAbnormal) {
			this.simulateAbnormal = simulateAbnormal;
		}

		protected void sendCall(RpcCall call) {
			//noinspection CodeBlock2Expr
			runOnContext(v -> {
				rpcServer.sendCall(call).andThen(ar -> {
					if (ar.succeeded()) {
						sentMessages++;
						sentRequests++;
						if (call.getRequest().getMethod() == Message.Method.UNKNOWN)
							sentUnknowns++;
					} else {
						sendFailed++;
					}
				});
			});
		}

		protected void sendMessage(Message<?> message) {
			//noinspection CodeBlock2Expr
			runOnContext(v -> {
				rpcServer.sendMessage(message).andThen(ar -> {
					if (ar.succeeded()) {
						sentMessages++;
						if (message.isRequest())
							sentRequests++;
						else if (message.isResponse())
							sentResponses++;
						else if (message.isError())
							sentErrors++;

						if (message.getMethod() == Message.Method.UNKNOWN)
							sentUnknowns++;
					} else {
						sendFailed++;
						System.out.println("Failed to send message: " + message);
					}
				});
			});
		}

		@SuppressWarnings("unchecked")
		private void onRequest(Message<?> message) {
			Message<?> response;

			if (simulateAbnormal) {
				double chance = Random.random().nextDouble();
				if (chance < 0.1) {
					// 10% chance of a timeout
					manualTimeouts++;
					return;
				} else if (chance > 0.9) {
					// 10% chance of error response
					response = Message.error(message.getMethod(), message.getTxid(), 12345, "Test error");
					response.setRemote(message.getId(), message.getRemoteAddress());
					sendMessage(response);
					return;
				} else if (chance > 0.8 && chance < 0.9) {
					// 10% chance of wrong response
					response = Message.message(Message.Type.RESPONSE, Message.Method.UNKNOWN, message.getTxid(), null);
					response.setRemote(message.getId(), message.getRemoteAddress());
					sendMessage(response);
					return;
				}
			}

			response = switch (message.getMethod()) {
				case PING -> Message.pingResponse(message.getTxid());
				case FIND_NODE -> Message.findNodeResponse(message.getTxid(), createRandomNodes(8), null, 0);
				case FIND_VALUE -> {
					Message<FindValueRequest> request = (Message<FindValueRequest>) message;
					if (values.containsKey(request.getBody().getTarget()))
						yield Message.findValueResponse(message.getTxid(), values.get(request.getBody().getTarget()));
					else
						yield Message.findValueResponse(message.getTxid(), createRandomNodes(8), null);
				}
				case STORE_VALUE -> Message.storeValueResponse(message.getTxid());
				case FIND_PEER -> {
					Message<FindPeerRequest> request = (Message<FindPeerRequest>) message;
					if (peers.containsKey(request.getBody().getTarget()))
						yield Message.findPeerResponse(message.getTxid(), peers.get(request.getBody().getTarget()));
					else
						yield Message.findPeerResponse(message.getTxid(), createRandomNodes(8), null);
				}
				case ANNOUNCE_PEER -> Message.announcePeerResponse(message.getTxid());
				default -> Message.error(message.getMethod(), message.getTxid(), 1000, "Test error");
			};

			response.setRemote(message.getId(), message.getRemoteAddress());
			sendMessage(response);
		}

		private void onMessage(Message<?> message) {
			receivedMessages++;

			if (message.isRequest()) {
				receivedRequests++;
				onRequest(message);
			} else if (message.isResponse()) {
				receivedResponses++;
			} else if (message.isError()) {
				receivedErrors++;
			}
		}

		private void callTimeout(RpcCall call) {
			System.out.println(">>>>>>>> RPC call timed out: " + call.getTxid());
			timeoutCalls++;
		}

		@Override
		public String toString() {
			//noinspection StringBufferReplaceableByString
			StringBuilder repr = new StringBuilder(160);

			repr.append("Statistics[").append(identity.getId()).append("]:\n")
					.append("  - sentFailed = ").append(sendFailed).append('\n')
					.append("  - sentMessages = ").append(sentMessages).append('\n')
					.append("  - receivedMessages = ").append(receivedMessages).append('\n')
					.append("  - sentRequests = ").append(sentRequests).append('\n')
					.append("  - receivedRequests = ").append(receivedRequests).append('\n')
					.append("  - sentResponses = ").append(sentResponses).append('\n')
					.append("  - receivedResponses = ").append(receivedResponses).append('\n')
					.append("  - sentErrors = ").append(sentErrors).append('\n')
					.append("  - receivedErrors = ").append(receivedErrors).append('\n')
					.append("  - sentUnknowns = ").append(sentUnknowns).append('\n')
					.append("  - timeoutCalls = ").append(timeoutCalls).append('\n')
					.append("  - manualTimeouts = ").append(manualTimeouts).append("\n\n");

			return repr.toString();
		}
	}

	@SuppressWarnings("SameParameterValue")
	protected static List<NodeInfo> createRandomNodes(int count) {
		List<NodeInfo> nodes = new ArrayList<>();
		for (int i = 0; i < count; i++)
			nodes.add(new NodeInfo(Id.random(), faker.internet().getPublicIpV4Address(), 39001));

		return nodes;
	}

	protected static PeerInfo createPeerInfo() {
		PeerInfo peer = switch (Random.random().nextInt(0, 5)) {
			case 1 -> PeerInfo.builder().endpoint(faker.internet().url()).build();
			case 2 -> PeerInfo.builder().node(new CryptoIdentity()).endpoint("tcp:///203.0.113.10:" +  + faker.internet().port()).build();
			case 3 -> PeerInfo.builder().node(new CryptoIdentity()).endpoint(faker.internet().url()).build();
			default -> PeerInfo.builder().endpoint("tcp:///203.0.113.10:" + faker.internet().port()).build();
		};

		peers.put(peer.getId(), List.of(peer));
		return peer;
	}

	protected static Id createPeerInfo(int count) {
		Signature.KeyPair keyPair = Signature.KeyPair.random();
		Id id = Id.of(keyPair.publicKey().bytes());

		List<PeerInfo> infos = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			PeerInfo peer = switch (Random.random().nextInt(0, 5)) {
				case 1 -> PeerInfo.builder().key(keyPair).endpoint("http://foo.example.com/").build();
				case 2 -> PeerInfo.builder().key(keyPair).endpoint("tcp://203.0.113.10:1234").build();
				case 3 -> PeerInfo.builder().key(keyPair).node(new CryptoIdentity()).endpoint("http://bar.example.com/").build();
				default -> PeerInfo.builder().key(keyPair).node(new CryptoIdentity()).endpoint("http://abc.example.com/").build();
			};

			infos.add(peer);
		}

		peers.put(id, infos);
		return id;
	}

	protected static Value createValue() {
		try {
			Value value = switch (Random.random().nextInt(0, 3)) {
				case 0 -> Value.builder().data(faker.lorem().paragraph().getBytes()).build();
				case 1 -> Value.builder().data(faker.lorem().paragraph().getBytes()).buildSigned();
				case 2 -> Value.builder().recipient(Id.of(Signature.KeyPair.random().publicKey().bytes()))
						.data(faker.lorem().paragraph().getBytes()).buildEncrypted();
				default -> Value.builder().sequenceNumber(2).data(faker.lorem().paragraph().getBytes()).build();
			};

			values.put(value.getId(), value);
			return value;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	@BeforeEach
	void setUp() {
		values.clear();
		peers.clear();
	}

	@Test
	@Timeout(value = TEST_MESSAGES / 2, timeUnit = TimeUnit.SECONDS)
	public void testGoodMessages(Vertx vertx, VertxTestContext context) {
		//noinspection ExtractMethodRecommender
		CyclicBarrier barrier = new CyclicBarrier(2);

		BiConsumer<TestNode, TestNode> testRoutine = (local, remote) -> {
			try {
				barrier.await();
			} catch (InterruptedException | BrokenBarrierException e) {
				System.out.println("Failed to wait for barrier: " + e.getMessage());
				return;
			}

			for (int i = 0; i < TEST_MESSAGES; i++) {
				try {
					Thread.sleep(Random.random().nextInt(25, 64));
				} catch (InterruptedException e) {
					System.out.println("Thread was interrupted.");
					break;
				}

				Message<?> request = switch (i % 7) {
					case 0, 1 -> Message.pingRequest();
					case 2 -> Message.findNodeRequest(Id.random(), true, false, true);
					case 3 -> Message.announcePeerRequest(createPeerInfo(), Random.random().nextInt(1, Integer.MAX_VALUE), -1);
					case 4 -> Message.findPeerRequest(createPeerInfo(Random.random().nextInt(2, 8)), true, false, -1, 2);
					case 5 -> Message.storeValueRequest(createValue(), Random.random().nextInt(1, Integer.MAX_VALUE), Random.random().nextInt(1, 100));
					case 6 -> Message.findValueRequest(createValue().getId(), true, false, 0);
					default -> Message.message(Message.Type.REQUEST, Message.Method.UNKNOWN, 0x7FFF0123, null);
				};

				RpcCall call = new RpcCall(remote.getNodeInfo(), request);
				local.sendCall(call);
			}

			try {
				System.out.println("Waiting for pending messages to be processed...");
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				System.out.println("Thread was interrupted.");
			}
		};

		TestNode node1 = new TestNode(localAddr, 8888);
		TestNode node2 = new TestNode(localAddr, 8889);

		Future.succeededFuture().compose(unused -> {
			Future<String> future1 = vertx.deployVerticle(node1);
			Future<String> future2 = vertx.deployVerticle(node2);
			return Future.all(future1, future2);
		}).compose(unused -> {
			Promise<Void> promise1 = Promise.promise();
			new Thread(() -> {
				testRoutine.accept(node1, node2);
				promise1.complete();
			}).start();

			Promise<Void> promise2 = Promise.promise();
			new Thread(() -> {
				testRoutine.accept(node2, node1);
				promise2.complete();
			}).start();

			return Future.all(promise1.future(), promise2.future());
		}).compose(unused -> {
			Future<Void> future1 = vertx.undeploy(node1.deploymentID());
			Future<Void> future2 = vertx.undeploy(node2.deploymentID());
			return Future.all(future1, future2);
		}).onComplete(context.succeeding(unused -> {
			System.out.println("Node 1 - " + node1);
			System.out.println("Node 2 - " + node2);

			context.verify(() -> {
				assertEquals(0, node1.sendFailed);
				assertEquals(TEST_MESSAGES * 2, node1.sentMessages);
				assertEquals(TEST_MESSAGES * 2, node1.receivedMessages);
				assertEquals(TEST_MESSAGES, node1.sentRequests);
				assertEquals(TEST_MESSAGES, node1.receivedResponses);
				assertEquals(TEST_MESSAGES, node1.receivedRequests);
				assertEquals(TEST_MESSAGES, node1.sentResponses);
				assertEquals(0, node1.sentErrors);
				assertEquals(0, node1.receivedErrors);
				assertEquals(0, node1.sentUnknowns);
				assertEquals(0, node1.timeoutCalls);
				assertEquals(0, node1.manualTimeouts);


				assertEquals(0, node2.sendFailed);
				assertEquals(TEST_MESSAGES * 2, node2.sentMessages);
				assertEquals(TEST_MESSAGES * 2, node2.receivedMessages);
				assertEquals(TEST_MESSAGES, node2.sentRequests);
				assertEquals(TEST_MESSAGES, node2.receivedResponses);
				assertEquals(TEST_MESSAGES, node2.receivedRequests);
				assertEquals(TEST_MESSAGES, node2.sentResponses);
				assertEquals(0, node2.sentErrors);
				assertEquals(0, node2.receivedErrors);
				assertEquals(0, node2.sentUnknowns);
				assertEquals(0, node2.timeoutCalls);
				assertEquals(0, node2.manualTimeouts);
			});

			context.completeNow();
		}));
	}

	@Test
	@Timeout(value = TEST_MESSAGES / 2, timeUnit = TimeUnit.SECONDS)
	public void testRandomUnknownMessages(Vertx vertx, VertxTestContext context) {
		//noinspection ExtractMethodRecommender
		final CyclicBarrier barrier = new CyclicBarrier(2);

		BiConsumer<TestNode, TestNode> testRoutine = (local, remote) -> {
			try {
				barrier.await();
			} catch (InterruptedException | BrokenBarrierException e) {
				System.out.println("Failed to wait for barrier: " + e.getMessage());
				return;
			}

			for (int i = 0; i < TEST_MESSAGES; i++) {
				try {
					Thread.sleep(Random.random().nextInt(25, 64));
				} catch (InterruptedException e) {
					System.out.println("Thread was interrupted.");
					break;
				}

				Message<?> request = switch (i % 7) {
					case 1 -> Message.pingRequest();
					case 2 -> Message.findNodeRequest(Id.random(), true, false, true);
					case 3 -> Message.announcePeerRequest(createPeerInfo(), Random.random().nextInt(1, Integer.MAX_VALUE), 3);
					case 4 -> Message.findPeerRequest(createPeerInfo(Random.random().nextInt(2, 8)), true, false, 2, 1);
					case 5 -> Message.storeValueRequest(createValue(), Random.random().nextInt(1, Integer.MAX_VALUE), Random.random().nextInt(1, 100));
					case 6 -> Message.findValueRequest(createValue().getId(), true, false, 0);
					default -> Message.message(Message.Type.REQUEST, Message.Method.UNKNOWN, 0x7FFF0123, null);
				};

				RpcCall call = new RpcCall(remote.getNodeInfo(), request);
				local.sendCall(call);
			}

			try {
				System.out.println("Waiting for pending messages to be processed...");
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				System.out.println("Thread was interrupted.");
			}
		};

		TestNode node1 = new TestNode(localAddr, 8888);
		TestNode node2 = new TestNode(localAddr, 8889);

		Future.succeededFuture().compose(unused -> {
			Future<String> future1 = vertx.deployVerticle(node1);
			Future<String> future2 = vertx.deployVerticle(node2);
			return Future.all(future1, future2);
		}).compose(unused -> {
			Promise<Void> promise1 = Promise.promise();
			new Thread(() -> {
				testRoutine.accept(node1, node2);
				promise1.complete();
			}).start();

			Promise<Void> promise2 = Promise.promise();
			new Thread(() -> {
				testRoutine.accept(node2, node1);
				promise2.complete();
			}).start();

			return Future.all(promise1.future(), promise2.future());
		}).compose(unused -> {
			Future<Void> future1 = vertx.undeploy(node1.deploymentID());
			Future<Void> future2 = vertx.undeploy(node2.deploymentID());
			return Future.all(future1, future2);
		}).onComplete(context.succeeding(unused -> {
			System.out.println("Node 1 - " + node1);
			System.out.println("Node 2 - " + node2);

			int totalUnknowns = (TEST_MESSAGES + 6) / 7;

			context.verify(() -> {
				assertEquals(0, node1.sendFailed);
				assertEquals(TEST_MESSAGES * 2, node1.sentMessages);
				assertEquals(TEST_MESSAGES * 2, node1.receivedMessages);
				assertEquals(TEST_MESSAGES, node1.sentRequests);
				assertEquals(TEST_MESSAGES - totalUnknowns, node1.receivedResponses);
				assertEquals(TEST_MESSAGES, node1.receivedRequests);
				assertEquals(TEST_MESSAGES - totalUnknowns, node1.sentResponses);
				assertEquals(totalUnknowns, node1.sentErrors);
				assertEquals(totalUnknowns, node1.receivedErrors);
				assertEquals(totalUnknowns * 2, node1.sentUnknowns);
				assertEquals(0, node1.timeoutCalls);
				assertEquals(0, node1.manualTimeouts);


				assertEquals(0, node2.sendFailed);
				assertEquals(TEST_MESSAGES * 2, node2.sentMessages);
				assertEquals(TEST_MESSAGES * 2, node2.receivedMessages);
				assertEquals(TEST_MESSAGES, node2.sentRequests);
				assertEquals(TEST_MESSAGES - totalUnknowns, node2.receivedResponses);
				assertEquals(TEST_MESSAGES, node2.receivedRequests);
				assertEquals(TEST_MESSAGES - totalUnknowns, node2.sentResponses);
				assertEquals(totalUnknowns, node2.sentErrors);
				assertEquals(totalUnknowns, node2.receivedErrors);
				assertEquals(totalUnknowns * 2, node2.sentUnknowns);
				assertEquals(0, node2.timeoutCalls);
				assertEquals(0, node2.manualTimeouts);
			});

			context.completeNow();
		}));
	}

	@Test
	@Timeout(value = TEST_MESSAGES / 2, timeUnit = TimeUnit.SECONDS)
	public void testAbnormalMessages(Vertx vertx, VertxTestContext context) {
		//noinspection ExtractMethodRecommender
		final CyclicBarrier barrier = new CyclicBarrier(2);

		BiConsumer<TestNode, TestNode> testRoutine = (local, remote) -> {
			try {
				barrier.await();
			} catch (InterruptedException | BrokenBarrierException e) {
				System.out.println("Failed to wait for barrier: " + e.getMessage());
				return;
			}

			for (int i = 0; i < TEST_MESSAGES; i++) {
				try {
					Thread.sleep(Random.random().nextInt(25, 64));
				} catch (InterruptedException e) {
					System.out.println("Thread was interrupted.");
					break;
				}

				Message<?> request = switch (i % 7) {
					case 0, 1 -> Message.pingRequest();
					case 2 -> Message.findNodeRequest(Id.random(), true, false, true);
					case 3 -> Message.announcePeerRequest(createPeerInfo(), Random.random().nextInt(1, Integer.MAX_VALUE), -1);
					case 4 -> Message.findPeerRequest(createPeerInfo(Random.random().nextInt(2, 8)), true, false, 0, 1);
					case 5 -> Message.storeValueRequest(createValue(), Random.random().nextInt(1, Integer.MAX_VALUE), Random.random().nextInt(1, 100));
					case 6 -> Message.findValueRequest(createValue().getId(), true, false, 0);
					default -> Message.message(Message.Type.REQUEST, Message.Method.UNKNOWN, 0x7FFF0123, null);
				};

				RpcCall call = new RpcCall(remote.getNodeInfo(), request);
				local.sendCall(call);
			}

			try {
				System.out.println("Waiting for pending timeout messages to be processed...");
				Thread.sleep(RpcServer.RPC_CALL_TIMEOUT_MAX + 5000);
			} catch (InterruptedException e) {
				System.out.println("Thread was interrupted.");
			}
		};

		TestNode node1 = new TestNode(localAddr, 8888);
		TestNode node2 = new TestNode(localAddr, 8889);

		node1.setSimulateAbnormal(true);
		node2.setSimulateAbnormal(true);

		Future.succeededFuture().compose(unused -> {
			Future<String> future1 = vertx.deployVerticle(node1);
			Future<String> future2 = vertx.deployVerticle(node2);
			return Future.all(future1, future2);
		}).compose(unused -> {
			Promise<Void> promise1 = Promise.promise();
			new Thread(() -> {
				testRoutine.accept(node1, node2);
				promise1.complete();
			}).start();

			Promise<Void> promise2 = Promise.promise();
			new Thread(() -> {
				testRoutine.accept(node2, node1);
				promise2.complete();
			}).start();

			return Future.all(promise1.future(), promise2.future());
		}).compose(unused -> {
			Future<Void> future1 = vertx.undeploy(node1.deploymentID());
			Future<Void> future2 = vertx.undeploy(node2.deploymentID());
			return Future.all(future1, future2);
		}).onComplete(context.succeeding(unused -> {
			System.out.println("Node 1 - " + node1);
			System.out.println("Node 2 - " + node2);

			context.verify(() -> {
				assertEquals(0, node1.sendFailed);
				assertEquals(TEST_MESSAGES * 2 - node1.manualTimeouts, node1.sentMessages);
				assertEquals(TEST_MESSAGES * 2 - node2.manualTimeouts - node2.sentUnknowns, node1.receivedMessages);
				assertEquals(TEST_MESSAGES, node1.sentRequests);
				assertEquals(TEST_MESSAGES - node2.sentErrors - node2.manualTimeouts - node2.sentUnknowns, node1.receivedResponses);
				assertEquals(TEST_MESSAGES, node1.receivedRequests);
				assertEquals(TEST_MESSAGES - node1.sentErrors - node1.manualTimeouts, node1.sentResponses);
				assertEquals(node2.sentErrors, node1.receivedErrors);
				assertEquals(node2.manualTimeouts, node1.timeoutCalls);

				assertEquals(0, node2.sendFailed);
				assertEquals(TEST_MESSAGES * 2 - node2.manualTimeouts, node2.sentMessages);
				assertEquals(TEST_MESSAGES * 2 - node1.manualTimeouts - node1.sentUnknowns, node2.receivedMessages);
				assertEquals(TEST_MESSAGES, node2.sentRequests);
				assertEquals(TEST_MESSAGES - node1.sentErrors - node1.manualTimeouts - node1.sentUnknowns, node2.receivedResponses);
				assertEquals(TEST_MESSAGES, node2.receivedRequests);
				assertEquals(TEST_MESSAGES - node2.sentErrors - node2.manualTimeouts, node2.sentResponses);
				assertEquals(node1.sentErrors, node2.receivedErrors);
				assertEquals(node1.manualTimeouts, node2.timeoutCalls);
			});

			context.completeNow();
		}));
	}

	@Test
	public void testOutboundThrottling(Vertx vertx, VertxTestContext context) {
		//noinspection ExtractMethodRecommender
		final CyclicBarrier barrier = new CyclicBarrier(2);

		BiConsumer<TestNode, TestNode> testRoutine = (local, remote) -> {
			try {
				barrier.await();
			} catch (InterruptedException | BrokenBarrierException e) {
				System.out.println("Failed to wait for barrier: " + e.getMessage());
				return;
			}

			for (int i = 0; i < 150; i++) {
				Message<?> request = switch (i % 7) {
					case 0, 1 -> Message.pingRequest();
					case 2 -> Message.findNodeRequest(Id.random(), true, false, true);
					case 3 -> Message.announcePeerRequest(createPeerInfo(), Random.random().nextInt(1, Integer.MAX_VALUE), 2);
					case 4 -> Message.findPeerRequest(createPeerInfo(Random.random().nextInt(2, 8)), true, false, 2, 0);
					case 5 -> Message.storeValueRequest(createValue(), Random.random().nextInt(1, Integer.MAX_VALUE), Random.random().nextInt(1, 100));
					case 6 -> Message.findValueRequest(createValue().getId(), true, false, 0);
					default -> Message.message(Message.Type.REQUEST, Message.Method.UNKNOWN, 0x7FFF0123, null);
				};

				RpcCall call = new RpcCall(remote.getNodeInfo(), request);
				local.sendCall(call);
			}

			try {
				System.out.println("Waiting for pending timeout messages to be processed...");
				Thread.sleep(2000);
			} catch (InterruptedException e) {
				System.out.println("Thread was interrupted.");
			}
		};

		TestNode node1 = new TestNode(localAddr, 8888);
		TestNode node2 = new TestNode(localAddr, 8889);

		Future.succeededFuture().compose(unused -> {
			Future<String> future1 = vertx.deployVerticle(node1);
			Future<String> future2 = vertx.deployVerticle(node2);
			return Future.all(future1, future2);
		}).compose(unused -> {
			Promise<Void> promise1 = Promise.promise();
			new Thread(() -> {
				testRoutine.accept(node1, node2);
				promise1.complete();
			}).start();

			Promise<Void> promise2 = Promise.promise();
			new Thread(() -> {
				testRoutine.accept(node2, node1);
				promise2.complete();
			}).start();

			return Future.all(promise1.future(), promise2.future());
		}).compose(unused -> {
			Future<Void> future1 = vertx.undeploy(node1.deploymentID());
			Future<Void> future2 = vertx.undeploy(node2.deploymentID());
			return Future.all(future1, future2);
		}).onComplete(context.succeeding(unused -> {
			System.out.println("Node 1 - " + node1);
			System.out.println("Node 2 - " + node2);

			context.verify(() -> {
				// ????
				assertEquals(0, node1.sendFailed);
				assertEquals(0, node2.sendFailed);
			});

			context.completeNow();
		}));
	}
}