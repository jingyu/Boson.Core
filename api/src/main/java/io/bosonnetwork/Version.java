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

import java.util.Map;

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

	/**
	 * Build a version from the software name and version number.
	 *
	 * @param name the Boson node software name
	 * @param version the Boson node software version
	 * @return an integer that represent the version information
	 */
	public static int build(String name, int version) {
		byte[] nameBytes = name.getBytes();

		return Byte.toUnsignedInt(nameBytes[0]) << 24 |
				Byte.toUnsignedInt(nameBytes[1]) << 16 |
				(version & 0x0000FF00) |
				(version & 0x000000FF);
	}

	/**
	 * Convert the integer version information to readable string.
	 *
	 * @param version the integer version information.
	 * @return a readable string version.
	 */
	public static String toString(int version) {
		if (version == 0)
			return VERSION_NOT_AVAILABLE;

		String n = new String(new byte[] { (byte)(version >>> 24),
				(byte)((version & 0x00ff0000) >>> 16) });
		String v = Integer.toString((version & 0x0000ff00) |
				(version & 0x000000ff));

		return names.getOrDefault(n, n) + "/" + v;
	}
}