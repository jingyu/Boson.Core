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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Objects;

/**
 * Utility class for hashing passwords using different security levels and Argon2 algorithms.
 * <p>
 * This class provides static methods for hashing passwords using interactive, moderate, or sensitive security levels,
 * as well as direct parameterized hashing. It supports Argon2i and Argon2id algorithms, and delegates cryptographic
 * operations to the active {@link CryptoProvider}. The encoded hash strings use the standard Argon2 PHC format and
 * are interoperable with libsodium's {@code crypto_pwhash_str}.
 */
public class PasswordHash {
	/**
	 * The fixed number of bytes required for a valid salt.
	 */
	public static final int SALT_BYTES = CryptoProvider.PWHASH_SALT_BYTES;

	/**
	 * Maximum allowed size (in bytes) for password hash output.
	 */
	public static final int MAX_HASH_BYTES = Integer.MAX_VALUE;
	/**
	 * Minimum allowed size (in bytes) for password hash output.
	 */
	public static final int MIN_HASH_BYTES = 16;

	// libsodium crypto_pwhash predefined limits (Argon2id based).
	private static final long INTERACTIVE_OPS = 2L;
	private static final long INTERACTIVE_MEM = 67108864L;   // 64 MiB
	private static final long MODERATE_OPS = 3L;
	private static final long MODERATE_MEM = 268435456L;     // 256 MiB
	private static final long SENSITIVE_OPS = 4L;
	private static final long SENSITIVE_MEM = 1073741824L;   // 1 GiB

	private static CryptoProvider provider() {
		return CryptoProviders.getDefault();
	}

	/**
	 * Enum representing the Argon2 algorithms available for password hashing.
	 * <p>
	 * Provides Argon2i (version 1.3) and Argon2id (version 1.3) algorithms. The {@link #DEFAULT} selection uses
	 * Argon2id.
	 * </p>
	 */
	public enum Algorithm {
		/**
		 * Argon2i version 1.3 algorithm.
		 */
		ARGON2I13(CryptoProvider.PWHASH_ALG_ARGON2I13),
		/**
		 * Argon2id version 1.3 algorithm.
		 */
		ARGON2ID13(CryptoProvider.PWHASH_ALG_ARGON2ID13);

		private final int id;

		/**
		 * The default algorithm to use for password hashing (Argon2id).
		 */
		public static final Algorithm DEFAULT = ARGON2ID13;

		Algorithm(int id) {
			this.id = id;
		}

		/**
		 * Returns the Algorithm corresponding to the given id.
		 *
		 * @param id the algorithm id (1 for Argon2i, 2 for Argon2id)
		 * @return the Algorithm enum value
		 * @throws IllegalArgumentException if the id is invalid
		 */
		public static Algorithm valueOf(int id) {
			if (id == CryptoProvider.PWHASH_ALG_ARGON2I13)
				return ARGON2I13;
			else if (id == CryptoProvider.PWHASH_ALG_ARGON2ID13)
				return ARGON2ID13;
			else
				throw new IllegalArgumentException("Invalid algorithm id: " + id);
		}

		/**
		 * Returns the integer id for this algorithm.
		 *
		 * @return the algorithm id
		 */
		public int id() {
			return id;
		}
	}

	/**
	 * Compute a key from a password, using limits on operations and memory that are
	 * suitable for interactive use-cases.
	 *
	 * @param password  The password to hash.
	 * @param length    The key length to generate.
	 * @param salt      A salt.
	 * @param algorithm The algorithm to use.
	 * @return The derived key.
	 */
	public static byte[] hashInteractive(String password, int length, byte[] salt, Algorithm algorithm) {
		return hashInteractive(password.getBytes(UTF_8), length, salt, algorithm);
	}

	/**
	 * Compute a key from a password, using limits on operations and memory that are
	 * suitable for interactive use-cases.
	 *
	 * @param password  The password to hash.
	 * @param length    The key length to generate.
	 * @param salt      A salt.
	 * @param algorithm The algorithm to use.
	 * @return The derived key.
	 */
	public static byte[] hashInteractive(byte[] password, int length, byte[] salt, Algorithm algorithm) {
		Objects.requireNonNull(password, "Password must not be null");
		if (Objects.requireNonNull(salt, "Salt must not be null").length != SALT_BYTES)
			throw new IllegalArgumentException("Invalid salt length: expected " + SALT_BYTES + " bytes, got " + salt.length);

		return provider().pwHash(password, length, salt, INTERACTIVE_OPS, INTERACTIVE_MEM, algorithm.id());
	}

	/**
	 * Compute a key from a password, using limits on operations and memory that are
	 * suitable for moderate use-cases.
	 *
	 * @param password  The password to hash.
	 * @param length    The key length to generate.
	 * @param salt      A salt.
	 * @param algorithm The algorithm to use.
	 * @return The derived key.
	 */
	public static byte[] hashModerate(String password, int length, byte[] salt, Algorithm algorithm) {
		return hashModerate(password.getBytes(UTF_8), length, salt, algorithm);
	}

	/**
	 * Compute a key from a password, using limits on operations and memory that are
	 * suitable for moderate use-cases.
	 *
	 * @param password  The password to hash.
	 * @param length    The key length to generate.
	 * @param salt      A salt.
	 * @param algorithm The algorithm to use.
	 * @return The derived key.
	 */
	public static byte[] hashModerate(byte[] password, int length, byte[] salt, Algorithm algorithm) {
		Objects.requireNonNull(password, "Password must not be null");
		if (Objects.requireNonNull(salt, "Salt must not be null").length != SALT_BYTES)
			throw new IllegalArgumentException("Invalid salt length: expected " + SALT_BYTES + " bytes, got " + salt.length);

		return provider().pwHash(password, length, salt, MODERATE_OPS, MODERATE_MEM, algorithm.id());
	}

	/**
	 * Compute a key from a password, using limits on operations and memory that are
	 * suitable for sensitive use-cases.
	 *
	 * @param password  The password to hash.
	 * @param length    The key length to generate.
	 * @param salt      A salt.
	 * @param algorithm The algorithm to use.
	 * @return The derived key.
	 */
	public static byte[] hashSensitive(String password, int length, byte[] salt, Algorithm algorithm) {
		return hashSensitive(password.getBytes(UTF_8), length, salt, algorithm);
	}

	/**
	 * Compute a key from a password, using limits on operations and memory that are
	 * suitable for sensitive use-cases.
	 *
	 * @param password  The password to hash.
	 * @param length    The key length to generate.
	 * @param salt      A salt.
	 * @param algorithm The algorithm to use.
	 * @return The derived key.
	 */
	public static byte[] hashSensitive(byte[] password, int length, byte[] salt, Algorithm algorithm) {
		Objects.requireNonNull(password, "Password must not be null");
		if (Objects.requireNonNull(salt, "Salt must not be null").length != SALT_BYTES)
			throw new IllegalArgumentException("Invalid salt length: expected " + SALT_BYTES + " bytes, got " + salt.length);

		return provider().pwHash(password, length, salt, SENSITIVE_OPS, SENSITIVE_MEM, algorithm.id());
	}

	/**
	 * Compute a key from a password.
	 *
	 * @param password The password to hash.
	 * @param length The key length to generate.
	 * @param salt A salt.
	 * @param opsLimit The operations limit.
	 * @param memLimit The memory limit in bytes.
	 * @param algorithm The algorithm to use.
	 * @return The derived key.
	 */
	public static byte[] hash(String password, int length, byte[] salt, long opsLimit, long memLimit, Algorithm algorithm) {
		return hash(password.getBytes(UTF_8), length, salt, opsLimit, memLimit, algorithm);
	}

	/**
	 * Compute a key from a password.
	 *
	 * @param password The password to hash.
	 * @param length The key length to generate.
	 * @param salt A salt.
	 * @param opsLimit The operations limit.
	 * @param memLimit The memory limit in bytes.
	 * @param algorithm The algorithm to use.
	 * @return The derived key.
	 */
	public static byte[] hash(byte[] password, int length, byte[] salt, long opsLimit, long memLimit, Algorithm algorithm) {
		Objects.requireNonNull(password, "Password must not be null");
		if (Objects.requireNonNull(salt, "Salt must not be null").length != SALT_BYTES)
			throw new IllegalArgumentException("Invalid salt length: expected " + SALT_BYTES + " bytes, got " + salt.length);

		return provider().pwHash(password, length, salt, opsLimit, memLimit, algorithm.id());
	}

	/**
	 * Compute a hash from a password, using limits on operations and memory that
	 * are suitable for interactive use-cases.
	 *
	 * @param password The password to hash.
	 * @return The hash string.
	 */
	public static String hashInteractive(String password) {
		return provider().pwHashString(password.getBytes(UTF_8), INTERACTIVE_OPS, INTERACTIVE_MEM,
				Algorithm.DEFAULT.id());
	}

	/**
	 * Compute a hash from a password, using limits on operations and memory that
	 * are suitable for most moderate use-cases.
	 *
	 * @param password The password to hash.
	 * @return The hash string.
	 */
	public static String hashModerate(String password) {
		return provider().pwHashString(password.getBytes(UTF_8), MODERATE_OPS, MODERATE_MEM, Algorithm.DEFAULT.id());
	}

	/**
	 * Compute a hash from a password, using limits on operations and memory that
	 * are suitable for sensitive use-cases.
	 *
	 * @param password The password to hash.
	 * @return The hash string.
	 */
	public static String hashSensitive(String password) {
		return provider().pwHashString(password.getBytes(UTF_8), SENSITIVE_OPS, SENSITIVE_MEM, Algorithm.DEFAULT.id());
	}

	/**
	 * Compute a hash from a password.
	 *
	 * @param password The password to hash.
	 * @param opsLimit The operations limit.
	 * @param memLimit The memory limit in bytes.
	 * @return The hash string.
	 */
	public static String hash(String password, long opsLimit, long memLimit) {
		return provider().pwHashString(password.getBytes(UTF_8), opsLimit, memLimit, Algorithm.DEFAULT.id());
	}

	/**
	 * Verify a password against a hash.
	 *
	 * @param hash     The hash.
	 * @param password The password to verify.
	 * @return {@code true} if the password matches the hash.
	 */
	public static boolean verify(String hash, String password) {
		return provider().pwHashVerify(hash, password.getBytes(UTF_8));
	}

	/**
	 * Check if a hash needs to be regenerated using limits on operations and memory
	 * that are suitable for interactive use-cases.
	 *
	 * @param hash The hash.
	 * @return {@code true} if the hash should be regenerated.
	 */
	public static boolean needsRehashForInteractive(String hash) {
		return provider().pwHashNeedsRehash(hash, INTERACTIVE_OPS, INTERACTIVE_MEM);
	}

	/**
	 * Check if a hash needs to be regenerated using limits on operations and memory
	 * that are suitable for most moderate use-cases.
	 *
	 * @param hash The hash.
	 * @return {@code true} if the hash should be regenerated.
	 */
	public static boolean needsRehashForModerate(String hash) {
		return provider().pwHashNeedsRehash(hash, MODERATE_OPS, MODERATE_MEM);
	}

	/**
	 * Check if a hash needs to be regenerated using limits on operations and memory
	 * that are suitable for sensitive use-cases.
	 *
	 * @param hash The hash.
	 * @return {@code true} if the hash should be regenerated.
	 */
	public static boolean needsRehashForSensitive(String hash) {
		return provider().pwHashNeedsRehash(hash, SENSITIVE_OPS, SENSITIVE_MEM);
	}
}