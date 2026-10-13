package io.bosonnetwork.kademlia;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.StandardProtocolFamily;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.internal.VertxInternal;
import net.datafaker.Faker;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.ConnectionStatusListener;
import io.bosonnetwork.AnnounceResult;
import io.bosonnetwork.Id;
import io.bosonnetwork.LookupOption;
import io.bosonnetwork.Node;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Subscription;
import io.bosonnetwork.SubscriptionListener;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.crypto.Signature.KeyPair;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.utils.FileUtils;
import io.bosonnetwork.vertx.ContextualFuture;

@ExtendWith(VertxExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = NodeAsyncTests.TEST_NODES + 1, timeUnit = TimeUnit.MINUTES)
public class NodeAsyncTests {
	static final int TEST_NODES = 32;
	private static final int TEST_NODES_PORT_START = 39301;

	private static final Path testDir = Path.of(System.getProperty("java.io.tmpdir"), "boson", "NodeAsyncTests");

	private static final InetAddress localAddr = AddressUtils.getDefaultRouteAddress(Inet4Address.class);

	private static final Vertx testingVertx =Vertx.vertx(new VertxOptions()
			.setEventLoopPoolSize(32)
			.setWorkerPoolSize(8)
			.setBlockedThreadCheckIntervalUnit(TimeUnit.SECONDS)
			.setBlockedThreadCheckInterval(120));

	private static KadNode bootstrap;
	private static final List<KadNode> testNodes = new ArrayList<>(TEST_NODES);

	private static ContextualFuture<Void> startBootstrap() {
		System.out.println("\n\n\007🟢 Starting the bootstrap node ...");

		var config = NodeConfiguration.builder()
				.vertx(testingVertx)
				.address4(localAddr)
				.port(TEST_NODES_PORT_START - 1)
				.generateKeyPair()
				.dataDir(testDir.resolve("nodes"  + File.separator + "node-bootstrap"))
				.databaseUri("jdbc:sqlite:" + testDir.resolve("nodes"  + File.separator + "node-bootstrap" + File.separator + "storage.db"))
				.developerMode(true)
				.build();

		bootstrap = new KadNode(config);
		return bootstrap.start();
	}

	private static ContextualFuture<Void> stopBootstrap() {
		System.out.println("\n\n\007🟢 Stopping the bootstrap nodes ...\n");
		return bootstrap.stop();
	}

	private static <T> ContextualFuture<Void> executeSequentially(int count, Function<Integer, ContextualFuture<T>> action) {
		ContextualFuture<T> chain = ContextualFuture.succeededFuture();
		for (int i = 0; i < count; i++) {
			final int index = i;
			chain = chain.thenCompose(v -> action.apply(index).whenComplete((r, e) -> {
				if (e != null) {
					System.err.println("Index " + index + " failed");
					//noinspection CallToPrintStackTrace
					e.printStackTrace();
				}
			}));
		}

		return chain.thenApply(v -> null);
	}

	protected static ContextualFuture<Void> executeSequentially(List<KadNode> nodes, Function<KadNode, ContextualFuture<Void>> action) {
		ContextualFuture<Void> chain = ContextualFuture.succeededFuture();

		for (final KadNode node : nodes) {
			chain = chain.thenCompose(v -> action.apply(node).whenComplete((r, e) -> {
				if (e != null) {
					System.err.println("Node " + node.getId() + " failed");
					//noinspection CallToPrintStackTrace
					e.printStackTrace();
				}
			}));
		}

		return chain;
	}

	private static ContextualFuture<KadNode> createTestNode(int index) {
		System.out.format("\n\n\007🟢 Starting the node %d ...\n", index);

		var config = NodeConfiguration.builder()
				.vertx(testingVertx)
				.address4(localAddr)
				.port(TEST_NODES_PORT_START + index)
				.generateKeyPair()
				.dataDir(testDir.resolve("nodes"  + File.separator + "node-" + index))
				.databaseUri("jdbc:sqlite:" + testDir.resolve("nodes"  + File.separator + "node-" + index + File.separator + "storage.db"))
				.addBootstrap(bootstrap.getNodeInfo().orElseThrow())
				.developerMode(true)
				.build();

		var node = new KadNode(config);
		testNodes.add(node);

		Promise<Void> promise = Promise.promise();
		node.addConnectionStatusListener(new ConnectionStatusListener() {
			@Override
			public void connected() {
				System.out.printf("\007🟢 The node %d - %s is ready ...\n", index, node.getId());
				promise.complete();
			}
		});

		// WARNING: This currently causes teardown failures.
		//          Stopping the first node triggers undeployment of all test nodes.
		//          The root cause is still unknown and needs further investigation.
		/*/
		node.start();
		return ContextualFuture.of(promise.future()).thenApply(v -> node);
		*/
		// But this will work well.
		return node.start().thenCompose(v -> ContextualFuture.of(promise.future())).thenApply(v -> node);
	}

	private static ContextualFuture<Void> startTestNodes() {
		return executeSequentially(TEST_NODES, NodeAsyncTests::createTestNode)
				.whenComplete((v, e) -> {
					if (e == null)
						System.out.println("\n\n\007🟢 All the nodes are ready!!!");
					else
						System.out.println("\n\n\007⛔ Some nodes start failed!!!");
				});
	}

	/**
	 * Actively converge the freshly-joined network before running lookups. Nodes join sequentially, so
	 * each node's bootstrap self-lookup ran against a partial network and the node was never re-announced
	 * once the rest joined. Now that all nodes are present, have every node perform a final self-lookup:
	 * each lookup announces the node to its neighborhood (the queried nodes add it to their routing
	 * tables) and fills the node's own buckets - Kademlia's standard convergence step - so every node
	 * becomes mutually findable. This targets reachability directly rather than inferring it from
	 * routing-table sizes; Kademlia keeps only k entries per bucket, so no node ever holds every other
	 * node anyway, and iterative routing - not full tables - is what makes a target findable.
	 */
	private static ContextualFuture<Void> awaitConvergence() {
		System.out.println("\n\n\007⌛ Converging the network via self-lookups ...");
		List<ContextualFuture<?>> lookups = new ArrayList<>(testNodes.size());
		for (var node : testNodes)
			// Result is irrelevant; the lookup's side effect (announce + bucket fill) is what converges.
			lookups.add((ContextualFuture<?>) node.findNode(node.getId()));

		return ContextualFuture.allOf(lookups)
				.whenComplete((v, e) -> System.out.println("\007🟢 Network converged via self-lookups"));
	}

	private static ContextualFuture<Void> stopTestNodes() {
		System.out.println("\n\n\007🟢 Stopping all the nodes ...\n");
		// cannot stop all the nodes in parallel, it will cause vertx internal error.
		return executeSequentially(testNodes, n -> {
			System.out.format("\n\n\007🟢 Stopping the node %s ...\n", n.getId());
			return n.stop();
		});
	}

	private static ContextualFuture<Void> dumpRoutingTable(String name, KadNode node) {
		System.out.format("\007🟢 Dumping the routing table of %s %s ...\n", name, node.getId());
		var file = testDir.resolve("nodes" + File.separator + name + File.separator + "routingtable");
		try {
			var out = new PrintStream(Files.newOutputStream(file));
			return ContextualFuture.of(node.dumpRoutingTable(StandardProtocolFamily.INET, out)
					.whenComplete((r, e) -> out.close()));
		} catch (IOException e) {
			return ContextualFuture.failedFuture(e);
		}
	}

	private static ContextualFuture<Void> dumpRoutingTables() {
		List<ContextualFuture<?>> futures = new ArrayList<>(testNodes.size() + 1);
		futures.add(dumpRoutingTable("node-bootstrap", bootstrap));

		for (int i = 0; i < testNodes.size(); i++)
			futures.add(dumpRoutingTable("node-" + i, testNodes.get(i)));

		return ContextualFuture.allOf(futures);
	}

	// in Vert.x 4.5.x, not support asynchronous lifecycle on static @BeforeAll and @AfterAll methods.
	// So we use synchronous method to do setup and teardown to make it compatible with Vert.x 4.5.x and 5.0.x

	@BeforeAll
	@Timeout(value = TEST_NODES + 1, timeUnit = TimeUnit.MINUTES)
	static void setup(VertxTestContext context) throws Exception {
		if (Files.exists(testDir))
			FileUtils.deleteFile(testDir);

		Files.createDirectories(testDir);

		startBootstrap().thenCompose(v -> startTestNodes())
				.thenCompose(v -> awaitConvergence())
				.whenComplete((v, e) -> {
					if (e == null) {
						System.out.println("\n\n\007🟢 All the nodes are ready!!! starting to run the test cases");
						context.completeNow();
					} else {
						context.failNow(e);
					}
				});
	}

	@AfterAll
	@Timeout(value = TEST_NODES + 1, timeUnit = TimeUnit.SECONDS)
	static void teardown(VertxTestContext context) {
		dumpRoutingTables().thenCompose(v -> stopTestNodes())
				.thenCompose(v -> stopBootstrap())
				.thenRun(() -> {
					try {
						FileUtils.deleteFile(testDir);
					} catch (Exception e) {
						throw new CompletionException(e);
					}
				}).whenComplete((v, e) -> {
					if (e == null) {
						System.out.format("\n\n\007🟢 Test cases finished\n");
						context.completeNow();
					} else {
						context.failNow(e);
					}
				});
	}

	@Test
	void testNodeWithPresetKey(VertxTestContext context) {
		var keypair = Signature.KeyPair.random();
		Id nodeId = Id.of(keypair.publicKey().bytes());

		var config = NodeConfiguration.builder()
				.vertx(testingVertx)
				.address4(localAddr)
				.port(TEST_NODES_PORT_START - 100)
				.privateKey(keypair.privateKey().bytes())
				.dataDir(testDir.resolve("nodes"  + File.separator + "node-" + nodeId))
				.build();

		var node = new KadNode(config);
		node.start()
				.thenRun(() -> context.verify(() -> assertEquals(nodeId, node.getId())))
				.thenCompose(v -> node.stop())
				.toVertxFuture().onComplete(context.succeedingThenComplete());
	}

	@Test
	@Timeout(value = TEST_NODES, timeUnit = TimeUnit.MINUTES)
	void testFindNode(VertxTestContext context) {
		executeSequentially(testNodes, target -> {
			System.out.format("\n\n\007🟢 Looking up node %s ...\n", target.getId());

			return executeSequentially(testNodes, node -> {
				System.out.format("\n\n\007⌛ %s looking up node %s ...\n", node.getId(), target.getId());
				var future = (ContextualFuture<Optional<NodeInfo>>) node.findNode(target.getId());
				return future.thenAccept(ni -> {
					System.out.format("\007🟢 %s lookup node %s finished\n", node.getId(), target.getId());
					context.verify(() -> {
						assertFalse(ni.isEmpty());
						assertEquals(target.getNodeInfo(), ni);
					});
				});
			});
		}).toVertxFuture().onComplete(context.succeedingThenComplete());
	}

	/**
	 * Enforces the invariant that the public {@link Node} API never leaks internal {@link NodeInfo}
	 * subtypes ({@code KBucketEntry}, {@code CandidateNode}). Those are mutable and mutate on the DHT
	 * event loop, so handing one to a caller would be an encapsulation and thread-safety leak (see
	 * {@code KadNode.toPublicNodeInfo}).
	 */
	@Test
	@Timeout(value = TEST_NODES, timeUnit = TimeUnit.MINUTES)
	void testNodeApiDoesNotLeakNodeInfoSubtypes(VertxTestContext context) throws Exception {
		// Structural tripwire: the set of NodeInfo-returning Node API methods must match this allowlist.
		// If this fails, a new NodeInfo-returning method was added - make sure its result is normalized
		// to a plain NodeInfo and extend the runtime checks below before updating the allowlist.
		Set<String> nodeInfoReturningMethods = Arrays.stream(Node.class.getMethods())
				.filter(m -> mentionsNodeInfo(m.getGenericReturnType()))
				.map(Method::getName)
				.collect(Collectors.toSet());
		assertEquals(Set.of("findNode", "getNodeInfo"), nodeInfoReturningMethods,
				"Unexpected NodeInfo-returning Node API method; ensure its result is normalized to a plain NodeInfo");

		// Runtime: actual results must be exactly NodeInfo, never an internal subtype.
		var node = testNodes.get(0);
		var target = testNodes.get(TEST_NODES - 1);

		// getNodeInfo() is built by merge - always a fresh, plain NodeInfo.
		assertSame(NodeInfo.class, node.getNodeInfo().orElseThrow().getClass());

		// findNode() conservative lookup - the single-stack passthrough is normalized.
		Future.fromCompletionStage(node.findNode(target.getId()))
				.onComplete(context.succeeding(found -> {
					context.verify(() -> {
						assertFalse(found.isEmpty());
						assertSame(NodeInfo.class, found.get().getClass());
					});
				}));

		// findNode(LOCAL) returns a routing-table entry (KBucketEntry) directly and must be normalized.
		// After the conservative lookup the target is usually cached; assert only when present.
		Future.fromCompletionStage(node.findNode(target.getId(), LookupOption.LOCAL))
				.onComplete(context.succeeding(found -> {
					context.verify(() -> {
						assertFalse(found.isEmpty());
						assertSame(NodeInfo.class, found.get().getClass());
					});
					context.completeNow();
				}));
	}

	/** Recursively checks whether {@link NodeInfo} appears anywhere in a (possibly generic) type. */
	private static boolean mentionsNodeInfo(Type type) {
		if (type instanceof Class<?> clazz)
			return clazz == NodeInfo.class;

		if (type instanceof ParameterizedType pt) {
			for (Type arg : pt.getActualTypeArguments())
				if (mentionsNodeInfo(arg))
					return true;
		}

		if (type instanceof WildcardType wt) {
			for (Type bound : wt.getUpperBounds())
				if (mentionsNodeInfo(bound))
					return true;
		}

		return false;
	}

	@Test
	@Timeout(value = TEST_NODES, timeUnit = TimeUnit.MINUTES)
	void testUpdateAndFindPeer(VertxTestContext context) {
		var peers = new ArrayList<PeerInfo>(TEST_NODES);

		// initial announcement
		executeSequentially(testNodes, announcer -> {
			var p = PeerInfo.builder()
					.node(announcer)
					.fingerprint(Random.random().nextLong())
					.endpoint("tcp://" + localAddr.getHostAddress() + ":8888")
					.build();
			peers.add(p);

			System.out.format("\n\n\007🟢 %s announce peer %s ...\n", announcer.getId(), p.getId());
			return ((ContextualFuture<AnnounceResult>)announcer.announcePeer(p)).thenCompose(v -> {
				System.out.format("\n\n\007🟢 Looking up peer %s ...\n", p.getId());

				return executeSequentially(testNodes, node -> {
					System.out.format("\n\n\007⌛ %s looking up peer %s ...\n", node.getId(), p.getId());
					var future = ((ContextualFuture<Optional<PeerInfo>>) node.findPeer(p.getId())).thenApply(o -> o.orElse(null));
					return future.thenAccept(result -> {
						System.out.format("\007🟢 %s lookup peer %s finished\n", node.getId(), p.getId());
						context.verify(() -> {
							assertNotNull(result);
							assertEquals(p, result);
						});
					});
				});
			});
		}).thenCompose(unused -> {
			Faker faker = new Faker();
			return executeSequentially(testNodes.size(), index -> {
				KadNode announcer = testNodes.get(index);
				final PeerInfo p = peers.get(index).update().node(announcer).endpoint(faker.internet().url()).build();

				System.out.format("\n\n\007🟢 %s announce peer %s ...\n", announcer.getId(), p.getId());
				return ((ContextualFuture<AnnounceResult>) announcer.announcePeer(p)).thenCompose(v -> {
					System.out.format("\n\n\007🟢 Looking up peer %s ...\n", p.getId());

					return executeSequentially(testNodes, node -> {
						System.out.format("\n\n\007⌛ %s looking up peer %s ...\n", node.getId(), p.getId());
						var future = ((ContextualFuture<Optional<PeerInfo>>) node.findPeer(p.getId())).thenApply(o -> o.orElse(null));
						return future.thenAccept(result -> {
							System.out.format("\007🟢 %s lookup peer %s finished\n", node.getId(), p.getId());
							context.verify(() -> {
								assertNotNull(result);
								assertEquals(p, result);
							});
						});
					});
				});
			});
		}).toVertxFuture().onComplete(context.succeedingThenComplete());
	}

	@Test
	void testAnswersOnTheCallersContext(VertxTestContext context) {
		// A service calls the node from a context of its own. Each answer must come back there, not on the
		// node's event loop: a service carrying on there touched its state from two threads (a web service
		// lost request bodies, counters raised on one thread were lowered on the other).
		KadNode node = testNodes.get(0);
		KadNode other = testNodes.get(1);
		ContextInternal nodeContext = (ContextInternal) node.vertxContext();
		assertNotNull(nodeContext);
		ContextInternal caller;
		do {
			caller = ((VertxInternal) testingVertx).createEventLoopContext();
		} while (caller.nettyEventLoop() == nodeContext.nettyEventLoop());
		ContextInternal service = caller;

		Value value = Value.immutableBuilder().data(("Asked from a service " + Id.random()).getBytes()).build();
		Promise<Void> checked = Promise.promise();
		service.runOnContext(x -> {
			List<CompletableFuture<?>> answers = new ArrayList<>();
			answers.add(node.findNode(other.getId()));
			answers.add(node.storeValue(value).thenCompose(v -> {
				context.verify(() -> assertTrue(service.isRunningOnContext(), "storeValue came back on another context"));
				return node.findValue(value.getId());
			}));
			answers.add(node.findPeer(other.getId()));
			answers.add(node.getValue(value.getId()));

			List<Future<Boolean>> where = answers.stream()
					.map(answer -> Future.fromCompletionStage(answer.handle((r, e) -> service.isRunningOnContext())))
					.toList();
			Future.all(where).onComplete(ar -> {
				context.verify(() -> {
					for (int i = 0; i < where.size(); i++)
						assertTrue(where.get(i).result(), "answer " + i + " came back on another context");
				});
				checked.complete();
			});
		});
		checked.future().onComplete(context.succeedingThenComplete());
	}

	@Test
	@Timeout(value = TEST_NODES, timeUnit = TimeUnit.MINUTES)
	void testStoreAndFindValue(VertxTestContext context) {
		executeSequentially(testNodes, announcer -> {
			var v = Value.immutableBuilder().data(("Hello from " + announcer.getId()).getBytes()).build();

			System.out.format("\n\n\007🟢 %s store value %s ...\n", announcer.getId(), v.getId());

			return ((ContextualFuture<AnnounceResult>) announcer.storeValue(v)).thenCompose(na -> {
				System.out.format("\n\n\007🟢 Looking up value %s ...\n", v.getId());
				return executeSequentially(testNodes, node -> {
					System.out.format("\n\n\007⌛ %s looking up value %s ...\n", node.getId(), v.getId());
					var future = ((ContextualFuture<Optional<Value>>) node.findValue(v.getId())).thenApply(o -> o.orElse(null));
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
		executeSequentially(testNodes, announcer -> {
			var keyPair = KeyPair.random();
			final Value v = Value.signedBuilder().keepPrivateKey().key(keyPair).data(("Hello from " + announcer.getId()).getBytes()).build();
			values.add(v);

			System.out.format("\n\n\007🟢 %s store value %s ...\n", announcer.getId(), v.getId());
			return ((ContextualFuture<AnnounceResult>)announcer.storeValue(v)).thenCompose(na -> {
				System.out.format("\n\n\007🟢 Looking up value %s ...\n", v.getId());
				return executeSequentially(testNodes, node -> {
					System.out.format("\n\n\007⌛ %s looking up value %s ...\n", node.getId(), v.getId());
					var future = ((ContextualFuture<Optional<Value>>) node.findValue(v.getId())).thenApply(o -> o.orElse(null));
					return future.thenAccept(result -> {
						System.out.format("\007🟢 %s lookup value %s finished\n", node.getId(), v.getId());
						context.verify(() -> {
							assertNotNull(result);
							assertArrayEquals(keyPair.publicKey().bytes(), v.getPublicKey().bytesUnsafe());
							assertTrue(v.isMutable());
							assertTrue(v.isValid());
							assertEquals(v, result);
						});
					});
				});
			});
		}).thenCompose(unused -> {
			// update announcement
			return executeSequentially(testNodes.size(), index -> {
				KadNode announcer = testNodes.get(index);
				final Value v;
				try {
					v = values.get(index).update().data(("Updated value from " + announcer.getId()).getBytes()).build();
					values.set(index, v);
				} catch (Exception e) {
					context.failNow(e);
					return ContextualFuture.failedFuture(e); // make compiler happy
				}

				System.out.format("\n\n\007🟢 %s update value %s ...\n", announcer.getId(), v.getId());
				return ((ContextualFuture<AnnounceResult>) announcer.storeValue(v)).thenCompose(unused1 -> {
					System.out.format("\n\n\007🟢 Looking up value %s ...\n", v.getId());
					return executeSequentially(testNodes, node -> {
						System.out.format("\n\n\007⌛ %s looking up value %s ...\n", node.getId(), v.getId());
						return ((ContextualFuture<Optional<Value>>) node.findValue(v.getId())).thenApply(o -> o.orElse(null)).thenAccept(result -> {
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
		executeSequentially(testNodes, announcer -> {
			var recipient = KeyPair.random();
			recipients.add(recipient);

			var keyPair = KeyPair.random();
			var data = ("Hello from " + announcer.getId()).getBytes();
			final Value v  = Value.encryptedBuilder().keepPrivateKey().key(keyPair).recipient(Id.of(recipient.publicKey().bytes())).data(data).build();
			values.add(v);

			System.out.format("\n\n\007🟢 %s store value %s ...\n", announcer.getId(), v.getId());
			return ((ContextualFuture<AnnounceResult>) announcer.storeValue(v)).thenCompose(unused -> {
				System.out.format("\n\n\007🟢 Looking up value %s ...\n", v.getId());
				return executeSequentially(testNodes, node -> {
					System.out.format("\n\n\007⌛ %s looking up value %s ...\n", node.getId(), v.getId());
					return ((ContextualFuture<Optional<Value>>) node.findValue(v.getId())).thenApply(o -> o.orElse(null)).thenAccept(result -> {
						System.out.format("\007🟢 %s lookup value %s finished\n", node.getId(), v.getId());
						context.verify(() -> {
							assertNotNull(result);
							assertArrayEquals(keyPair.publicKey().bytes(), v.getPublicKey().bytesUnsafe());
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
			return executeSequentially(testNodes.size(), index -> {
				KadNode announcer = testNodes.get(index);
				var recipient = recipients.get(index);
				var data = ("Updated value from " + announcer.getId()).getBytes();
				final Value v;
				try {
					v = values.get(index).update().data(data).build();
					values.set(index, v);
				} catch (Exception e) {
					context.failNow(e);
					return ContextualFuture.failedFuture(e);
				}

				System.out.format("\n\n\007🟢 %s update value %s ...\n", announcer.getId(), v.getId());
				return ((ContextualFuture<AnnounceResult>) announcer.storeValue(v)).thenCompose(unused1 -> {
					System.out.format("\n\n\007🟢 Looking up value %s ...\n", v.getId());
					return executeSequentially(testNodes, node -> {
						System.out.format("\n\n\007⌛ %s looking up value %s ...\n", node.getId(), v.getId());
						return ((ContextualFuture<Optional<Value>>) node.findValue(v.getId())).thenApply(o -> o.orElse(null)).thenAccept(result -> {
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

	/**
	 * End to end: a node subscribed to a value hears each new version written by another node, once and
	 * in order, and a second listener on the same node is caught up at once.
	 */
	@Test
	@Timeout(value = 2, timeUnit = TimeUnit.MINUTES)
	void testSubscribeValue(VertxTestContext context) throws Exception {
		KadNode subscriber = testNodes.get(3);
		KadNode writer = testNodes.get(TEST_NODES - 4);
		KeyPair owner = KeyPair.random();
		Value v1 = Value.signedBuilder().key(owner).sequenceNumber(1).data("first".getBytes()).build();
		Value v2 = Value.signedBuilder().key(owner).sequenceNumber(2).data("second".getBytes()).build();

		List<Value> received = new CopyOnWriteArrayList<>();
		Promise<Void> gotV1 = Promise.promise();
		Promise<Void> gotV2 = Promise.promise();
		SubscriptionListener<Value> listener = value -> {
			received.add(value);
			if (value.getSequenceNumber() == 1)
				gotV1.tryComplete();
			else if (value.getSequenceNumber() == 2)
				gotV2.tryComplete();
		};

		Promise<Value> lateJoiner = Promise.promise();
		Subscription[] subscription = new Subscription[1];

		// Nothing is stored yet: the storing nodes accept a subscription to a value that does not exist.
		subscriber.subscribeValue(v1.getId(), listener).thenCompose(sub -> {
			subscription[0] = sub;
			context.verify(() -> assertFalse(sub.isCancelled()));
			return writer.storeValue(v1);
		}).thenCompose(r -> ContextualFuture.of(gotV1.future()))
				.thenCompose(v -> writer.storeValue(v2))
				.thenCompose(r -> ContextualFuture.of(gotV2.future()))
				.thenCompose(v -> subscriber.subscribeValue(v1.getId(), lateJoiner::tryComplete))
				.thenCompose(late -> ContextualFuture.of(lateJoiner.future()).thenCompose(v -> late.cancel()))
				.thenCompose(v -> subscription[0].cancel())
				.whenComplete((v, e) -> {
					if (e != null) {
						context.failNow(e);
						return;
					}

					context.verify(() -> {
						assertEquals(List.of(v1, v2), received, "each version once, in order");
						assertEquals(v2, lateJoiner.future().result(), "a late listener is caught up at once");
						assertTrue(subscription[0].isCancelled());
					});
					context.completeNow();
				});
	}

	/**
	 * End to end: a node subscribed to a peer id hears a new instance, and a changed one, announced by another
	 * node.
	 */
	@Test
	@Timeout(value = 2, timeUnit = TimeUnit.MINUTES)
	void testSubscribePeer(VertxTestContext context) throws Exception {
		KadNode subscriber = testNodes.get(5);
		KadNode announcer = testNodes.get(TEST_NODES - 6);
		KeyPair key = KeyPair.random();
		PeerInfo first = PeerInfo.builder().key(key).fingerprint(1).sequenceNumber(1)
				.endpoint("tcp://203.0.113.10:3456").build();
		PeerInfo moved = PeerInfo.builder().key(key).fingerprint(1).sequenceNumber(2)
				.endpoint("tcp://203.0.113.11:3456").build();

		List<PeerInfo> received = new CopyOnWriteArrayList<>();
		Promise<Void> gotFirst = Promise.promise();
		Promise<Void> gotMoved = Promise.promise();
		SubscriptionListener<PeerInfo> listener = peer -> {
			received.add(peer);
			if (peer.getSequenceNumber() == 1)
				gotFirst.tryComplete();
			else if (peer.getSequenceNumber() == 2)
				gotMoved.tryComplete();
		};

		subscriber.subscribePeer(first.getId(), listener).thenCompose(sub ->
				announcer.announcePeer(first)
						.thenCompose(r -> ContextualFuture.of(gotFirst.future()))
						.thenCompose(v -> announcer.announcePeer(moved))
						.thenCompose(r -> ContextualFuture.of(gotMoved.future()))
						.thenCompose(v -> sub.cancel())
		).whenComplete((v, e) -> {
			if (e != null) {
				context.failNow(e);
				return;
			}

			context.verify(() -> assertEquals(List.of(first, moved), received));
			context.completeNow();
		});
	}
}
