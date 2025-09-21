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
 * Utility class for converting between binary bytes and hexadecimal strings.
 * Provides methods for encoding binary data to hexadecimal representation and decoding
 * hexadecimal strings back to binary.
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
	 * Decodes a single byte from a hexadecimal-encoded character sequence.
	 *
	 * @param chars   the character sequence containing hexadecimal digits
	 * @param offset  the offset within the character sequence to decode from
	 * @return the decoded byte
	 * @throws NullPointerException if {@code chars} is null
	 * @throws IllegalArgumentException if the characters at the given offset are not valid hexadecimal digits
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
	 * Decodes a portion of a character sequence containing hexadecimal digits into a byte array.
	 *
	 * @param chars   the character sequence containing hexadecimal digits
	 * @param offset  the offset within the character sequence to start decoding
	 * @param length  the number of characters to decode (must be even)
	 * @return a byte array containing binary data decoded from the supplied character sequence
	 * @throws NullPointerException if {@code chars} is null
	 * @throws IllegalArgumentException if {@code length} is negative or not even, or if any character is not valid hex
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
	 * Decodes a character sequence containing hexadecimal digits into a byte array.
	 *
	 * @param chars the character sequence containing hexadecimal digits
	 * @return a byte array containing binary data decoded from the supplied character sequence
	 * @throws NullPointerException if {@code chars} is null
	 * @throws IllegalArgumentException if the length of {@code chars} is not even or contains invalid hex characters
	 */
	public static byte[] decode(CharSequence chars) {
		return decode(chars, 0, chars.length());
	}

	/**
	 * Encodes a range of a byte array into a hexadecimal string.
	 *
	 * @param bytes  the byte array to encode
	 * @param offset the offset in the byte array to start encoding from
	 * @param length the number of bytes to encode
	 * @return a string representing the hexadecimal values of the given byte array
	 * @throws NullPointerException if {@code bytes} is null
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
	 * Encodes a byte array into a hexadecimal string.
	 *
	 * @param bytes the byte array to encode
	 * @return a string representing the hexadecimal values of the given byte array
	 * @throws NullPointerException if {@code bytes} is null
	 */
	public static String encode(byte[] bytes) {
		return encode(bytes, 0, bytes.length);
	}

	/**
	 * Encodes a single byte value as a hexadecimal string.
	 *
	 * @param value the byte value to encode
	 * @return a 2-character string representing the hexadecimal value of the byte
	 */
	public static String encode(byte value) {
		char[] chars = new char[2];
		chars[0] = HEX_CHARS[(value >>> 4) & 0x0F];
		chars[1] = HEX_CHARS[value & 0x0F];
		return new String(chars);
	}

	/**
	 * Encodes a short value as a hexadecimal string.
	 *
	 * @param value the short value to encode
	 * @return a 4-character string representing the hexadecimal value of the short
	 */
	public static String encode(short value) {
		char[] chars = new char[4];
		chars[0] = HEX_CHARS[(value >>> 12) & 0x0F];
		chars[1] = HEX_CHARS[(value >>> 8) & 0x0F];
		chars[2] = HEX_CHARS[(value >>> 4) & 0x0F];
		chars[3] = HEX_CHARS[value & 0x0F];
		return new String(chars);
	}

	/**
	 * Encodes an int value as a hexadecimal string.
	 *
	 * @param value the int value to encode
	 * @return an 8-character string representing the hexadecimal value of the int
	 */
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

	/**
	 * Encodes a long value as a hexadecimal string.
	 *
	 * @param value the long value to encode
	 * @return a 16-character string representing the hexadecimal value of the long
	 */
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