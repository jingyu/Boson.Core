package io.bosonnetwork.crypto;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Utility class providing methods for generating SHA-256 and MD5 hashes.
 * Supports hashing single or multiple byte arrays, as well as performing double SHA-256 hashing.
 */
public class Hash {
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
			if (input != null && input.length != 0)
				md.update(input);
		}

		return md.digest(md.digest());
	}

	/**
	 * Create a new MD5 message digest object.
	 *
	 * @return the current thread's MD5 {@code MessageDigest}
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
			if (input != null && input.length != 0)
				md.update(input);
		}

		return md.digest();
	}

}