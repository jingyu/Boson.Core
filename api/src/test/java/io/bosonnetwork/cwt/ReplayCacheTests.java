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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.Random;

class ReplayCacheTests {
	private static long now() {
		return System.currentTimeMillis() / 1000;
	}

	@Test
	void aTokenIsAcceptedOnce() {
		ReplayCache cache = new ReplayCache();
		Id issuer = Id.random();
		byte[] jti = Random.randomBytes(16);

		assertDoesNotThrow(() -> cache.accept(issuer, jti, now() + 60));
		TokenReplayException e = assertThrows(TokenReplayException.class, () -> cache.accept(issuer, jti, now() + 60));
		assertEquals("Token already used", e.getMessage());
		// The same bytes, not the same array.
		assertThrows(TokenReplayException.class, () -> cache.accept(issuer, jti.clone(), now() + 60));
	}

	@Test
	void theSameIdFromAnotherIssuerIsAnotherToken() {
		ReplayCache cache = new ReplayCache();
		byte[] jti = Random.randomBytes(16);

		assertDoesNotThrow(() -> cache.accept(Id.random(), jti, now() + 60));
		assertDoesNotThrow(() -> cache.accept(Id.random(), jti, now() + 60));
	}

	@Test
	void aTokenThatCanNoLongerBeAcceptedIsNotRemembered() {
		ReplayCache cache = new ReplayCache();
		Id issuer = Id.random();
		byte[] jti = Random.randomBytes(16);

		assertDoesNotThrow(() -> cache.accept(issuer, jti, now() - 1));
		assertEquals(0, cache.size());
		assertDoesNotThrow(() -> cache.accept(issuer, jti, now() - 1));
	}

	@Test
	void expiredEntriesAreForgottenAndTheirTokensAcceptedAgain() throws Exception {
		ReplayCache cache = new ReplayCache();
		Id issuer = Id.random();
		byte[] jti = Random.randomBytes(16);

		// Remembered until the current second only: by the next one it has expired.
		cache.accept(issuer, jti, now());
		Thread.sleep(1100);
		assertDoesNotThrow(() -> cache.accept(issuer, jti, now() + 60));
		assertEquals(1, cache.size());
	}

	@Test
	void aFullCacheRefusesNewTokensRatherThanForgettingLiveOnes() throws Exception {
		ReplayCache cache = new ReplayCache(2);
		Id issuer = Id.random();
		byte[] first = Random.randomBytes(16);
		cache.accept(issuer, first, now() + 60);
		cache.accept(issuer, Random.randomBytes(16), now() + 60);

		TokenReplayException e = assertThrows(TokenReplayException.class,
				() -> cache.accept(issuer, Random.randomBytes(16), now() + 60));
		assertEquals("Too many tokens in flight, try again later", e.getMessage());
		// And the first is still remembered.
		assertThrows(TokenReplayException.class, () -> cache.accept(issuer, first, now() + 60));
	}

	@Test
	void aFullCacheMakesRoomByPurgingExpiredEntriesAnywhere() throws Exception {
		ReplayCache cache = new ReplayCache(2);
		Id issuer = Id.random();
		// The live entry is first, so purging from the head alone would stop at it.
		cache.accept(issuer, Random.randomBytes(16), now() + 60);
		cache.accept(issuer, Random.randomBytes(16), now());
		Thread.sleep(1100);

		assertDoesNotThrow(() -> cache.accept(issuer, Random.randomBytes(16), now() + 60));
		assertEquals(2, cache.size());
	}

	@Test
	void capacityMustBePositive() {
		assertThrows(IllegalArgumentException.class, () -> new ReplayCache(0));
	}
}
