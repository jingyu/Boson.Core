/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

import java.security.SecureRandom;
import java.util.Random;

/**
 * Common String related utility functions
 */
public class StringUtils {
	private static final Random rnd = new SecureRandom();

	/**
	 * Creates a random string whose length is the number of characters specified.
	 *
	 * <p>
	 * Characters will be chosen from the set of characters whose ASCII value is
	 * 0-9 | A-Z | a-z.
	 * </p>
	 *
	 * @param length the length of random string to create
	 * @return the random string
	 * @throws IllegalArgumentException if {@code count} &lt; 0.
	 */
	public static String random(int length) {
		if (length < 0)
			throw new IllegalArgumentException("Invalid length");

		return rnd.ints(48, 123)
			      .filter(c -> (c <= 57 || c >= 65) && (c <= 90 || c >= 97))
			      .limit(length)
			      .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			      .toString();
	}
}