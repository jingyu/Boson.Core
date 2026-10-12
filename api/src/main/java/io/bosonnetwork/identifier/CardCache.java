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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;

import io.bosonnetwork.Id;
import io.bosonnetwork.identifier.Registry.ResolutionResult;

/**
 * The in-memory cache of a registry's resolutions: a bounded map that drops the least recently used card
 * when full. No cache library: the registry runs on Android clients too.
 * <p>
 * An entry holds the resolution's future, so resolutions of one id made while its lookup runs share that
 * lookup. A lookup that fails leaves nothing behind; a card found answers for the cache's TTL, an answer
 * of none (not found, invalid) for at most {@link #MAX_NONE_TTL}, as a card may be published any moment.
 * An expired entry stays in the map until it is resolved again or evicted, and doesn't answer.
 */
final class CardCache {
	static final long MAX_NONE_TTL = TimeUnit.SECONDS.toMillis(30);

	private final LinkedHashMap<Id, Entry> entries;
	private final long foundTtl;
	private final long noneTtl;
	private final LongSupplier clock;

	private static final class Entry {
		final CompletableFuture<ResolutionResult<Card>> future = new CompletableFuture<>();
		// Until the lookup answers, the entry doesn't expire.
		volatile long expiresAt = Long.MAX_VALUE;
	}

	/**
	 * @param maxEntries the most entries kept
	 * @param ttl how long a card found answers, in milliseconds
	 */
	CardCache(int maxEntries, long ttl) {
		this(maxEntries, ttl, System::currentTimeMillis);
	}

	CardCache(int maxEntries, long ttl, LongSupplier clock) {
		if (maxEntries <= 0)
			throw new IllegalArgumentException("Cache size must be positive");
		if (ttl <= 0)
			throw new IllegalArgumentException("Cache TTL must be positive");

		this.foundTtl = ttl;
		this.noneTtl = Math.min(ttl, MAX_NONE_TTL);
		this.clock = clock;
		// Access order: a lookup moves its entry to the end, so the eldest is the least recently used.
		this.entries = new LinkedHashMap<>(16, 0.75f, true) {
			private static final long serialVersionUID = 4120526305786347811L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<Id, Entry> eldest) {
				return size() > maxEntries;
			}
		};
	}

	/**
	 * The resolution of {@code id}: the cached one, the one under way, or a new one by {@code lookup}.
	 *
	 * @param id the id to resolve
	 * @param lookup resolves an id without the cache
	 * @return the resolution, shared with every caller of the same entry
	 */
	CompletableFuture<ResolutionResult<Card>> get(Id id, Function<Id, CompletableFuture<ResolutionResult<Card>>> lookup) {
		Entry entry;
		synchronized (entries) {
			entry = entries.get(id);
			if (entry != null && entry.expiresAt > clock.getAsLong())
				return entry.future;

			entry = new Entry();
			entries.put(id, entry);
		}

		load(id, entry, lookup);
		return entry.future;
	}

	/**
	 * Resolves {@code id} afresh by {@code lookup}, replacing what the cache has for it.
	 *
	 * @param id the id to resolve
	 * @param lookup resolves an id without the cache
	 * @return the resolution
	 */
	CompletableFuture<ResolutionResult<Card>> refresh(Id id, Function<Id, CompletableFuture<ResolutionResult<Card>>> lookup) {
		Entry entry = new Entry();
		synchronized (entries) {
			entries.put(id, entry);
		}

		load(id, entry, lookup);
		return entry.future;
	}

	/**
	 * Forgets what the cache has for {@code id}; a resolution under way still answers its callers.
	 *
	 * @param id the id
	 */
	void invalidate(Id id) {
		synchronized (entries) {
			entries.remove(id);
		}
	}

	int size() {
		synchronized (entries) {
			return entries.size();
		}
	}

	private void load(Id id, Entry entry, Function<Id, CompletableFuture<ResolutionResult<Card>>> lookup) {
		CompletableFuture<ResolutionResult<Card>> lookingUp;
		try {
			lookingUp = lookup.apply(id);
		} catch (RuntimeException e) {
			lookingUp = CompletableFuture.failedFuture(e);
		}

		lookingUp.whenComplete((result, error) -> {
			if (error != null) {
				synchronized (entries) {
					// Only this entry: a newer one may have replaced it meanwhile.
					entries.remove(id, entry);
				}
				entry.future.completeExceptionally(error);
			} else {
				entry.expiresAt = clock.getAsLong() + (result.succeeded() ? foundTtl : noneTtl);
				entry.future.complete(result);
			}
		});
	}
}
