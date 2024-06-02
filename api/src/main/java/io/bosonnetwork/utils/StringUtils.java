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
