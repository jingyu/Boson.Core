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

package io.bosonnetwork.cwt;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.bosonnetwork.Id;

/**
 * Remembers the tokens a verifier has accepted, by issuer and "CWT ID" (cti), until they could no
 * longer be accepted anyway, so that each is accepted once.
 * <p>
 * The cache is bounded. When it is full of tokens that are all still acceptable it refuses new ones
 * rather than forgetting old ones: a forgotten token could be replayed until it expires, while a refused
 * one only has to be issued again. Keep the tokens it guards short-lived and the callers rate-limited,
 * and it stays far from full.
 * <p>
 * Times are dates (epoch seconds, as in the token's claims), compared with the wall clock. Thread-safe.
 */
public final class ReplayCache {
	/** The default number of tokens remembered at once. */
	public static final int DEFAULT_CAPACITY = 65536;

	private final int capacity;
	// Insertion order: tokens are short-lived and inserted as they arrive, so the oldest entries are
	// nearly always the first to expire, and purging from the head finds them without a full scan.
	private final LinkedHashMap<Key, Long> entries;

	private record Key(Id issuer, byte[] tokenId) {
		@Override
		public boolean equals(Object o) {
			return o instanceof Key k && issuer.equals(k.issuer) && Arrays.equals(tokenId, k.tokenId);
		}

		@Override
		public int hashCode() {
			return 31 * issuer.hashCode() + Arrays.hashCode(tokenId);
		}
	}

	/**
	 * Creates a cache that remembers up to {@link #DEFAULT_CAPACITY} tokens.
	 */
	public ReplayCache() {
		this(DEFAULT_CAPACITY);
	}

	/**
	 * Creates a cache that remembers up to {@code capacity} tokens.
	 *
	 * @param capacity the maximum number of tokens remembered at once, at least 1
	 */
	public ReplayCache(int capacity) {
		if (capacity <= 0)
			throw new IllegalArgumentException("Invalid capacity: " + capacity);

		this.capacity = capacity;
		this.entries = new LinkedHashMap<>();
	}

	/**
	 * Accepts a token the first time it is presented and refuses it every time after that.
	 *
	 * @param issuer        the token's issuer
	 * @param tokenId       the token's "CWT ID" (cti) claim
	 * @param rememberUntil until when to remember it, in epoch seconds: the token's expiration plus the
	 *                      leeway the verifier allows, since until then the verifier would accept it again
	 * @throws TokenReplayException if the token has been presented before, or the cache is full of tokens
	 *                              that are all still acceptable
	 */
	public synchronized void accept(Id issuer, byte[] tokenId, long rememberUntil) throws TokenReplayException {
		Objects.requireNonNull(issuer, "issuer");
		Objects.requireNonNull(tokenId, "tokenId");

		long now = System.currentTimeMillis() / 1000;
		purgeExpired(now, false);

		Key key = new Key(issuer, tokenId.clone());
		Long until = entries.get(key);
		if (until != null && until >= now)
			throw new TokenReplayException("Token already used");

		if (rememberUntil < now)
			return; // nothing could accept it again

		if (until == null && entries.size() >= capacity) {
			purgeExpired(now, true);
			if (entries.size() >= capacity)
				throw new TokenReplayException("Too many tokens in flight, try again later");
		}

		entries.put(key, rememberUntil);
	}

	/**
	 * Returns the number of tokens remembered now, expired ones not yet purged included.
	 *
	 * @return the number of entries
	 */
	public synchronized int size() {
		return entries.size();
	}

	// Drops expired entries: from the head until the first live one, or, with all, everywhere.
	private void purgeExpired(long now, boolean all) {
		Iterator<Map.Entry<Key, Long>> it = entries.entrySet().iterator();
		while (it.hasNext()) {
			if (it.next().getValue() < now)
				it.remove();
			else if (!all)
				break;
		}
	}
}
