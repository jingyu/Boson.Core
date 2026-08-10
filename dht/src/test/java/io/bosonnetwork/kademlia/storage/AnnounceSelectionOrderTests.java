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

package io.bosonnetwork.kademlia.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;

/**
 * Covers the order the re-announce selection returns its items in.
 * <p>
 * {@code KadNode.persistentAnnounce} serves a bounded number of items per cycle, so whatever sorts
 * first is what gets served and the rest wait for the next cycle. These queries used to sort
 * {@code updated DESC} - most recently announced first - which put the least urgent item at the head.
 * That was harmless only while every item was dispatched at once; against a budget it starves the
 * items nearest expiry indefinitely, and unlike a deferred bucket refresh a deferred announce means
 * the network drops the item.
 * </p>
 * <p>
 * These are the cheap half of the regression: the ordering is what makes the bound safe, and it is a
 * property of the SQL rather than of anything that needs a live DHT to observe.
 * </p>
 */
@ExtendWith(VertxExtension.class)
class AnnounceSelectionOrderTests {
	/** How many items each case stores. More than a plausible budget, so a head-of-list bug is visible. */
	private static final int ITEMS = 5;

	private static Future<DataStorage> newStorage(Vertx vertx) throws Exception {
		Path dir = Files.createTempDirectory("boson-announce-order");
		DataStorage storage = new SQLiteStorage("jdbc:sqlite:" + dir.resolve("storage.db"));
		return storage.initialize(vertx, TimeUnit.HOURS.toMillis(1), TimeUnit.HOURS.toMillis(1))
				.map(v -> storage);
	}

	/**
	 * Runs the given steps one after another, so each item's announced time is distinct and ordered.
	 * Sequencing beats sleeping here: the announced time is written by
	 * {@code updateValueAnnouncedTime} from {@code System.currentTimeMillis()}, and two calls in the
	 * same millisecond would leave the order to the id tiebreak rather than to the timestamp.
	 */
	private static Future<Void> inOrder(Vertx vertx, List<Supplier<Future<?>>> steps) {
		Future<Void> chain = Future.succeededFuture();
		for (Supplier<Future<?>> step : steps)
			chain = chain.compose(v -> delay(vertx).compose(unused -> step.get().<Void>mapEmpty()));

		return chain;
	}

	/** Two milliseconds, enough for the clock to move between two announced-time writes. */
	private static Future<Void> delay(Vertx vertx) {
		return Future.future(promise -> vertx.setTimer(2, id -> promise.complete()));
	}

	/**
	 * Values: the one announced longest ago must come first, because it is the one closest to being
	 * dropped by its remote holders.
	 */
	@Test
	@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
	void valuesAreSelectedLeastRecentlyAnnouncedFirst(Vertx vertx, VertxTestContext context) throws Exception {
		List<Value> values = new ArrayList<>();
		for (int i = 0; i < ITEMS; i++)
			values.add(Value.immutableBuilder().data(("announce order " + i).getBytes()).build());

		newStorage(vertx).compose(storage -> {
			List<Supplier<Future<?>>> steps = new ArrayList<>();
			// Stored, then stamped, in list order - so values.get(0) is the least recently announced and
			// must lead the selection.
			for (Value value : values)
				steps.add(() -> storage.putValue(value, true)
						.compose(v -> storage.updateValueAnnouncedTime(value.getId())));

			return inOrder(vertx, steps)
					.compose(v -> storage.getValues(true, System.currentTimeMillis()));
		}).onComplete(context.succeeding(selected -> context.verify(() -> {
			assertEquals(ITEMS, selected.size(), "every persistent value must be eligible");

			List<Id> expected = values.stream().map(Value::getId).toList();
			List<Id> actual = selected.stream().map(Value::getId).toList();
			assertEquals(expected, actual,
					"the re-announce must be served least recently announced first, or a bounded cycle "
							+ "serves the least urgent items and starves the rest");

			context.completeNow();
		})));
	}

	/** The same property for peers, which share the budget and the deadline. */
	@Test
	@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
	void peersAreSelectedLeastRecentlyAnnouncedFirst(Vertx vertx, VertxTestContext context) throws Exception {
		List<PeerInfo> peers = new ArrayList<>();
		for (int i = 0; i < ITEMS; i++)
			peers.add(PeerInfo.builder().endpoint("tcp:///203.0.113.10:" + (12345 + i)).build());

		newStorage(vertx).compose(storage -> {
			List<Supplier<Future<?>>> steps = new ArrayList<>();
			for (PeerInfo peer : peers)
				steps.add(() -> storage.putPeer(peer, true)
						.compose(v -> storage.updatePeerAnnouncedTime(peer.getId(), peer.getFingerprint())));

			return inOrder(vertx, steps)
					.compose(v -> storage.getPeers(true, System.currentTimeMillis()));
		}).onComplete(context.succeeding(selected -> context.verify(() -> {
			assertEquals(ITEMS, selected.size(), "every persistent peer must be eligible");

			List<Id> expected = peers.stream().map(PeerInfo::getId).toList();
			List<Id> actual = selected.stream().map(PeerInfo::getId).toList();
			assertEquals(expected, actual, "peers must be served least recently announced first too");

			context.completeNow();
		})));
	}

	/**
	 * The property the ordering exists for, stated as the cycle sees it: an item that a cycle failed to
	 * announce keeps its place at the head.
	 * <p>
	 * This is what makes the rotation free and self-correcting, the same way {@code lastRefresh} does
	 * for bucket maintenance. {@code updateValueAnnouncedTime} runs on success only, so a served item
	 * sorts to the back while a failed one keeps its old timestamp - and under an ascending sort that
	 * leaves it exactly where something closer to expiring than everything else belongs. Under the old
	 * descending sort it went to the back instead, so failures were retried last.
	 * </p>
	 */
	@Test
	@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
	void anItemThatFailedToAnnounceStaysAtTheHead(Vertx vertx, VertxTestContext context) throws Exception {
		List<Value> values = new ArrayList<>();
		for (int i = 0; i < ITEMS; i++)
			values.add(Value.immutableBuilder().data(("retry order " + i).getBytes()).build());

		newStorage(vertx).compose(storage -> {
			List<Supplier<Future<?>>> steps = new ArrayList<>();
			for (Value value : values)
				steps.add(() -> storage.putValue(value, true)
						.compose(v -> storage.updateValueAnnouncedTime(value.getId())));

			// A cycle that served everything except the head - which is what a failed announce looks
			// like, since the announced time is only stamped on success.
			for (int i = 1; i < ITEMS; i++) {
				final Value value = values.get(i);
				steps.add(() -> storage.updateValueAnnouncedTime(value.getId()));
			}

			return inOrder(vertx, steps)
					.compose(v -> storage.getValues(true, System.currentTimeMillis()));
		}).onComplete(context.succeeding(selected -> context.verify(() -> {
			assertTrue(selected.size() > 1, "precondition: there must be an order to check");
			assertEquals(values.get(0).getId(), selected.get(0).getId(),
					"the item whose announce failed must be retried first, not last");

			context.completeNow();
		})));
	}
}
