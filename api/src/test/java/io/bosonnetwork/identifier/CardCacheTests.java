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

package io.bosonnetwork.identifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.identifier.Registry.ResolutionResult;

public class CardCacheTests {
	private static final long TTL = 60_000;
	private final AtomicLong now = new AtomicLong(1_000_000);
	private final AtomicInteger lookups = new AtomicInteger();
	private final ResolutionResult<Card> found = new ResolutionResult<>(new CardBuilder(new CryptoIdentity()).build(), null);

	private Function<Id, CompletableFuture<ResolutionResult<Card>>> answering(ResolutionResult<Card> result) {
		return id -> {
			lookups.incrementAndGet();
			return CompletableFuture.completedFuture(result);
		};
	}

	@Test
	void aFoundCardStaysUntilItsTtl() {
		CardCache cache = new CardCache(4, TTL, now::get);
		Id id = Id.random();
		cache.get(id, answering(found));

		now.addAndGet(TTL - 1);
		cache.get(id, answering(found));
		assertEquals(1, lookups.get());

		now.addAndGet(1);
		cache.get(id, answering(found));
		assertEquals(2, lookups.get());
	}

	@Test
	void noCardStaysShorter() {
		CardCache cache = new CardCache(4, TTL, now::get);
		Id id = Id.random();
		cache.get(id, answering(ResolutionResult.notFound()));

		now.addAndGet(CardCache.MAX_NONE_TTL - 1);
		cache.get(id, answering(ResolutionResult.notFound()));
		assertEquals(1, lookups.get());

		now.addAndGet(1);
		cache.get(id, answering(found));
		assertEquals(2, lookups.get());
	}

	@Test
	void theLeastRecentlyUsedCardGoesFirst() {
		CardCache cache = new CardCache(2, TTL, now::get);
		Id a = Id.random(), b = Id.random(), c = Id.random();
		cache.get(a, answering(found));
		cache.get(b, answering(found));
		cache.get(a, answering(found));		// a is now more recent than b
		cache.get(c, answering(found));		// b goes
		assertEquals(2, cache.size());
		assertEquals(3, lookups.get());

		cache.get(a, answering(found));
		assertEquals(3, lookups.get());
		cache.get(b, answering(found));
		assertEquals(4, lookups.get());
	}

	@Test
	void aRefreshReplacesTheEntry() {
		CardCache cache = new CardCache(4, TTL, now::get);
		Id id = Id.random();
		CompletableFuture<ResolutionResult<Card>> cached = cache.get(id, answering(found));
		CompletableFuture<ResolutionResult<Card>> refreshed = cache.refresh(id, answering(found));
		assertNotSame(cached, refreshed);
		assertSame(refreshed, cache.get(id, answering(found)));
		assertEquals(2, lookups.get());
	}

	@Test
	void aLookupThatThrowsFailsTheResolution() {
		CardCache cache = new CardCache(4, TTL, now::get);
		CompletableFuture<ResolutionResult<Card>> f = cache.get(Id.random(), id -> {
			throw new IllegalStateException("boom");
		});
		assertThrows(Exception.class, f::join);
		assertEquals(0, cache.size());
	}

	@Test
	void noCardNeverOutlivesAShorterTtl() {
		CardCache cache = new CardCache(4, 1_000, now::get);
		Id id = Id.random();
		cache.get(id, answering(ResolutionResult.notFound()));

		now.addAndGet(1_000);
		cache.get(id, answering(ResolutionResult.notFound()));
		assertEquals(2, lookups.get());
	}

	@Test
	void aCacheMustHoldSomethingForSomeTime() {
		assertThrows(IllegalArgumentException.class, () -> new CardCache(0, TTL));
		assertThrows(IllegalArgumentException.class, () -> new CardCache(4, 0));
	}
}
