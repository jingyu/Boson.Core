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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.datagram.DatagramSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
// The vertx-junit5 timeout, not the JUnit one: VertxExtension enforces its own 30-second default on
// the VertxTestContext and does not consult org.junit.jupiter.api.Timeout. The sweep this test waits
// out is deliberately slower than that.
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;

import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.kademlia.security.Blacklist;
import io.bosonnetwork.kademlia.storage.DataStorage;
import io.bosonnetwork.utils.AddressUtils;

/**
 * Covers what a restart costs when the cached routing table has gone partly stale.
 * <p>
 * A warm start revalidates the persisted table with one {@code PingRefreshTask} per bucket, and those
 * tasks used to gate the connection status one promise each - so the node stayed {@code Connecting}
 * until the last cached contact had answered or timed out. A contact that has gone away is silent
 * rather than refusing, so it settles only at {@code RPC_CALL_TIMEOUT_MAX}, and a node that has just
 * started has no RTT samples yet, so that is the full ten seconds. One dead contact among the first
 * {@code alpha} of a bucket was therefore enough to hold a perfectly usable node in
 * {@code Connecting}.
 * </p>
 * <p>
 * The dead contacts here are UDP sockets that receive and never reply. Closed ports would not do: the
 * OS answers those with ICMP unreachable, the calls settle at once, and the case under test never
 * happens.
 * </p>
 * <p>
 * Everything binds to the default-route address rather than loopback, as the other live suites do:
 * {@code AddressUtils.isAnyUnicast} excludes loopback, so on 127.0.0.1 a cached contact would never
 * survive into the routing table at all.
 * </p>
 */
@ExtendWith(VertxExtension.class)
public class DHTWarmStartTests {
	private static final Path testDir = Path.of(System.getProperty("java.io.tmpdir"), "boson", "DHTWarmStartTests");

	// Kademlia parameters, as KadNode would pass them down from NodeConfiguration.KademliaOptions.
	private static final int K = 16;
	private static final int REPLACEMENTS = 8;
	private static final int ALPHA = 3;
	private static final int CONCURRENT_TASKS = 32;

	private static final InetAddress DEFAULT_ROUTE = AddressUtils.getDefaultRouteAddress(Inet4Address.class);
	private static final String HOST = DEFAULT_ROUTE == null ? null : DEFAULT_ROUTE.getHostAddress();
	private static final int SERVER_PORT = 39411;
	private static final int NODE_PORT = 39412;
	private static final int SILENT_PORT_BASE = 39420;

	/** How many dead contacts the cache carries besides the one live one. */
	private static final int DEAD_CONTACTS = 12;
	/** What the table may still hold once the sweep has done its work. */
	private static final int REMAINING_AFTER_SWEEP = 4;

	/**
	 * The assertion bound. Well under the ten seconds the old behaviour spent waiting out the first
	 * dead ping, and far enough above the couple of round trips the fix actually costs that ordinary
	 * scheduling noise cannot reach it.
	 */
	private static final long MAX_CONNECT_MILLIS = 3000;

	private DataStorage storage;
	private DHT server;
	private DHT node;
	private final List<DatagramSocket> silent = new ArrayList<>();
	private Path persistFile;

	@BeforeEach
	void setUp(Vertx vertx, VertxTestContext testContext) throws IOException {
		assumeTrue(HOST != null, "no default IPv4 route, so there is no usable non-loopback address");

		if (Files.notExists(testDir))
			Files.createDirectories(testDir);
		persistFile = testDir.resolve("node.cache");
		Files.deleteIfExists(persistFile);

		TokenManager tokenManager = new TokenManager();
		storage = DataStorage.create("jdbc:sqlite:" + testDir.resolve("storage.db"), 0, null);

		storage.initialize(vertx, TimeUnit.HOURS.toMillis(1), TimeUnit.HOURS.toMillis(1)).compose(unused -> {
			server = newDht(new CryptoIdentity(), SERVER_PORT, null, tokenManager);

			Future<Void> sockets = Future.succeededFuture();
			for (int i = 0; i < DEAD_CONTACTS; i++) {
				final int port = SILENT_PORT_BASE + i;
				sockets = sockets.compose(unused2 -> vertx.createDatagramSocket().listen(port, HOST)
						.map(socket -> {
							silent.add(socket);
							return (Void) null;
						}));
			}

			return sockets.compose(unused2 -> vertx.deployVerticle(server));
		}).onComplete(testContext.succeedingThenComplete());
	}

	@AfterEach
	void tearDown(Vertx vertx, VertxTestContext testContext) {
		List<Future<?>> closers = new ArrayList<>();
		if (server != null)
			closers.add(vertx.undeploy(server.deploymentID()).otherwiseEmpty());
		if (node != null)
			closers.add(vertx.undeploy(node.deploymentID()).otherwiseEmpty());
		silent.forEach(socket -> closers.add(socket.close().otherwiseEmpty()));

		Future.join(closers)
				.compose(unused -> storage == null ? Future.succeededFuture() : storage.close())
				.otherwiseEmpty()
				.onComplete(unused -> {
					deleteRecursively(testDir);
					testContext.completeNow();
				});
	}

	private DHT newDht(CryptoIdentity identity, int port, Path cache, TokenManager tokenManager) {
		return new DHT(identity, Network.IPv4, HOST, port, List.of(),
				ALPHA, K, REPLACEMENTS, CONCURRENT_TASKS,
				storage, cache, tokenManager,
				Blacklist.empty(), false, false, true, null);
	}

	/**
	 * Writes the cache the node under test will warm-start from: the live server first, then the dead
	 * contacts, all last seen an hour ago.
	 * <p>
	 * Written as CBOR rather than through {@code RoutingTable.save}, because the age is the whole
	 * point and a saved table can only carry the age of the entries in it - which, built here, would be
	 * seconds. {@code KBucketEntry.needsPing} declines to ping anything seen within the last 30 seconds
	 * and anything younger than {@code OLD_AND_STALE_TIME}, so a fresh cache is swept without a single
	 * packet being sent and the case under test never happens. An hour old is what a restart normally
	 * looks like.
	 * </p>
	 * <p>
	 * Only the fields {@code toMap} would have written are written here, and for the same reason: the
	 * zero-valued ones are omitted because CBOR decodes a small integer back to {@code Integer}, which
	 * the {@code (long)} casts in {@code fromMap} would reject, dropping the entry silently.
	 * </p>
	 */
	private void writeCache(CryptoIdentity identity) throws IOException {
		long now = System.currentTimeMillis();
		long lastSeen = now - TimeUnit.HOURS.toMillis(1);

		List<NodeInfo> contacts = new ArrayList<>();
		// The live server goes first: order survives the file, and the sweep pings alpha at a time, so
		// this puts the one contact that answers in the first batch - which is the case the assertion is
		// about, and the ordinary one, since a cache keeps the contacts that were working.
		contacts.add(NodeInfo.of(server.getIdentity().getId(), HOST, SERVER_PORT));
		for (int i = 0; i < DEAD_CONTACTS; i++)
			// A real key, not Id.random(): the ping is encrypted to the target's id, and a random 32
			// bytes is only sometimes a valid public key.
			contacts.add(NodeInfo.of(new CryptoIdentity().getId(), HOST, SILENT_PORT_BASE + i));

		try (OutputStream out = Files.newOutputStream(persistFile)) {
			CBORGenerator gen = Json.cborFactory().createGenerator(out);
			gen.writeStartObject();
			gen.writeBinaryField("nodeId", identity.getId().bytesUnsafe());
			gen.writeNumberField("timestamp", now);

			gen.writeFieldName("entries");
			gen.writeStartArray();
			for (NodeInfo contact : contacts) {
				gen.writeStartObject();
				gen.writeBinaryField("id", contact.getId().bytesUnsafe());
				gen.writeBinaryField("addr", contact.getIpAddress().getAddress());
				gen.writeNumberField("port", contact.getPort());
				gen.writeNumberField("created", lastSeen);
				gen.writeNumberField("lastSeen", lastSeen);
				gen.writeNumberField("avgRtt", 50);
				gen.writeBooleanField("reachable", true);
				gen.writeEndObject();
			}
			gen.writeEndArray();

			gen.writeFieldName("replacements");
			gen.writeStartArray();
			gen.writeEndArray();

			gen.writeEndObject();
			gen.close();
		}
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
	@Timeout(value = 120, timeUnit = TimeUnit.SECONDS)
	void testDeadCachedContactsDoNotHoldUpTheConnectionStatus(Vertx vertx, VertxTestContext testContext)
			throws IOException {
		CryptoIdentity identity = new CryptoIdentity();
		writeCache(identity);

		node = newDht(identity, NODE_PORT, persistFile, new TokenManager());

		Promise<Long> connected = Promise.promise();
		long started = System.currentTimeMillis();
		node.setConnectionStatusListener(new DHTConnectionStatusListener() {
			@Override
			public void connecting(Network network) {
			}

			@Override
			public void connected(Network network) {
				connected.tryComplete(System.currentTimeMillis() - started);
			}

			@Override
			public void disconnected(Network network) {
			}
		});

		vertx.deployVerticle(node)
				.compose(unused -> connected.future())
				.onComplete(testContext.succeeding(elapsed -> testContext.verify(() -> {
					// The assertion: a live cached contact answers, and that alone is enough to settle the
					// status. It used to wait for the dead ones to time out first.
					assertTrue(elapsed < MAX_CONNECT_MILLIS,
							"a warm start must not wait out a dead cached contact's RPC timeout, but "
									+ "reported Connected after " + elapsed + "ms");

					// Resolving early must not abandon the sweep - it is still what purges the dead
					// contacts, and it keeps running after the status has been decided.
					assertTrue(node.getRoutingTable().getNumberOfEntries() > 1,
							"precondition: the dead contacts should still be in the table at this point");

					awaitSweep(vertx, testContext, System.currentTimeMillis() + 90_000);
				})));
	}

	/**
	 * Polls until the sweep has purged the bulk of the dead contacts, then checks the live one is still
	 * there. That is the proof that settling the status early neither cancelled the tasks behind it nor
	 * cost the table the contact that answered.
	 * <p>
	 * The bar is most of them rather than all of them on purpose. Exactly how far a single
	 * {@code PingRefreshTask} drains its queue is that task's own business and is not what this change
	 * touched; pinning it here would make the test fail for a reason it is not about.
	 * </p>
	 */
	private void awaitSweep(Vertx vertx, VertxTestContext testContext, long deadline) {
		int entries = node.getRoutingTable().getNumberOfEntries();
		if (entries <= REMAINING_AFTER_SWEEP) {
			testContext.verify(() -> assertNotNull(node.getRoutingTable().getEntry(server.getIdentity().getId()),
					"the contact that answered must survive the sweep that removed the silent ones"));
			testContext.completeNow();
			return;
		}

		if (System.currentTimeMillis() > deadline) {
			testContext.failNow("the sweep left " + entries + " entries in the table; it should have "
					+ "carried on pinging the dead cached contacts after the status was settled");
			return;
		}

		vertx.setTimer(200, unused -> awaitSweep(vertx, testContext, deadline));
	}
}
