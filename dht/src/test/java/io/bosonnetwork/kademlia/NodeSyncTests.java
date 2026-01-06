package io.bosonnetwork.kademlia;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.PrintStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.bosonnetwork.ConnectionStatusListener;
import io.bosonnetwork.Id;
import io.bosonnetwork.Network;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.CryptoBox.Nonce;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.crypto.Signature.KeyPair;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.utils.FileUtils;
import io.bosonnetwork.vertx.VertxFuture;

public class NodeSyncTests {
	private static Vertx vertx;
	private static final int TEST_NODES = 32;
	private static final int TEST_NODES_PORT_START = 39001;

	private static final Path testDir = Path.of(System.getProperty("java.io.tmpdir"), "boson", "NodeSyncTests");

	private static InetAddress localAddr;

	private static KadNode bootstrap;
	private static final List<KadNode> testNodes = new ArrayList<>(TEST_NODES);

	private static void startBootstrap() throws Exception {
		System.out.println("\n\n\007🟢 Starting the bootstrap node ...");

		var config = NodeConfiguration.builder()
				.vertx(vertx)
				.address4(localAddr)
				.port(TEST_NODES_PORT_START - 1)
				.dataDir(testDir.resolve("nodes"  + File.separator + "node-bootstrap"))
				.database("jdbc:sqlite:" + testDir.resolve("nodes"  + File.separator + "node-bootstrap" + File.separator + "storage.db"))
				.enableDeveloperMode()
				.build();

		bootstrap = new KadNode(config);
		bootstrap.start().get();
	}

	private static void stopBootstrap() throws Exception {
		System.out.println("\n\n\007🟢 Stopping the bootstrap node ...\n");
		bootstrap.stop().get();
	}

	private static void startTestNodes() throws Exception {
		for (int i = 0; i < TEST_NODES; i++) {
			System.out.format("\n\n\007🟢 Starting the node %d ...\n", i);

			var config = NodeConfiguration.builder()
					.vertx(vertx)
					.address4(localAddr)
					.port(TEST_NODES_PORT_START + i)
					.dataDir(testDir.resolve("nodes"  + File.separator + "node-" + i))
					.database("jdbc:sqlite:" + testDir.resolve("nodes"  + File.separator + "node-" + i + File.separator + "storage.db"))
					.addBootstrap(bootstrap.getNodeInfo().getV4())
					.enableDeveloperMode()
					.build();

			var node = new KadNode(config);
			CompletableFuture<Void> future = new CompletableFuture<>();
			node.addConnectionStatusListener(new ConnectionStatusListener() {
				@Override
				public void connected(Network network) {
					future.complete(null);
				}
			});
			node.start().get();
			testNodes.add(node);

			System.out.printf("\n\n\007⌛ Wainting for the test node %d - %s ready ...\n", i, node.getId());
			future.get();
			System.out.printf("\007🟢 The node %d - %s is ready ...\n", i, node.getId());
		}

		System.out.println("\n\n\007⌛ Wainting for all the nodes ready ...");
		TimeUnit.SECONDS.sleep(5);
	}

	private static void stopTestNodes() throws Exception {
		System.out.println("\n\n\007🟢 Stopping all the nodes ...\n");

		for (var node : testNodes)
			node.stop().get();
	}

	private static void dumpRoutingTables() throws Exception {
		System.out.format("\007🟢 Dumping the routing table of bootstrap node %s ...\n", bootstrap.getId());
		var file = testDir.resolve("nodes" + File.separator + "node-bootstrap" + File.separator + "routingtable");
		try (var out = new PrintStream(Files.newOutputStream(file))) {
			VertxFuture.of(bootstrap.getDHT(Network.IPv4).dumpRoutingTable(out)).get();
		}

		for (int i = 0; i < testNodes.size(); i++) {
			var node = testNodes.get(i);
			System.out.format("\007🟢 Dumping the routing table of node %s ...\n", node.getId());
			var dht = node.getDHT(Network.IPv4);
			//noinspection SpellCheckingInspection
			file = testDir.resolve("nodes"  + File.separator + "node-" + i + File.separator + "routingtable");
			try (var out = new PrintStream(Files.newOutputStream(file))) {
				VertxFuture.of(dht.dumpRoutingTable(out)).get();
			}
		}
	}

	@BeforeAll
	@Timeout(value = TEST_NODES + 1, unit = TimeUnit.MINUTES)
	static void setup() throws Exception {
		localAddr = AddressUtils.getDefaultRouteAddress(Inet4Address.class);

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

		startBootstrap();
		startTestNodes();

		System.out.println("\n\n\007🟢 All the nodes are ready!!! starting to run the test cases");
	}

	@AfterAll
	static void teardown() throws Exception {
		dumpRoutingTables();
		stopTestNodes();
		stopBootstrap();

		VertxFuture.of(vertx.close()).get();

		FileUtils.deleteFile(testDir);
	}

	@Test
	void testNodeWithPresetKey() throws Exception {
		var keypair = Signature.KeyPair.random();
		Id nodeId = Id.of(keypair.publicKey().bytes());

		var config = NodeConfiguration.builder()
				.vertx(vertx)
				.address4(localAddr)
				.port(TEST_NODES_PORT_START - 100)
				.privateKey(keypair.privateKey().bytes())
				.dataDir(testDir.resolve("nodes"  + File.separator + "node-" + nodeId))
				.build();

		var node = new KadNode(config);
		node.start().get();

		assertEquals(nodeId, node.getId());

		node.stop().get();
	}

	@Test
	@Timeout(value = TEST_NODES, unit = TimeUnit.MINUTES)
	void testFindNode() throws Exception {
		for (int i = 0; i < TEST_NODES; i++) {
			var target = testNodes.get(i);
			System.out.format("\n\n\007🟢 %d Looking up node %s ...\n", i, target.getId());

			for (int j = 0; j < TEST_NODES; j++) {
				var node = testNodes.get(j);
				System.out.format("\n\n\007⌛ %d:%d %s looking up node %s ...\n", i, j, node.getId(), target.getId());
				var result = node.findNode(target.getId()).get();
				System.out.format("\007🟢 %s lookup node %s finished\n", node.getId(), target.getId());

				assertNotNull(result);
				assertFalse(result.isEmpty());
				assertEquals(target.getNodeInfo().getV4(), result.getV4());
			}
		}
	}

	@Test
	@Timeout(value = TEST_NODES, unit = TimeUnit.MINUTES)
	void testAnnounceAndFindPeer() throws Exception {
		for (int i = 0; i < TEST_NODES; i++) {
			Thread.sleep(1000);
			var announcer = testNodes.get(i);
			var p = PeerInfo.create(announcer.getId(), 8888);

			System.out.format("\n\n\007🟢 %s announce peer %s ...\n", announcer.getId(), p.getId());
			announcer.announcePeer(p).get();

			System.out.format("\n\n\007🟢 Looking up peer %s ...\n", p.getId());
			for (int j = 0; j < TEST_NODES; j++) {
				var node = testNodes.get(j);
				System.out.format("\n\n\007⌛ %s looking up peer %s ...\n", node.getId(), p.getId());
				var result = node.findPeer(p.getId(), 0).get();
				System.out.format("\007🟢 %s lookup peer %s finished\n", node.getId(), p.getId());

				assertNotNull(result);
				assertFalse(result.isEmpty());
				assertEquals(1, result.size());
				assertEquals(p, result.get(0));
			}
		}
	}

	@Test
	@Timeout(value = TEST_NODES, unit = TimeUnit.MINUTES)
	void testStoreAndFindValue() throws Exception {
		for (int i = 0; i < TEST_NODES; i++) {
			var announcer = testNodes.get(i);
			var v = Value.createValue(("Hello from " + announcer.getId()).getBytes());

			System.out.format("\n\n\007🟢 %s store value %s ...\n", announcer.getId(), v.getId());
			announcer.storeValue(v).get();

			System.out.format("\n\n\007🟢 Looking up value %s ...\n", v.getId());
			for (int j = 0; j < TEST_NODES; j++) {
				var node = testNodes.get(j);
				System.out.format("\n\n\007⌛ %s looking up value %s ...\n", node.getId(), v.getId());
				var result = node.findValue(v.getId()).get();
				System.out.format("\007🟢 %s lookup value %s finished\n", node.getId(), v.getId());

				assertNotNull(result);
				assertEquals(v, result);
			}
		}
	}

	@Test
	@Timeout(value = TEST_NODES, unit = TimeUnit.MINUTES)
	void testUpdateAndFindSignedValue() throws Exception {
		var values = new ArrayList<Value>(TEST_NODES);

		// initial announcement
		for (int i = 0; i < TEST_NODES; i++) {
			var announcer = testNodes.get(i);
			var peerKeyPair = KeyPair.random();
			var nonce = Nonce.random();
			var v = Value.createSignedValue(peerKeyPair, nonce, ("Hello from " + announcer.getId()).getBytes());
			values.add(v);

			System.out.format("\n\n\007🟢 %s store value %s ...\n", announcer.getId(), v.getId());
			announcer.storeValue(v).get();

			System.out.format("\n\n\007🟢 Looking up value %s ...\n", v.getId());
			for (int j = 0; j < TEST_NODES; j++) {
				var node = testNodes.get(j);
				System.out.format("\n\n\007⌛ %s looking up value %s ...\n", node.getId(), v.getId());
				var result = node.findValue(v.getId()).get();
				System.out.format("\007🟢 %s lookup value %s finished\n", node.getId(), v.getId());

				assertNotNull(result);
				assertArrayEquals(nonce.bytes(), v.getNonce());
				assertArrayEquals(peerKeyPair.publicKey().bytes(), v.getPublicKey().bytes());
				assertTrue(v.isMutable());
				assertTrue(v.isValid());
				assertEquals(v, result);
			}
		}

		// update announcement
		for (int i = 0; i < TEST_NODES; i++) {
			var announcer = testNodes.get(i);
			var v = values.get(i);
			v = v.update(("Updated value from " + announcer.getId()).getBytes());
			values.set(i, v);

			System.out.format("\n\n\007🟢 %s update value %s ...\n", announcer.getId(), v.getId());
			announcer.storeValue(v).get();

			System.out.format("\n\n\007🟢 Looking up value %s ...\n", v.getId());
			for (int j = 0; j < TEST_NODES; j++) {
				var node = testNodes.get(j);
				System.out.format("\n\n\007⌛ %s looking up value %s ...\n", node.getId(), v.getId());
				var result = node.findValue(v.getId()).get();
				System.out.format("\007🟢 %s lookup value %s finished\n", node.getId(), v.getId());

				assertNotNull(result);
				assertTrue(v.isMutable());
				assertTrue(v.isValid());
				assertEquals(v, result);
			}
		}
	}

	@Test
	@Timeout(value = TEST_NODES, unit = TimeUnit.MINUTES)
	void testUpdateAndFindEncryptedValue() throws Exception {
		var values = new ArrayList<Value>(TEST_NODES);
		var recipients = new ArrayList<KeyPair>(TEST_NODES);

		// initial announcement
		for (int i = 0; i < TEST_NODES; i++) {
			var announcer = testNodes.get(i);
			var recipient = KeyPair.random();
			recipients.add(recipient);

			var peerKeyPair = KeyPair.random();
			var nonce = Nonce.random();
			var data = ("Hello from " + announcer.getId()).getBytes();
			var v = Value.createEncryptedValue(peerKeyPair, Id.of(recipient.publicKey().bytes()), nonce, data);
			values.add(v);

			System.out.format("\n\n\007🟢 %s store value %s ...\n", announcer.getId(), v.getId());
			announcer.storeValue(v).get();

			System.out.format("\n\n\007🟢 Looking up value %s ...\n", v.getId());
			for (int j = 0; j < TEST_NODES; j++) {
				var node = testNodes.get(j);
				System.out.format("\n\n\007⌛ %s looking up value %s ...\n", node.getId(), v.getId());
				var result = node.findValue(v.getId()).get();
				System.out.format("\007🟢 %s lookup value %s finished\n", node.getId(), v.getId());

				assertNotNull(result);
				assertArrayEquals(nonce.bytes(), v.getNonce());
				assertArrayEquals(peerKeyPair.publicKey().bytes(), v.getPublicKey().bytes());
				assertTrue(v.isMutable());
				assertTrue(v.isEncrypted());
				assertTrue(v.isValid());
				assertEquals(v, result);

				var d = v.decryptData(recipient.privateKey());
				assertArrayEquals(data, d);
			}
		}

		// update announcement
		for (int i = 0; i < TEST_NODES; i++) {
			var announcer = testNodes.get(i);
			var recipient = recipients.get(i);

			var v = values.get(i);
			var data = ("Updated value from " + announcer.getId()).getBytes();
			v = v.update(data);
			values.set(i, v);

			System.out.format("\n\n\007🟢 %s update value %s ...\n", announcer.getId(), v.getId());
			announcer.storeValue(v).get();

			System.out.format("\n\n\007🟢 Looking up value %s ...\n", v.getId());
			for (int j = 0; j < TEST_NODES; j++) {
				var node = testNodes.get(j);
				System.out.format("\n\n\007⌛ %s looking up value %s ...\n", node.getId(), v.getId());
				var result = node.findValue(v.getId()).get();
				System.out.format("\007🟢 %s lookup value %s finished\n", node.getId(), v.getId());

				assertNotNull(result);
				assertTrue(v.isMutable());
				assertTrue(v.isEncrypted());
				assertTrue(v.isValid());
				assertEquals(v, result);

				var d = v.decryptData(recipient.privateKey());
				assertArrayEquals(data, d);
			}
		}
	}
}