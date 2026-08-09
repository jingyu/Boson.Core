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

package io.bosonnetwork.kademlia.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.kademlia.security.Blacklist;
import io.bosonnetwork.kademlia.storage.DataStorage;
import io.bosonnetwork.utils.AddressUtils;

/**
 * Covers what a bootstrap costs when one of the configured servers does not answer.
 * <p>
 * The fan-out used to wait for every server to reach a final state, and an unanswered call only gets
 * there when its RPC times out. That made a dead server cost the full timeout on every attempt - and
 * because {@code DHT.deploy()} holds {@code ConnectionStatus.Connected} behind the startup bootstrap,
 * a node with one dead server in its list reported itself disconnected for ten seconds after it was
 * perfectly usable.
 * </p>
 * <p>
 * The dead server here is a UDP socket that receives and never replies. A closed port would not do:
 * the OS answers it with ICMP unreachable, the call settles immediately, and the case under test
 * never happens.
 * </p>
 * <p>
 * Everything binds to the default-route address rather than loopback, as the live suites do:
 * {@code AddressUtils.isAnyUnicast} excludes loopback, so on 127.0.0.1 an answering peer never enters
 * the routing table and the second assertion below would be impossible.
 * </p>
 */
@ExtendWith(VertxExtension.class)
public class DHTBootstrapServersTests {
	private static final Path testDir = Path.of(System.getProperty("java.io.tmpdir"), "boson", "DHTBootstrapServersTests");

	// Kademlia parameters, as KadNode would pass them down from NodeConfiguration.KademliaOptions.
	private static final int K = 16;
	private static final int REPLACEMENTS = 8;
	private static final int ALPHA = 3;
	private static final int CONCURRENT_TASKS = 32;

	// A real interface rather than loopback, because loopback is not unicast as far as AddressUtils is
	// concerned and so never reaches a routing table - which would cost this test its second assertion.
	// Null on a host with no default route, which setUp turns into a skip rather than an
	// ExceptionInInitializerError that takes the whole class down.
	private static final InetAddress DEFAULT_ROUTE = AddressUtils.getDefaultRouteAddress(Inet4Address.class);
	private static final String HOST = DEFAULT_ROUTE == null ? null : DEFAULT_ROUTE.getHostAddress();
	private static final int SERVER_PORT = 39401;
	private static final int NODE_PORT = 39402;
	private static final int SILENT_PORT = 39403;

	/**
	 * The assertion bound. Comfortably above what the fix costs - one grace window plus a lookup
	 * between two nodes on this machine - and comfortably below the ten-second RPC timeout that the old
	 * behaviour waited for, so the test neither flakes under load nor passes if waiting-for-all comes
	 * back.
	 */
	private static final long MAX_BOOTSTRAP_MILLIS = 5000;

	private DataStorage storage;
	private DHT server;
	private DHT node;
	private DatagramSocket silent;

	@BeforeEach
	void setUp(Vertx vertx, VertxTestContext testContext) throws IOException {
		assumeTrue(HOST != null, "no default IPv4 route, so there is no usable non-loopback address");

		if (Files.notExists(testDir))
			Files.createDirectories(testDir);

		TokenManager tokenManager = new TokenManager();
		storage = DataStorage.create("jdbc:sqlite:" + testDir.resolve("storage.db"), 0, null);

		storage.initialize(vertx, TimeUnit.HOURS.toMillis(1), TimeUnit.HOURS.toMillis(1)).compose(unused -> {
			server = newDht(new CryptoIdentity(), SERVER_PORT, "server.cache", tokenManager);
			node = newDht(new CryptoIdentity(), NODE_PORT, "node.cache", tokenManager);

			return vertx.deployVerticle(server)
					.compose(unused2 -> vertx.deployVerticle(node))
					// Receives and never answers, which is what a dead-but-routable server looks like.
					.compose(unused2 -> vertx.createDatagramSocket().listen(SILENT_PORT, HOST))
					.map(socket -> {
						silent = socket;
						return (Void) null;
					});
		}).onComplete(testContext.succeedingThenComplete());
	}

	@AfterEach
	void tearDown(Vertx vertx, VertxTestContext testContext) {
		Future.join(
				server == null ? Future.succeededFuture() : vertx.undeploy(server.deploymentID()).otherwiseEmpty(),
				node == null ? Future.succeededFuture() : vertx.undeploy(node.deploymentID()).otherwiseEmpty(),
				silent == null ? Future.succeededFuture() : silent.close().otherwiseEmpty()
		).compose(unused -> storage == null ? Future.succeededFuture() : storage.close())
				.otherwiseEmpty()
				.onComplete(unused -> {
					deleteRecursively(testDir);
					testContext.completeNow();
				});
	}

	private DHT newDht(CryptoIdentity identity, int port, String cacheName, TokenManager tokenManager) {
		return new DHT(identity, Network.IPv4, HOST, port, List.of(),
				ALPHA, K, REPLACEMENTS, CONCURRENT_TASKS,
				storage, testDir.resolve(cacheName), tokenManager,
				Blacklist.empty(), false, false, true, null);
	}

	private static void deleteRecursively(Path dir) {
		if (dir == null || !Files.exists(dir))
			return;

		try (var paths = Files.walk(dir)) {
			paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// best effort cleanup
				}
			});
		} catch (IOException ignored) {
			// best effort cleanup
		}
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testDeadServerDoesNotHoldUpTheBootstrap(VertxTestContext testContext) {
		// A real node that answers, and one that never will. The dead one needs a decodable id like any
		// other peer, since the request to it is encrypted to that id - Id.random() is only sometimes a
		// valid public key.
		NodeInfo live = NodeInfo.of(server.getIdentity().getId(), HOST, SERVER_PORT);
		NodeInfo dead = NodeInfo.of(new CryptoIdentity().getId(), HOST, SILENT_PORT);

		long started = System.currentTimeMillis();
		node.bootstrap(List.of(live, dead)).onComplete(testContext.succeeding(unused -> testContext.verify(() -> {
			long elapsed = System.currentTimeMillis() - started;

			// The window is the assertion, and both ends of it carry weight.
			//
			// Above the grace, because that is what proves a server actually answered: the grace timer is
			// armed by a response, so a run where nothing was heard from anyone does not pass through
			// here at all - it waits for every call to settle, which is the RPC timeout. Without this
			// bound the test would also pass if both servers had failed instantly.
			//
			// Below the ceiling, because that is the defect: waiting for the dead server to settle costs
			// RPC_CALL_TIMEOUT_MAX, twice this bound.
			assertTrue(elapsed >= KadConstants.BOOTSTRAP_NODE_GRACE,
					"the bootstrap should have waited out the grace after the live server answered, but took "
							+ elapsed + "ms - did anything answer at all?");
			assertTrue(elapsed < MAX_BOOTSTRAP_MILLIS,
					"a bootstrap must not wait out a dead server's RPC timeout, but took " + elapsed + "ms");

			// Independent of the clock: the live node answered and became a contact, so the run above was
			// a real bootstrap rather than something that resolved early without talking to anyone.
			assertTrue(node.getRoutingTable().getNumberOfEntries() > 0,
					"the answering bootstrap node should have entered the routing table");

			testContext.completeNow();
		})));
	}
}
