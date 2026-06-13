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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.bosonnetwork.Value;
import io.bosonnetwork.kademlia.exceptions.NotOwnerException;
import io.bosonnetwork.kademlia.exceptions.SequenceNotExpectedException;

/**
 * Regression tests for the upsert keep-alive semantics: re-announcing an existing value/peer
 * (same sequence number) must refresh its {@code updated} timestamp so it survives {@code purge()}
 * (Kademlia republish, paper 2.5), and a non-persistent re-store must not downgrade a persistent record.
 */
@ExtendWith(VertxExtension.class)
class StorageRepublishRefreshTests {
	private static Future<DataStorage> newStorage(Vertx vertx) throws Exception {
		Path dir = Files.createTempDirectory("boson-storage-refresh");
		DataStorage storage = new SQLiteStorage("jdbc:sqlite:" + dir.resolve("storage.db"));
		return storage.initialize(vertx, TimeUnit.MINUTES.toMillis(1), TimeUnit.MINUTES.toMillis(1))
				.map(v -> storage);
	}

	/** Completes with the current time after {@code ms} milliseconds, without blocking the event loop. */
	private static Future<Long> delay(Vertx vertx, long ms) {
		Promise<Long> p = Promise.promise();
		vertx.setTimer(ms, id -> p.complete(System.currentTimeMillis()));
		return p.future();
	}

	@Test
	@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
	void reStoringSameValueRefreshesAnnouncedTime(Vertx vertx, VertxTestContext context) throws Exception {
		Value value = Value.immutableBuilder().data("hello republish".getBytes()).build();

		newStorage(vertx).compose(storage ->
				storage.putValue(value)                       // initial store: updated = T0
						.compose(v -> delay(vertx, 150))      // marker captured after T0
						.compose(marker -> delay(vertx, 50).map(ignored -> marker))
						.compose(marker -> storage.putValue(value)            // re-store, same seq: must refresh updated > marker
								.compose(v -> storage.getValues(false, marker))  // non-persistent values with updated <= marker
								.map(stale -> new Object[]{marker, stale}))
						.compose(arr -> storage.getValue(value.getId()).map(reloaded -> new Object[]{arr[1], reloaded}))
		).onComplete(context.succeeding(arr -> {
			context.verify(() -> {
				@SuppressWarnings("unchecked")
				var stale = (java.util.List<Value>) arr[0];
				Value reloaded = (Value) arr[1];
				org.junit.jupiter.api.Assertions.assertFalse(stale.contains(value),
						"Re-stored value must have a refreshed announced time (not appear as stale before the marker)");
				org.junit.jupiter.api.Assertions.assertEquals(value, reloaded, "Re-store must not corrupt the value content");
			});
			context.completeNow();
		}));
	}

	@Test
	@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
	void atomicPutValueRejectsStaleCas(Vertx vertx, VertxTestContext context) throws Exception {
		Value v = Value.signedBuilder().sequenceNumber(5).data("v1".getBytes()).build();
		Value newer = v.update().data("v2".getBytes()).build(); // sequence 6

		newStorage(vertx)
				.compose(storage -> storage.putValue(v, true).map(x -> storage))
				// CAS: stored seq (5) exceeds expected (4) -> reject.
				.compose(storage -> storage.putValue(newer, 4, false, true))
				.onComplete(context.failing(err -> {
					context.verify(() -> assertInstanceOf(SequenceNotExpectedException.class, err));
					context.completeNow();
				}));
	}

	@Test
	@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
	void atomicPutValueOwnerSkipKeepsExisting(Vertx vertx, VertxTestContext context) throws Exception {
		Value owned = Value.signedBuilder().data("owned".getBytes()).build();
		Value notOwned = owned.withoutPrivateKey();

		newStorage(vertx)
				.compose(storage -> storage.putValue(owned, true).map(x -> storage))
				// failIfNotOwner=false: a non-owner re-store must keep our owned copy (private key retained).
				.compose(storage -> storage.putValue(notOwned, -1, false, false).map(x -> storage))
				.compose(storage -> storage.getValue(owned.getId()))
				.onComplete(context.succeeding(reloaded -> {
					context.verify(() -> assertTrue(reloaded.hasPrivateKey(),
							"owned value must be preserved on a non-owner re-store"));
					context.completeNow();
				}));
	}

	@Test
	@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
	void atomicPutValueOwnerFailWhenRequested(Vertx vertx, VertxTestContext context) throws Exception {
		Value owned = Value.signedBuilder().data("owned".getBytes()).build();
		Value notOwned = owned.withoutPrivateKey();

		newStorage(vertx)
				.compose(storage -> storage.putValue(owned, true).map(x -> storage))
				// failIfNotOwner=true: reject a non-owner overwrite of our owned value.
				.compose(storage -> storage.putValue(notOwned, -1, false, true))
				.onComplete(context.failing(err -> {
					context.verify(() -> assertInstanceOf(NotOwnerException.class, err));
					context.completeNow();
				}));
	}

	@Test
	@Timeout(value = 30, timeUnit = TimeUnit.SECONDS)
	void nonPersistentReStoreDoesNotDowngradePersistentValue(Vertx vertx, VertxTestContext context) throws Exception {
		Value value = Value.immutableBuilder().data("persist me".getBytes()).build();
		long horizon = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(1);

		newStorage(vertx).compose(storage ->
				storage.putValue(value, true)                  // store as persistent
						.compose(v -> storage.putValue(value, false))  // inbound (non-persistent) re-store
						.compose(v -> storage.getValues(true, horizon))    // persistent values
						.compose(persistent -> storage.getValues(false, horizon)  // non-persistent values
								.map(nonPersistent -> new Object[]{persistent, nonPersistent}))
		).onComplete(context.succeeding(arr -> {
			context.verify(() -> {
				@SuppressWarnings("unchecked")
				var persistent = (java.util.List<Value>) arr[0];
				@SuppressWarnings("unchecked")
				var nonPersistent = (java.util.List<Value>) arr[1];
				org.junit.jupiter.api.Assertions.assertTrue(persistent.contains(value),
						"Value must remain persistent after a non-persistent re-store");
				org.junit.jupiter.api.Assertions.assertFalse(nonPersistent.contains(value),
						"Value must not be downgraded to non-persistent");
			});
			context.completeNow();
		}));
	}
}
