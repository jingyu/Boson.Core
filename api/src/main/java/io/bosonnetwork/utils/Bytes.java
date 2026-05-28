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

/**
 * Utility class for efficient conversion between primitive types and byte arrays.
 */
public final class Bytes {
	private Bytes() {}

	/**
	 * Converts an integer value to a byte array.
	 *
	 * @param value the integer value to convert
	 * @return a byte array of length 4 representing the integer
	 */
	public static byte[] fromInteger(int value) {
		byte[] b = new byte[4];
		b[0] = (byte) (value >> 24);
		b[1] = (byte) (value >> 16);
		b[2] = (byte) (value >> 8);
		b[3] = (byte) value;
		return b;
	}

	/**
	 * Converts a byte array to an integer value.
	 *
	 * @param bytes the byte array to convert
	 * @return the integer value
	 */
	public static int toInteger(byte[] bytes) {
		return toInteger(bytes, 0);
	}

	/**
	 * Converts a byte array to an integer value starting from the specified offset.
	 *
	 * @param bytes the byte array to convert
	 * @param offset the offset in the byte array to start from
	 * @return the integer value
	 */
	public static int toInteger(byte[] bytes, int offset) {
		return ((bytes[offset] & 0xFF) << 24) |
				((bytes[offset + 1] & 0xFF) << 16) |
				((bytes[offset + 2] & 0xFF) << 8) |
				(bytes[offset + 3] & 0xFF);
	}

	/**
	 * Converts a short value to a byte array.
	 *
	 * @param value the short value to convert
	 * @return a byte array of length 2 representing the short
	 */
	public static byte[] fromShort(short value) {
		byte[] b = new byte[2];
		b[0] = (byte) (value >> 8);
		b[1] = (byte) value;
		return b;
	}

	/**
	 * Converts a byte array to a short value.
	 *
	 * @param bytes the byte array to convert
	 * @return the short value
	 */
	public static short toShort(byte[] bytes) {
		return toShort(bytes, 0);
	}

	/**
	 * Converts a byte array to a short value starting from the specified offset.
	 *
	 * @param bytes the byte array to convert
	 * @param offset the offset in the byte array to start from
	 * @return the short value
	 */
	public static short toShort(byte[] bytes, int offset) {
		return (short) (((bytes[offset] & 0xFF) << 8) |
				(bytes[offset + 1] & 0xFF));
	}

	/**
	 * Converts a long value to a byte array.
	 *
	 * @param value the long value to convert
	 * @return a byte array of length 8 representing the long
	 */
	public static byte[] fromLong(long value) {
		byte[] b = new byte[8];
		b[0] = (byte) (value >> 56);
		b[1] = (byte) (value >> 48);
		b[2] = (byte) (value >> 40);
		b[3] = (byte) (value >> 32);
		b[4] = (byte) (value >> 24);
		b[5] = (byte) (value >> 16);
		b[6] = (byte) (value >> 8);
		b[7] = (byte) value;
		return b;
	}

	/**
	 * Converts a byte array to a long value.
	 *
	 * @param bytes the byte array to convert
	 * @return the long value
	 */
	public static long toLong(byte[] bytes) {
		return toLong(bytes, 0);
	}

	/**
	 * Converts a byte array to a long value starting from the specified offset.
	 *
	 * @param bytes the byte array to convert
	 * @param offset the offset in the byte array to start from
	 * @return the long value
	 */
	public static long toLong(byte[] bytes, int offset) {
		return (((long) bytes[offset] & 0xFF) << 56) |
				(((long) bytes[offset + 1] & 0xFF) << 48) |
				(((long) bytes[offset + 2] & 0xFF) << 40) |
				(((long) bytes[offset + 3] & 0xFF) << 32) |
				(((long) bytes[offset + 4] & 0xFF) << 24) |
				(((long) bytes[offset + 5] & 0xFF) << 16) |
				(((long) bytes[offset + 6] & 0xFF) << 8) |
				((long) bytes[offset + 7] & 0xFF);
	}
}