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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ConfigMap#getSize}, the parser behind every byte-valued setting in the service
 * configuration files.
 * <p>
 * These exist because the configuration templates advertised a {@code T} suffix that the parser did
 * not accept, so a node configured with {@code 1T} failed at startup with no hint that the
 * documentation was the thing at fault. The suffix set is now part of the contract rather than an
 * implementation detail, and this pins it.
 * </p>
 */
class ConfigMapSizeTests {
	private static final long K = 1024L;
	private static final long M = K * 1024;
	private static final long G = M * 1024;
	private static final long T = G * 1024;

	private static ConfigMap of(Object value) {
		return new ConfigMap(Map.of("size", value));
	}

	@ParameterizedTest(name = "\"{0}\" is {1} bytes")
	@CsvSource({
			"512b, 512",
			"1k, 1024",
			"64m, 67108864",
			"1g, 1073741824",
			"1t, 1099511627776",
	})
	void testEverySupportedSuffix(String value, long expected) {
		assertEquals(expected, of(value).getSize("size"));
	}

	/**
	 * The suffix is matched case-insensitively, and the templates write it upper case while the error
	 * message and this test's lower-case cases spell it lower - both have to keep working.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"64M", "64m"})
	void testSuffixIsCaseInsensitive(String value) {
		assertEquals(64 * M, of(value).getSize("size"));
	}

	@Test
	void testTerabyteDoesNotOverflowTheWeight() {
		// 1024^4 does not fit in an int, so the multiplier this is built from has to be a long. A
		// regression here would not throw, it would silently return a small or negative number.
		assertEquals(T, of("1t").getSize("size"));
		assertEquals(4 * T, of("4T").getSize("size"));
	}

	@Test
	void testPlainNumbersNeedNoSuffix() {
		assertEquals(1024L, of("1024").getSize("size"));
		assertEquals(1024L, of(1024).getSize("size"));
		assertEquals(1024L, of(1024L).getSize("size"));
	}

	@Test
	void testUnknownSuffixIsRejectedAndSaysWhatIsAccepted() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> of("1p").getSize("size"));
		// The message is the only guidance an operator gets, so it has to list the real set.
		assertEquals("Invalid size value - size: 1p, units: b, k, m, g, t", e.getMessage());
	}

	@Test
	void testOverflowIsReportedRatherThanWrappingAround() {
		assertThrows(IllegalArgumentException.class, () -> of("9223372036854775807t").getSize("size"));
	}

	@Test
	void testNegativeSizesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> of("-1m").getSize("size"));
		assertThrows(IllegalArgumentException.class, () -> of(-1).getSize("size"));
	}

	@Test
	void testMissingKeyFallsBackToTheDefault() {
		assertEquals(7L, new ConfigMap(Map.of()).getSize("size", 7L));
		assertEquals(64 * M, of("64m").getSize("size", 7L));
	}
}
