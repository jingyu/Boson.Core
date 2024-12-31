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

import java.util.Objects;

/**
 * Converts between binary bytes and hexadecimal Strings.
 */
public class Hex {
	private static final byte[] EMPTY_BYTES = {};
	private static final char[] HEX_CHARS = {
			'0', '1', '2', '3', '4', '5', '6', '7',
			'8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
	};

	private static int decodeNibble(final char c) {
		// Character.digit() is not used here, as it addresses a larger
		// set of characters (both ASCII and full-width latin letters).
		if (c >= '0' && c <= '9') {
			return c - '0';
		}
		if (c >= 'A' && c <= 'F') {
			return c - ('A' - 0xA);
		}
		if (c >= 'a' && c <= 'f') {
			return c - ('a' - 0xA);
		}

		return -1;
	}

	/**
	 * Converts one byte from a String representing hexadecimal values.
	 *
	 * @param chars the {@code CharSequence} of the string.
	 * @param offset the offset within the char sequence to decode.
	 * @return the decoded byte.
	 */
	public static byte decodeByte(CharSequence chars, int offset) {
		Objects.requireNonNull(chars, "Input chars cannot be null");
		int hi = decodeNibble(chars.charAt(offset));
		int lo = decodeNibble(chars.charAt(offset + 1));
		if (hi == -1 || lo == -1) {
			throw new IllegalArgumentException(String.format(
					"Invalid hex byte '%s' at index %d of '%s'",
					chars.subSequence(offset, offset + 2), offset, chars));
		}

		return (byte) ((hi << 4) + lo);
	}

	/**
	 * Converts a String representing hexadecimal values into an array of bytes of
	 * those same values.
	 *
	 * @param chars the {@code CharSequence} of the string.
	 * @param offset the offset within the char sequence to decode.
	 * @param length the number of chars to decode.
	 * @return a byte array containing binary data decoded from the supplied char array.
	 */
	public static byte[] decode(CharSequence chars, int offset, int length) {
		Objects.requireNonNull(chars, "Input chars cannot be null");

		if (length < 0 || (length & 1) != 0)
			throw new IllegalArgumentException("Invalid length: " + length);

		if (length == 0)
			return EMPTY_BYTES;

		byte[] bytes = new byte[length >>> 1];
		for (int i = 0; i < length; i += 2)
			bytes[i >>> 1] = decodeByte(chars, offset + i);

		return bytes;
	}

	/**
	 * Converts a String representing hexadecimal values into an array of bytes of
	 * those same values.
	 *
	 * @param chars the {@code CharSequence} of the string.
	 * @return a byte array containing binary data decoded from the supplied char array.
	 */
	public static byte[] decode(CharSequence chars) {
		return decode(chars, 0, chars.length());
	}

	/**
	 * Converts an array of bytes into an array of characters representing
	 * the hexadecimal values of each byte in order.
	 *
	 * @param bytes the byte array to convert to hex characters.
	 * @param offset the offset in byte array to start encoding from.
	 * @param length the number of bytes to encode.
	 * @return a String represent the hexadecimal values of the given byte array.
	 */
	public static String encode(byte[] bytes, int offset, int length) {
		Objects.requireNonNull(bytes, "Input bytes cannot be null");

		char[] chars = new char[length * 2];

		for (int i = 0; i < length; i++) {
			int v = bytes[offset + i] & 0xFF;
			//int v = bytes[offset + i];
			chars[i << 1] = HEX_CHARS[(v >>> 4) & 0x0F];
			chars[(i << 1) + 1] = HEX_CHARS[v & 0x0F];
		}

		return new String(chars);
	}

	/**
	 * Converts an array of bytes into an array of characters representing
	 * the hexadecimal values of each byte in order.
	 *
	 * @param bytes the byte array to convert to hex characters.
	 * @return a String represent the hexadecimal values of the given byte array.
	 */
	public static String encode(byte[] bytes) {
		return encode(bytes, 0, bytes.length);
	}

	public static String encode(byte value) {
		char[] chars = new char[2];
		chars[0] = HEX_CHARS[(value >>> 4) & 0x0F];
		chars[1] = HEX_CHARS[value & 0x0F];
		return new String(chars);
	}

	public static String encode(short value) {
		char[] chars = new char[4];
		chars[0] = HEX_CHARS[(value >>> 12) & 0x0F];
		chars[1] = HEX_CHARS[(value >>> 8) & 0x0F];
		chars[2] = HEX_CHARS[(value >>> 4) & 0x0F];
		chars[3] = HEX_CHARS[value & 0x0F];
		return new String(chars);
	}

	public static String encode(int value) {
		char[] chars = new char[8];
		chars[0] = HEX_CHARS[(value >>> 28) & 0x0F];
		chars[1] = HEX_CHARS[(value >>> 24) & 0x0F];
		chars[2] = HEX_CHARS[(value >>> 20) & 0x0F];
		chars[3] = HEX_CHARS[(value >>> 16) & 0x0F];
		chars[4] = HEX_CHARS[(value >>> 12) & 0x0F];
		chars[5] = HEX_CHARS[(value >>> 8) & 0x0F];
		chars[6] = HEX_CHARS[(value >>> 4) & 0x0F];
		chars[7] = HEX_CHARS[value & 0x0F];
		return new String(chars);
	}

	public static String encode(long value) {
		char[] chars = new char[16];

		int v = (int)(value >>> 32);
		chars[0] = HEX_CHARS[(v >>> 28) & 0x0F];
		chars[1] = HEX_CHARS[(v >>> 24) & 0x0F];
		chars[2] = HEX_CHARS[(v >>> 20) & 0x0F];
		chars[3] = HEX_CHARS[(v >>> 16) & 0x0F];
		chars[4] = HEX_CHARS[(v >>> 12) & 0x0F];
		chars[5] = HEX_CHARS[(v >>> 8) & 0x0F];
		chars[6] = HEX_CHARS[(v >>> 4) & 0x0F];
		chars[7] = HEX_CHARS[v & 0x0F];

		v = (int)(value & 0xFFFFFFFF);
		chars[8] = HEX_CHARS[(v >>> 28) & 0x0F];
		chars[9] = HEX_CHARS[(v >>> 24) & 0x0F];
		chars[10] = HEX_CHARS[(v >>> 20) & 0x0F];
		chars[11] = HEX_CHARS[(v >>> 16) & 0x0F];
		chars[12] = HEX_CHARS[(v >>> 12) & 0x0F];
		chars[13] = HEX_CHARS[(v >>> 8) & 0x0F];
		chars[14] = HEX_CHARS[(v >>> 4) & 0x0F];
		chars[15] = HEX_CHARS[v & 0x0F];

		return new String(chars);
	}
}
