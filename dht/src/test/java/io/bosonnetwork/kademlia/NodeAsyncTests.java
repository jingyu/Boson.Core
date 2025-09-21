package io.bosonnetwork.kademlia;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.ConnectionStatusListener;
import io.bosonnetwork.Id;
import io.bosonnetwork.Network;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Result;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoBox.Nonce;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.crypto.Signature.KeyPair;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.utils.FileUtils;
import io.bosonnetwork.utils.vertx.VertxFuture;

@EnabledIfSystemProperty(named = "io.bosonnetwork.environment", matches = "development")
@ExtendWith(VertxExtension.class)
public class NodeAsyncTests {
	private static Vertx vertx;
	private static final int TEST_NODES = 32;
	private static final int TEST_NODES_PORT_START = 39001;

	private static final Path testDir = Path.of(System.getProperty("java.io.tmpdir"), "boson", "NodeAsyncTests");

	private static InetAddress localAddr;

	private static KadNode bootstrap;
	private static final List<KadNode> testNodes = new ArrayList<>(TEST_NODES);

	private static VertxFuture<Void> startBootstrap() {
		System.out.println("\n\n\007🟢 Starting the bootstrap node ...");

		var config = NodeConfiguration.builder()
				.vertx(vertx)
				.address4(localAddr)
				.port(TEST_NODES_PORT_START - 1)
				.dataPath(testDir.resolve("nodes"  + File.separator + "node-bootstrap"))
				.storageURL("jdbc:sqlite:" + testDir.resolve("nodes"  + File.separator + "node-bootstrap" + File.separator + "storage.db"))
				.enableDeveloperMode()
				.build();

		bootstrap = new KadNode(config);
		return bootstrap.run();
	}

	private static VertxFuture<Void> stopBootstrap() {
		System.out.println("\n\n\007🟢 Stopping the bootstrap nodes ...\n");
		return bootstrap.shutdown();
	}

	private static <T> VertxFuture<Void> executeSequentially(int max, int index, Function<Integer, VertxFuture<T>> action) {
		if (index >= max)
			return VertxFuture.succeededFuture();

		return action.apply(index)
				.thenCompose(result -> executeSequentially(max, index + 1, action));
	}

	protected static VertxFuture<Void> executeSequentially(List<KadNode> nodes, int index, Function<KadNode, VertxFuture<Void>> action) {
		if (index >= nodes.size())
			return VertxFuture.succeededFuture();

		var node = nodes.get(index);
		return action.apply(node)
				.thenCompose(v -> executeSequentially(nodes, index + 1, action))
				.exceptionally(e -> {
					//noinspection CallToPrintStackTrace
					e.printStackTrace();
					return null;
				});
	}

	private static VertxFuture<KadNode> createTestNode(int index) {
		System.out.format("\n\n\007🟢 Starting the node %d ...\n", index);

		var config = NodeConfiguration.builder()
				.vertx(vertx)
				.address4(localAddr)
				.port(TEST_NODES_PORT_START + index)
				.dataPath(testDir.resolve("nodes"  + File.separator + "node-" + index))
				.storageURL("jdbc:sqlite:" + testDir.resolve("nodes"  + File.separator + "node-" + index + File.separator + "storage.db"))
				.addBootstrap(bootstrap.getNodeInfo().getV4())
				.enableDeveloperMode()
				.build();

		var node = new KadNode(config);
		testNodes.add(node);

		Promise<KadNode> promise = Promise.promise();
		node.addConnectionStatusListener(new ConnectionStatusListener() {
			@Override
			public void connected(Network network) {
				System.out.printf("\007🟢 The node %d - %s is ready ...\n", index, node.getId());
				promise.complete(node);
			}
		});

		node.run();
		return VertxFuture.of(promise.future());
	}

	private static VertxFuture<Void> startTestNodes() {
		return executeSequentially(TEST_NODES, 0, NodeAsyncTests::createTestNode)
				.whenComplete((v, e) -> {
					if (e == null)
						System.out.println("\n\n\007🟢 All the nodes are ready!!!");
					else
						System.out.println("\n\n\007⛔ Some nodes start failed!!!");
				});
	}

	private static VertxFuture<Void> stopTestNodes() {
		System.out.println("\n\n\007🟢 Stopping all the nodes ...\n");
		// cannot stop all the nodes in parallel, it will cause vertx internal error.
		return executeSequentially(testNodes, 0, KadNode::shutdown);
	}

	private static VertxFuture<Void> dumpRoutingTable(String name, KadNode node) {
		System.out.format("\007🟢 Dumping the routing table of %s %s ...\n", name, node.getId());
		var file = testDir.resolve("nodes" + File.separator + name + File.separator + "routingtable");
		try {
			var out = new PrintStream(Files.newOutputStream(file));
			return VertxFuture.of(bootstrap.getDHT(Network.IPv4).dumpRoutingTable(out).andThen(ar -> out.close()));
		} catch (IOException e) {
			return VertxFuture.failedFuture(e);
		}
	}

	/*/
	private static VertxFuture<Void> dumpRoutingTables() {
		return dumpRoutingTable("node-bootstrap", bootstrap).thenCompose(v -> {
			return executeSequentially(testNodes.size(), 0, index -> {
				KadNode node = testNodes.get(index);
				return dumpRoutingTable("node-" + index, node);
			});
		});
	}
	*/

	private static VertxFuture<Void> dumpRoutingTables() {
		List<Future<Void>> futures = new ArrayList<>(testNodes.size() + 1);
		futures.add(dumpRoutingTable("node-bootstrap", bootstrap).toVertxFuture());

		for (int i = 0; i < testNodes.size(); i++)
			futures.add(dumpRoutingTable("node-" + i, testNodes.get(i)).toVertxFuture());

		return VertxFuture.of(Future.all(futures).mapEmpty());
	}

	@BeforeAll
	@Timeout(value = TEST_NODES + 1, timeUnit = TimeUnit.MINUTES)
	static void setup(VertxTestContext context) throws Exception {
		localAddr = AddressUtils.getAllAddresses()
				.filter(Inet4Address.class::isInstance)
				.filter(AddressUtils::isAnyUnicast)
				.distinct()
				.findFirst()
				.orElse(null);

		if (localAddr == null)
			fail("No eligible address to run the test.");
		else
			System.out.println("🟢 local address: " + localAddr.getHostAddress());

		if (Files.exists(testDir))
			FileUtils.deleteFile(testDir);

		Files.createDirectories(testDir);

		vertx = Vertx.vertx(new VertxOptions()
				.setEventLoopPoolSize(32)
				.setWorkerPoolSize(8)
				.setBlockedThreadCheckIntervalUnit(TimeUnit.SECONDS)
				.setBlockedThreadCheckInterval(120));

		var future = startBootstrap().thenCompose(v -> startTestNodes());

		future.toVertxFuture().onComplete(context.succeeding(v -> {
			System.out.println("\n\n\007🟢 All the nodes are ready!!! starting to run the test cases");
			context.completeNow();
		}));
	}

	@AfterAll
	static void teardown(VertxTestContext context) throws Exception {
		dumpRoutingTables().thenCompose(v -> {
			return stopTestNodes();
		}).thenCompose(v -> {
			return stopBootstrap();
		}).thenRun(() -> {
			/*/
			try {
				FileUtils.deleteFile(testDir);
			} catch (Exception e) {
				context.failNow(e);
			}
			*/
			System.out.format("\n\n\007🟢 Test cases finished\n");
		}).toVertxFuture().onComplete(context.succeedingThenComplete());
	}

	@Test
	void testNodeWithPresetKey(VertxTestContext context) {
		var keypair = Signature.KeyPair.random();
		Id nodeId = Id.of(keypair.publicKey().bytes());

		var config = NodeConfiguration.builder()
				.vertx(vertx)
				.address4(localAddr)
				.port(TEST_NODES_PORT_START - 100)
				.privateKey(keypair.privateKey().bytes())
				.dataPath(testDir.resolve("nodes"  + File.separator + "node-" + nodeId))
				.build();

		var node = new KadNode(config);
		node.run()
				.thenRun(() -> context.verify(() -> assertEquals(nodeId, node.getId())))
				.thenCompose(v -> node.shutdown())
				.toVertxFuture().onComplete(context.succeedingThenComplete());
	}

	@Test
	@Timeout(value = TEST_NODES, timeUnit = TimeUnit.MINUTES)
	void testFindNode(VertxTestContext context) {
		executeSequentially(testNodes, 0, target -> {
			System.out.format("\n\n\007🟢 Looking up node %s ...\n", target.getId());

			return executeSequentially(testNodes, 0, node -> {
				System.out.format("\n\n\007⌛ %s looking up node %s ...\n", node.getId(), target.getId());
				var future = (VertxFuture<Result<NodeInfo>>) node.findNode(target.getId());
				return future.thenAccept(result -> {
					System.out.format("\007🟢 %s lookup node %s finished\n", node.getId(), target.getId());
					context.verify(() -> {
						assertNotNull(result);
						assertFalse(result.isEmpty());
						assertEquals(target.getNodeInfo().getV4(), result.getV4());
					});
				});
			});
		}).toVertxFuture().onComplete(context.succeedingThenComplete());
	}

	@Test
	@Timeout(value = TEST_NODES, timeUnit = TimeUnit.MINUTES)
	void testAnnounceAndFindPeer(VertxTestContext context) {
		executeSequentially(testNodes, 0, announcer -> {
			var p = PeerInfo.create(announcer.getId(), 8888);

			System.out.format("\n\n\007🟢 %s announce peer %s ...\n", announcer.getId(), p.getId());
			return ((VertxFuture<Void>)announcer.announcePeer(p)).thenCompose(v -> {
				System.out.format("\n\n\007🟢 Looking up peer %s ...\n", p.getId());

				return executeSequentially(testNodes, 0, node -> {
					System.out.format("\n\n\007⌛ %s looking up peer %s ...\n", node.getId(), p.getId());
					var future = (VertxFuture<List<PeerInfo>>) node.findPeer(p.getId(), 0);
					return future.thenAccept(result -> {
						System.out.format("\007🟢 %s lookup peer %s finished\n", node.getId(), p.getId());
						context.verify(() -> {
							assertNotNull(result);
							assertFalse(result.isEmpty());
							assertEquals(1, result.size());
							assertEquals(p, result.get(0));
						});
					});
				});
			});
		}).toVertxFuture().onComplete(context.succeedingThenComplete());
	}

	@Test
	@Timeout(value = TEST_NODES, timeUnit = TimeUnit.MINUTES)
	void testStoreAndFindValue(VertxTestContext context) {
		executeSequentially(testNodes, 0, announcer -> {
			var v = Value.createValue(("Hello from " + announcer.getId()).getBytes());

			System.out.format("\n\n\007🟢 %s store value %s ...\n", announcer.getId(), v.getId());

			return ((VertxFuture<Void>) announcer.storeValue(v)).thenCompose(na -> {
				System.out.format("\n\n\007🟢 Looking up value %s ...\n", v.getId());
				return executeSequentially(testNodes, 0, node -> {
					System.out.format("\n\n\007⌛ %s looking up value %s ...\n", node.getId(), v.getId());
					var future = (VertxFuture<Value>) node.findValue(v.getId());
					return future.thenAccept(result -> {
						System.out.format("\007🟢 %s lookup value %s finished\n", node.getId(), v.getId());
						context.verify(() -> {
							assertNotNull(result);
							assertEquals(v, result);
						});
					});
				});
			});
		}).toVertxFuture().onComplete(context.succeedingThenComplete());
	}

	@Test
	@Timeout(value = TEST_NODES, timeUnit = TimeUnit.MINUTES)
	void testUpdateAndFindSignedValue(VertxTestContext context) {
		var values = new ArrayList<Value>(TEST_NODES);

		// initial announcement
		executeSequentially(testNodes, 0, announcer -> {
			var peerKeyPair = KeyPair.random();
			var nonce = Nonce.random();
			final Value v;
			try {
				v = Value.createSignedValue(peerKeyPair, nonce, ("Hello from " + announcer.getId()).getBytes());
				values.add(v);
			} catch (Exception e) {
				context.failNow(e);
				return VertxFuture.failedFuture(e); // make compiler happy
			}

			System.out.format("\n\n\007🟢 %s store value %s ...\n", announcer.getId(), v.getId());
			return ((VertxFuture<Void>)announcer.storeValue(v)).thenCompose(na -> {
				System.out.format("\n\n\007🟢 Looking up value %s ...\n", v.getId());
				return executeSequentially(testNodes, 0, node -> {
					System.out.format("\n\n\007⌛ %s looking up value %s ...\n", node.getId(), v.getId());
					var future = (VertxFuture<Value>) node.findValue(v.getId());
					return future.thenAccept(result -> {
						System.out.format("\007🟢 %s lookup value %s finished\n", node.getId(), v.getId());
						context.verify(() -> {
							assertNotNull(result);
							assertArrayEquals(nonce.bytes(), v.getNonce());
							assertArrayEquals(peerKeyPair.publicKey().bytes(), v.getPublicKey().bytes());
							assertTrue(v.isMutable());
							assertTrue(v.isValid());
							assertEquals(v, result);
						});
					});
				});
			});
		}).thenCompose(unused -> {
			// update announcement
			return executeSequentially(testNodes.size(), 0, index -> {
				KadNode announcer = testNodes.get(index);
				final Value v;
				try {
					v = values.get(index).update(("Updated value from " + announcer.getId()).getBytes());
					values.set(index, v);
				} catch (Exception e) {
					context.failNow(e);
					return VertxFuture.failedFuture(e); // make compiler happy
				}

				System.out.format("\n\n\007🟢 %s update value %s ...\n", announcer.getId(), v.getId());
				return ((VertxFuture<Void>) announcer.storeValue(v)).thenCompose(unused1 -> {
					System.out.format("\n\n\007🟢 Looking up value %s ...\n", v.getId());
					return executeSequentially(testNodes, 0, node -> {
						System.out.format("\n\n\007⌛ %s looking up value %s ...\n", node.getId(), v.getId());
						return ((VertxFuture<Value>) node.findValue(v.getId())).thenAccept(result -> {
							System.out.format("\007🟢 %s lookup value %s finished\n", node.getId(), v.getId());
							context.verify(() -> {
								assertNotNull(result);
								assertTrue(v.isMutable());
								assertTrue(v.isValid());
								assertEquals(v, result);
							});
						});
					});
				});
			});
		}).toVertxFuture().onComplete(context.succeedingThenComplete());
	}

	@Test
	@Timeout(value = TEST_NODES, timeUnit = TimeUnit.MINUTES)
	void testUpdateAndFindEncryptedValue(VertxTestContext context) {
		var values = new ArrayList<Value>(TEST_NODES);
		var recipients = new ArrayList<KeyPair>(TEST_NODES);

		// initial announcement
		executeSequentially(testNodes, 0, announcer -> {
			var recipient = KeyPair.random();
			recipients.add(recipient);

			var peerKeyPair = KeyPair.random();
			var nonce = Nonce.random();
			var data = ("Hello from " + announcer.getId()).getBytes();
			final Value v;
			try {
				v = Value.createEncryptedValue(peerKeyPair, Id.of(recipient.publicKey().bytes()), nonce, data);
				values.add(v);
			} catch (Exception e) {
				context.failNow(e);
				return VertxFuture.failedFuture(e);
			}

			System.out.format("\n\n\007🟢 %s store value %s ...\n", announcer.getId(), v.getId());
			return ((VertxFuture<Void>) announcer.storeValue(v)).thenCompose(unused -> {
				System.out.format("\n\n\007🟢 Looking up value %s ...\n", v.getId());
				return executeSequentially(testNodes, 0, node -> {
					System.out.format("\n\n\007⌛ %s looking up value %s ...\n", node.getId(), v.getId());
					return ((VertxFuture<Value>) node.findValue(v.getId())).thenAccept(result -> {
						System.out.format("\007🟢 %s lookup value %s finished\n", node.getId(), v.getId());
						context.verify(() -> {
							assertNotNull(result);
							assertArrayEquals(nonce.bytes(), v.getNonce());
							assertArrayEquals(peerKeyPair.publicKey().bytes(), v.getPublicKey().bytes());
							assertTrue(v.isMutable());
							assertTrue(v.isEncrypted());
							assertTrue(v.isValid());
							assertEquals(v, result);

							var d = v.decryptData(recipient.privateKey());
							assertArrayEquals(data, d);
						});
					});
				});
			});
		}).thenCompose(unused -> {
			// update announcement
			return executeSequentially(testNodes.size(), 0, index -> {
				KadNode announcer = testNodes.get(index);
				var recipient = recipients.get(index);
				var data = ("Updated value from " + announcer.getId()).getBytes();
				final Value v;
				try {
					v = values.get(index).update(data);
					values.set(index, v);
				} catch (Exception e) {
					context.failNow(e);
					return VertxFuture.failedFuture(e);
				}

				System.out.format("\n\n\007🟢 %s update value %s ...\n", announcer.getId(), v.getId());
				return ((VertxFuture<Void>) announcer.storeValue(v)).thenCompose(unused1 -> {
					System.out.format("\n\n\007🟢 Looking up value %s ...\n", v.getId());
					return executeSequentially(testNodes, 0, node -> {
						System.out.format("\n\n\007⌛ %s looking up value %s ...\n", node.getId(), v.getId());
						return ((VertxFuture<Value>) node.findValue(v.getId())).thenAccept(result -> {
							System.out.format("\007🟢 %s lookup value %s finished\n", node.getId(), v.getId());
							context.verify(() -> {
								assertNotNull(result);
								assertTrue(v.isMutable());
								assertTrue(v.isEncrypted());
								assertTrue(v.isValid());
								assertEquals(v, result);

								var d = v.decryptData(recipient.privateKey());
								assertArrayEquals(data, d);
							});
						});
					});
				});
			});
		}).toVertxFuture().onComplete(context.succeedingThenComplete());
	}
}