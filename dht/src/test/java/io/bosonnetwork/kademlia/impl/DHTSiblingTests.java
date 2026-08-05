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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.security.Blacklist;
import io.bosonnetwork.kademlia.storage.DataStorage;

/**
 * Covers the dual-stack sibling path of {@link DHT#populateClosestNodes}.
 * <p>
 * A dual-stack node answers lookups with nodes from both address families, but each family lives
 * in a separate verticle with its own event loop, and {@code RoutingTable} is explicitly
 * single-threaded. So the sibling's table must be walked on the sibling's context and the result
 * handed back to the caller's context - never walked directly from the caller.
 * </p>
 * <p>
 * Every other DHT test is IPv4-only, so without these the sibling path never executes at all.
 * </p>
 */
@ExtendWith(VertxExtension.class)
public class DHTSiblingTests {
	// Kademlia parameters, as KadNode would pass them down from NodeConfiguration.KademliaOptions.
	private static final int K = 16;
	private static final int REPLACEMENTS = 16;
	private static final int ALPHA = 4;
	private static final int CONCURRENT_TASKS = 16;

	private static final int PORT4 = 39001;
	private static final int PORT6 = 39002;

	private Path testDir;
	private DataStorage storage;
	private DHT dht4;
	private DHT dht6;

	@BeforeEach
	void setUp(Vertx vertx, VertxTestContext testContext) throws IOException {
		testDir = Files.createTempDirectory("boson-dht-sibling-tests");

		CryptoIdentity identity = new CryptoIdentity();
		TokenManager tokenManager = new TokenManager();
		storage = DataStorage.create("jdbc:sqlite:" + testDir.resolve("storage.db"), 4, null);

		storage.initialize(vertx, TimeUnit.HOURS.toMillis(1), TimeUnit.HOURS.toMillis(1)).compose(unused -> {
			dht4 = new DHT(identity, Network.IPv4, "127.0.0.1", PORT4, List.of(),
					ALPHA, K, REPLACEMENTS, CONCURRENT_TASKS,
					storage, testDir.resolve("dht4.cache"), tokenManager,
					Blacklist.empty(), false, false, true, null);
			dht6 = new DHT(identity, Network.IPv6, "::1", PORT6, List.of(),
					ALPHA, K, REPLACEMENTS, CONCURRENT_TASKS,
					storage, testDir.resolve("dht6.cache"), tokenManager,
					Blacklist.empty(), false, false, true, null);

			// Wire before deploying, exactly as KadNode does.
			dht4.setSibling(dht6);
			dht6.setSibling(dht4);

			return vertx.deployVerticle(dht4).compose(unused2 -> vertx.deployVerticle(dht6));
		}).onComplete(testContext.succeedingThenComplete());
	}

	@AfterEach
	void tearDown(Vertx vertx, VertxTestContext testContext) {
		Future.join(
				dht4 == null ? Future.succeededFuture() : vertx.undeploy(dht4.deploymentID()).otherwiseEmpty(),
				dht6 == null ? Future.succeededFuture() : vertx.undeploy(dht6.deploymentID()).otherwiseEmpty()
		).compose(unused -> storage == null ? Future.succeededFuture() : storage.close())
				.otherwiseEmpty()
				.onComplete(unused -> {
					deleteRecursively(testDir);
					testContext.completeNow();
				});
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

	/**
	 * Fills a DHT's routing table with reachable entries, on that DHT's own context.
	 */
	private static Future<Void> fillRoutingTable(DHT dht, int count, boolean ipv6) {
		Promise<Void> promise = Promise.promise();
		dht.vertxContext().runOnContext(v -> {
			for (int i = 0; i < count; i++) {
				InetSocketAddress addr = ipv6 ?
						new InetSocketAddress("::1", 10000 + i) :
						new InetSocketAddress("127.0.0.1", 10000 + i);
				KBucketEntry entry = new KBucketEntry(Id.random(), addr);
				// Only reachable entries are eligible for the nodes list.
				entry.onResponded(20);
				dht.getRoutingTable().put(entry);
			}
			promise.complete();
		});
		return promise.future();
	}

	/**
	 * Runs an action on a DHT's context and returns its result.
	 */
	private static <T> Future<T> onContext(DHT dht, java.util.function.Supplier<Future<T>> action) {
		Promise<T> promise = Promise.promise();
		dht.vertxContext().runOnContext(v -> action.get().onComplete(promise));
		return promise.future();
	}

	/**
	 * The DHT constructor is the single point where all four Kademlia parameters arrive, so it is
	 * where they must be validated.
	 * <p>
	 * None of these values fails loudly downstream: alpha below 1 makes {@code Task.canDoRequest()}
	 * permanently false so a task never issues an RPC and never completes, and concurrentTasks below
	 * 1 makes {@code TaskManager.isReady()} permanently false so every task queues forever. Both are
	 * silent hangs, which is exactly the kind of defect a constructor check should turn into a crash.
	 */
	@Test
	void testRejectsInvalidKademliaParameters() {
		CryptoIdentity identity = new CryptoIdentity();
		TokenManager tokenManager = new TokenManager();

		// alpha, k, replacements, concurrentTasks - each rejected independently at 0.
		assertThrows(IllegalArgumentException.class, () -> newDht(identity, tokenManager, 0, K, REPLACEMENTS, CONCURRENT_TASKS));
		assertThrows(IllegalArgumentException.class, () -> newDht(identity, tokenManager, ALPHA, 0, REPLACEMENTS, CONCURRENT_TASKS));
		assertThrows(IllegalArgumentException.class, () -> newDht(identity, tokenManager, ALPHA, K, 0, CONCURRENT_TASKS));
		assertThrows(IllegalArgumentException.class, () -> newDht(identity, tokenManager, ALPHA, K, REPLACEMENTS, 0));

		assertThrows(IllegalArgumentException.class, () -> newDht(identity, tokenManager, -1, K, REPLACEMENTS, CONCURRENT_TASKS));

		// The all-valid combination must still construct, so the test cannot pass by rejecting everything.
		assertNotNull(newDht(identity, tokenManager, ALPHA, K, REPLACEMENTS, CONCURRENT_TASKS));
	}

	private DHT newDht(CryptoIdentity identity, TokenManager tokenManager,
					   int alpha, int k, int replacements, int concurrentTasks) {
		return new DHT(identity, Network.IPv4, "127.0.0.1", PORT4, List.of(),
				alpha, k, replacements, concurrentTasks,
				storage, testDir.resolve("params.cache"), tokenManager,
				Blacklist.empty(), false, false, true, null);
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testPopulateClosestNodesReturnsBothFamilies(VertxTestContext testContext) {
		Id target = Id.random();

		Future.all(fillRoutingTable(dht4, 16, false), fillRoutingTable(dht6, 16, true))
				.compose(unused -> onContext(dht4, () -> dht4.populateClosestNodes(target, 8, 8)))
				.onComplete(testContext.succeeding(closest -> testContext.verify(() -> {
					assertFalse(closest.nodes4().isEmpty(), "IPv4 nodes must come from the local table");
					assertFalse(closest.nodes6().isEmpty(), "IPv6 nodes must come from the sibling");

					// The sibling's entries are normalized, so no mutable KBucketEntry crosses loops.
					for (NodeInfo n : closest.nodes6())
						assertSame(NodeInfo.class, n.getClass(),
								"sibling nodes must be plain NodeInfo, not " + n.getClass().getSimpleName());

					testContext.completeNow();
				})));
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testPopulateClosestNodesSkipsUnwantedFamily(VertxTestContext testContext) {
		Id target = Id.random();

		Future.all(fillRoutingTable(dht4, 16, false), fillRoutingTable(dht6, 16, true))
				.compose(unused -> onContext(dht4, () -> dht4.populateClosestNodes(target, 8, 0)))
				.onComplete(testContext.succeeding(closest -> testContext.verify(() -> {
					assertFalse(closest.nodes4().isEmpty());
					assertTrue(closest.nodes6().isEmpty(), "the sibling must not be consulted when not wanted");
					testContext.completeNow();
				})));
	}

	/**
	 * The regression guard: hammer the sibling path while the sibling mutates its own routing table.
	 * <p>
	 * Walking the sibling's table directly - as the code did before - races bucket splits and merges
	 * and throws {@code ConcurrentModificationException} or {@code IndexOutOfBoundsException}.
	 * </p>
	 */
	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS)
	void testSiblingLookupsAreSafeWhileSiblingMutatesItsTable(Vertx vertx, VertxTestContext testContext) {
		final int rounds = 300;
		final AtomicBoolean mutating = new AtomicBoolean(true);
		final AtomicInteger completed = new AtomicInteger();

		// Churn dht6's routing table on dht6's own context, forcing bucket splits and merges.
		final Runnable churn = new Runnable() {
			@Override
			public void run() {
				if (!mutating.get())
					return;

				for (int i = 0; i < 64; i++) {
					KBucketEntry entry = new KBucketEntry(Id.random(), new InetSocketAddress("::1", 20000 + i));
					entry.onResponded(20);
					dht6.getRoutingTable().put(entry);
				}
				dht6.getRoutingTable().stream().findFirst()
						.flatMap(b -> b.entries().stream().findFirst())
						.ifPresent(e -> dht6.getRoutingTable().remove(e.getId()));

				dht6.vertxContext().runOnContext(v -> run());
			}
		};

		fillRoutingTable(dht6, 32, true)
				.compose(unused -> {
					dht6.vertxContext().runOnContext(v -> churn.run());
					return fillRoutingTable(dht4, 16, false);
				})
				.compose(unused -> {
					// Serialize the lookups on dht4's context, mirroring real request handling.
					Promise<Void> done = Promise.promise();
					dht4.vertxContext().runOnContext(v -> lookupLoop(rounds, completed, done));
					return done.future();
				})
				.onComplete(ar -> {
					mutating.set(false);
					testContext.verify(() -> {
						if (ar.failed())
							throw new AssertionError("sibling lookup failed under concurrent mutation", ar.cause());

						assertEquals(rounds, completed.get());
						testContext.completeNow();
					});
				});
	}

	private void lookupLoop(int remaining, AtomicInteger completed, Promise<Void> done) {
		if (remaining == 0) {
			done.complete();
			return;
		}

		final Future<DHT.ClosestNodes> lookup;
		try {
			lookup = dht4.populateClosestNodes(Id.random(), 8, 8);
		} catch (Throwable t) {
			// Walking the sibling's routing table from this context races its bucket splits and
			// merges: ConcurrentModificationException or IndexOutOfBoundsException land here.
			done.fail(t);
			return;
		}

		lookup.onComplete(ar -> {
			if (ar.failed()) {
				done.fail(ar.cause());
				return;
			}

			assertNotNull(ar.result());
			completed.incrementAndGet();
			lookupLoop(remaining - 1, completed, done);
		});
	}

	@Test
	@Timeout(value = 60, unit = TimeUnit.SECONDS)
	void testCompletionRunsOnCallersContext(VertxTestContext testContext) {
		Id target = Id.random();

		fillRoutingTable(dht6, 16, true).onSuccess(unused ->
				dht4.vertxContext().runOnContext(v -> {
					Context callerContext = Vertx.currentContext();
					dht4.populateClosestNodes(target, 8, 8).onComplete(ar -> testContext.verify(() -> {
						assertTrue(ar.succeeded());
						assertSame(callerContext, Vertx.currentContext(),
								"the continuation must resume on the calling DHT's context");
						testContext.completeNow();
					}));
				}));
	}
}