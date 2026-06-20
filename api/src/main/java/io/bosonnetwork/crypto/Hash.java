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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class providing methods for generating SHA-256 and MD5 hashes.
 * Supports hashing single or multiple byte arrays, as well as performing double SHA-256 hashing.
 * <p>
 * <strong>Security note:</strong> MD5 is cryptographically broken (not collision-resistant) and
 * is provided only for non-cryptographic uses such as legacy checksums and interop. Never use the
 * {@code md5*} methods for security-sensitive purposes; use the SHA-256 methods instead.
 */
public class Hash {
	private Hash() {
	}

	/**
	 * Create a new SHA256 message digest object.
	 *
	 * @return the SHA256 {@code MessageDigest}
	 */
	public static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);  // never happen
		}
	}

	/**
	 * Performs a final update on the digest using the specified array
	 * of bytes, then completes the digest computation.
	 *
	 * @param input  the array of bytes.
	 * @param offset the offset to start from in the array of bytes.
	 * @param len    the number of bytes to use, starting at {@code offset}.
	 * @return the array of bytes for the resulting hash value.
	 */
	public static byte[] sha256(byte[] input, int offset, int len) {
		MessageDigest md = sha256();
		md.update(input, offset, len);
		return md.digest();
	}

	/**
	 * Performs a final update on the digest using the specified array of bytes,
	 * then completes the digest computation.
	 *
	 * @param input the input to be updated before the digest is completed.
	 *
	 * @return the array of bytes for the resulting hash value.
	 */
	public static byte[] sha256(byte[] input) {
		return sha256().digest(input);
	}

	/**
	 * Performs a final update on the digest using the specified arrays of bytes,
	 * then completes the digest computation.
	 * Null or empty arrays are skipped.
	 *
	 * @param inputs the arrays of bytes to be updated before the digest is completed.
	 *
	 * @return the array of bytes for the resulting hash value.
	 */
	public static byte[] sha256(byte[]... inputs) {
		MessageDigest md = sha256();

		for (byte[] input : inputs) {
			// noinspection ConstantConditions
			if (input != null && input.length != 0)
				md.update(input);
		}

		return md.digest();
	}

	/**
	 * Performs a double SHA-256 hash on the specified portion of the input array.
	 * First computes SHA-256 on the input bytes, then computes SHA-256 on the result.
	 *
	 * @param input  the array of bytes.
	 * @param offset the offset to start from in the array of bytes.
	 * @param len    the number of bytes to use, starting at {@code offset}.
	 * @return the array of bytes for the resulting double hash value.
	 */
	public static byte[] sha256Twice(byte[] input, int offset, int len) {
		MessageDigest md = sha256();
		md.update(input, offset, len);
		return md.digest(md.digest());
	}

	/**
	 * Performs a double SHA-256 hash on the specified input array.
	 * First computes SHA-256 on the input bytes, then computes SHA-256 on the result.
	 *
	 * @param input the input to be double hashed.
	 * @return the array of bytes for the resulting double hash value.
	 */
	public static byte[] sha256Twice(byte[] input) {
		MessageDigest md = sha256();
		return md.digest(md.digest(input));
	}

	/**
	 * Performs a double SHA-256 hash on the specified arrays of bytes.
	 * Null or empty arrays are skipped.
	 * First computes SHA-256 on the concatenated input bytes, then computes SHA-256 on the result.
	 *
	 * @param inputs the arrays of bytes to be double hashed.
	 * @return the array of bytes for the resulting double hash value.
	 */
	public static byte[] sha256Twice(byte[]... inputs) {
		MessageDigest md = sha256();

		for (byte[] input : inputs) {
			//noinspection ConstantConditions
			if (input != null && input.length != 0)
				md.update(input);
		}

		return md.digest(md.digest());
	}

	/**
	 * Create a new MD5 message digest object.
	 * <p>
	 * <strong>Security note:</strong> MD5 is not collision-resistant; use only for
	 * non-cryptographic purposes. Prefer {@link #sha256()} for anything security-related.
	 *
	 * @return a new MD5 {@code MessageDigest} instance
	 */
	public static MessageDigest md5() {
		try {
			return MessageDigest.getInstance("MD5");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);  // never happen
		}
	}

	/**
	 * Performs a final update on the digest using the specified array of bytes,
	 * then completes the digest computation.
	 *
	 * @param input the input to be updated before the digest is completed.
	 *
	 * @return the array of bytes for the resulting hash value.
	 */
	public static byte[] md5(byte[] input) {
		return md5().digest(input);
	}

	/**
	 * Performs a final update on the digest using the specified arrays of bytes,
	 * then completes the digest computation.
	 * Null or empty arrays are skipped.
	 *
	 * @param inputs the arrays of bytes to be updated before the digest is completed.
	 *
	 * @return the array of bytes for the resulting hash value.
	 */
	public static byte[] md5(byte[]... inputs) {
		MessageDigest md = md5();

		for (byte[] input : inputs) {
			//noinspection ConstantConditions
			if (input != null && input.length != 0)
				md.update(input);
		}

		return md.digest();
	}

}