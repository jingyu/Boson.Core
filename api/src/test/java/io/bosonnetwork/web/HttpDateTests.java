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

package io.bosonnetwork.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Tests of the {@code Date} header format shared by the servers that send it and the clients that
 * read it.
 */
class HttpDateTests {
	// Sun, 20 Sep 2026 09:28:09 GMT
	private static final long EPOCH_SECOND = 1789896489L;
	private static final String FORMATTED = "Sun, 20 Sep 2026 09:28:09 GMT";

	@Test
	void anInstantIsFormattedAsAnImfFixdate() {
		assertEquals(FORMATTED, HttpDate.format(Instant.ofEpochSecond(EPOCH_SECOND)));
	}

	@Test
	void subSecondPrecisionIsDropped() {
		assertEquals(FORMATTED, HttpDate.format(Instant.ofEpochSecond(EPOCH_SECOND).plusMillis(999)));
	}

	@Test
	void whatIsFormattedIsReadBack() {
		Instant sent = Instant.ofEpochSecond(EPOCH_SECOND);
		assertEquals(sent, HttpDate.parse(HttpDate.format(sent)));
	}

	@Test
	void aHeaderValueIsReadWithItsSurroundingSpace() {
		assertEquals(Instant.ofEpochSecond(EPOCH_SECOND), HttpDate.parse("  " + FORMATTED + " "));
	}

	@Test
	void anAbsentOrUnreadableDateIsNoDate() {
		assertNull(HttpDate.parse(null));
		assertNull(HttpDate.parse(""));
		assertNull(HttpDate.parse("yesterday"));
		// The obsolete RFC 850 form, which a server must not send.
		assertNull(HttpDate.parse("Sunday, 20-Sep-26 09:28:09 GMT"));
	}

	@Test
	void theCurrentDateIsTheCurrentSecond() {
		Instant before = Instant.now();
		String current = HttpDate.current();
		Instant after = Instant.now();

		Instant parsed = HttpDate.parse(current);
		assertNotNull(parsed);
		// Formatting truncates, so the value can be up to a second behind the instant it was taken at.
		assertTrue(!parsed.isBefore(before.minusSeconds(1)) && !parsed.isAfter(after),
				() -> current + " is not between " + before + " and " + after);
		// A second call inside the same second answers from the cache, and is still readable.
		assertNotNull(HttpDate.parse(HttpDate.current()));
	}
}
