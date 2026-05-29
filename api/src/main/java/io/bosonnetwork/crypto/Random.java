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

package io.bosonnetwork.crypto;

import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility class providing methods for generating random numbers and random bytes.
 * <p>
 * This class offers access to both fast, non-cryptographically secure random number generation
 * (via {@link ThreadLocalRandom}) and cryptographically secure random number generation
 * (via {@link SecureRandom}). It also provides convenience methods for generating random byte arrays
 * using either approach.
 */
public class Random {
	// SecureRandom has been explicitly documented as thread-safe since Java 17
	private static final SecureRandom secureRandom = new SecureRandom();

	/**
	 * Returns the current thread's {@code ThreadLocalRandom} object.
	 * Methods of this object should be called only by the current thread,
	 * not by other threads.
	 *
	 * @return the current thread's {@code ThreadLocalRandom}
	 */
	public static ThreadLocalRandom random() {
		return ThreadLocalRandom.current();
	}

	/**
	 * Returns a {@code SecureRandom} instance.
	 *
	 * @return a thread-safe {@code SecureRandom} instance.
	 */
	public static SecureRandom secureRandom() {
			return secureRandom;
	}

	/**
	 * Generates a byte array containing random bytes using the current thread's
	 * {@link ThreadLocalRandom} instance. This method is not suitable for cryptographic purposes.
	 *
	 * @param length the number of random bytes to generate
	 * @return a byte array of the specified length filled with random bytes
	 */
	public static byte[] randomBytes(int length) {
		byte[] bytes = new byte[length];
		random().nextBytes(bytes);
		return bytes;
	}

	/**
	 * Generates a byte array containing random bytes using a cryptographically secure
	 * {@link SecureRandom} instance.
	 *
	 * @param length the number of secure random bytes to generate
	 * @return a byte array of the specified length filled with secure random bytes
	 */
	public static byte[] randomBytesSecure(int length) {
		byte[] bytes = new byte[length];
		secureRandom().nextBytes(bytes);
		return bytes;
	}
}