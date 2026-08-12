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

package io.bosonnetwork;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/**
 *  A representation of a version information for a Boson node. A version information
 *  consists of short software name and a version number.
 */
public final class Version {
	private static final String VERSION_NOT_AVAILABLE = "N/A";

	private static final Map<String, String> names = Map.of(
	    "OR", "Orca",			// Java super node
	    "MK", "Meerkat"			// Native regular node
	);

	private Version() {
	}

	/**
	 * Build a version from the software name and version number.
	 *
	 * @param name the Boson node software name
	 * @param version the Boson node software version
	 * @return an integer that represent the version information
	 */
	public static int build(String name, int version) {
		Objects.requireNonNull(name, "name");
		byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
		if (nameBytes.length < 2)
			throw new IllegalArgumentException("Invalid name: must be at least 2 characters");

		return Byte.toUnsignedInt(nameBytes[0]) << 24 |
				Byte.toUnsignedInt(nameBytes[1]) << 16 |
				(version & 0x0000FFFF);
	}

	/**
	 * Convert the integer version information to readable string.
	 * <p>
	 * The result is safe to log. On a received message the version is whatever the sender put on the
	 * wire, and its two name bytes become characters here - so this is where they stop being bytes and
	 * have to start being text.
	 * </p>
	 *
	 * @param version the integer version information.
	 * @return a readable string version, printable ASCII throughout and at most eight characters long.
	 */
	public static String toString(int version) {
		if (version == 0)
			return VERSION_NOT_AVAILABLE;

		String n = printable(new String(new byte[] { (byte)(version >>> 24),
				(byte)((version & 0x00ff0000) >>> 16) }, StandardCharsets.US_ASCII));
		String v = Integer.toString(version & 0x0000ffff);

		return names.getOrDefault(n, n) + "/" + v;
	}

	/**
	 * Replaces everything that is not printable ASCII with a dot.
	 * <p>
	 * Applied to the name before the lookup, not after, so that a known name still matches - the two
	 * registered names are printable, and anything that needed replacing was never going to match one.
	 * </p>
	 * <p>
	 * Two bytes is too few to flood a log with, which is why the length is left alone, and too few to
	 * fake a record with. It is not too few to break one: a name of 0x0a puts a real line break in the
	 * middle of every log line that carries this version, splitting one record into two, and 0x1b begins
	 * a terminal escape sequence that a reader paging the file will execute rather than see. Both are
	 * cheap for a peer to send and cost nothing to prevent here.
	 * </p>
	 *
	 * @param name the decoded name, which may contain anything the sender chose.
	 * @return the name with every non-printable character replaced.
	 */
	private static String printable(String name) {
		char[] chars = name.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			// US-ASCII decoding already folds bytes above 0x7f to the replacement character, which this
			// then replaces in turn - the point is a known character set on the way out, not a faithful
			// rendering of what arrived.
			if (chars[i] < 0x20 || chars[i] > 0x7e)
				chars[i] = '.';
		}

		return new String(chars);
	}
}