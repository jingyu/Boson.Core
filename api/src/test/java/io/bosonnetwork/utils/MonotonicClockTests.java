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

package io.bosonnetwork.utils;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class MonotonicClockTests {
	// 2020-01-01T00:00:00Z, the earliest the clock may read.
	private static final long FLOOR = 1_577_836_800_000L;

	@Test
	void neverEarlierThanTheFloorSoZeroReadsAsLongAgo() {
		assertTrue(MonotonicClock.elapsedMillis() >= FLOOR);
		assertTrue(MonotonicClock.elapsedNanos() / 1_000_000 >= FLOOR);
	}

	@Test
	void neverGoesBackwards() {
		long millis = MonotonicClock.elapsedMillis();
		long nanos = MonotonicClock.elapsedNanos();
		for (int i = 0; i < 100_000; i++) {
			long m = MonotonicClock.elapsedMillis();
			long n = MonotonicClock.elapsedNanos();
			assertTrue(m >= millis, "millis went backwards");
			assertTrue(n >= nanos, "nanos went backwards");
			millis = m;
			nanos = n;
		}
	}

	@Test
	void theTwoReadingsShareTheirOrigin() {
		long before = MonotonicClock.elapsedMillis();
		long nanos = MonotonicClock.elapsedNanos();
		long after = MonotonicClock.elapsedMillis();
		long millis = nanos / 1_000_000;
		assertTrue(before <= millis && millis <= after, before + " <= " + millis + " <= " + after);
	}

	@Test
	void measuresElapsedTime() throws InterruptedException {
		long start = MonotonicClock.elapsedMillis();
		Thread.sleep(50);
		long elapsed = MonotonicClock.elapsedMillis() - start;
		assertTrue(elapsed >= 50 && elapsed < 5_000, "elapsed " + elapsed + " ms");
	}
}
