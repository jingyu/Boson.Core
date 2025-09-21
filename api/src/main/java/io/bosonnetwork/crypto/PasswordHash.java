package io.bosonnetwork.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
/**
 * Utility class for hashing passwords using different security levels and Argon2 algorithms.
 * <p>
 * This class provides static methods for hashing passwords using interactive, moderate, or sensitive security levels,
 * as well as direct parameterized hashing. It supports Argon2i and Argon2id algorithms, and delegates cryptographic
 * operations to Tuweni Sodium.
 * </p>
 */
public class PasswordHash {
	/**
	 * Maximum allowed size (in bytes) for password hash output as defined by the underlying sodium implementation.
	 */
	public static final int MAX_HASH_BYTES = org.apache.tuweni.crypto.sodium.PasswordHash.maxHashLength();
	/**
	 * Minimum allowed size (in bytes) for password hash output as defined by the underlying sodium implementation.
	 */
	public static final int MIN_HASH_BYTES = org.apache.tuweni.crypto.sodium.PasswordHash.minHashLength();

	/**
	 * Enum representing the Argon2 algorithms available for password hashing.
	 * <p>
	 * Provides Argon2i (version 1.3) and Argon2id (version 1.3) algorithms. The {@link #DEFAULT} selection checks
	 * if Argon2id is supported in the native library and uses it; otherwise, falls back to Argon2i.
	 * </p>
	 */
	public enum Algorithm {
		/**
		 * Argon2i version 1.3 algorithm.
		 */
		ARGON2I13(1),
		/**
		 * Argon2id version 1.3 algorithm.
		 */
		ARGON2ID13(2);

		private final int id;

		/**
		 * The default algorithm to use for password hashing.
		 * <p>
		 * If Argon2id is supported by the loaded sodium library, it is selected; otherwise, Argon2i is used.
		 * </p>
		 */
		public static final Algorithm DEFAULT =
				org.apache.tuweni.crypto.sodium.PasswordHash.Algorithm.argon2id13().isSupported() ?
				ARGON2ID13 : ARGON2I13;

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
			if (id == 1)
				return ARGON2I13;
			else if (id == 2)
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

		org.apache.tuweni.crypto.sodium.PasswordHash.Algorithm raw() {
			if (id == 1)
				return org.apache.tuweni.crypto.sodium.PasswordHash.Algorithm.argon2i13();
			else
				return org.apache.tuweni.crypto.sodium.PasswordHash.Algorithm.argon2id13();
		}
	}

	/**
	 * Represents a salt value used for password hashing.
	 * <p>
	 * Provides methods to generate a random salt or create a salt from an existing byte array.
	 * </p>
	 */
	public static class Salt {
		/**
		 * The fixed number of bytes required for a valid salt.
		 */
		public static final int BYTES = org.apache.tuweni.crypto.sodium.PasswordHash.Salt.length();

		private final org.apache.tuweni.crypto.sodium.PasswordHash.Salt salt;
		private byte[] bytes;

		private Salt(org.apache.tuweni.crypto.sodium.PasswordHash.Salt salt) {
			this.salt = salt;
		}

		/**
		 * Creates a salt object from the given byte array.
		 *
		 * @param salt the byte array containing the salt value
		 * @return a new {@code Salt} instance wrapping the given bytes
		 * @throws IllegalArgumentException if the byte array is not of the correct length
		 */
		public static Salt fromBytes(byte[] salt) {
			// No SodiumException raised
			return new Salt(org.apache.tuweni.crypto.sodium.PasswordHash.Salt.fromBytes(salt));
		}

		/**
		 * Generates a new random salt suitable for password hashing.
		 *
		 * @return a new randomly generated {@code Salt} instance
		 */
		public static Salt random() {
			// No SodiumException raised
			return new Salt(org.apache.tuweni.crypto.sodium.PasswordHash.Salt.random());
		}

		org.apache.tuweni.crypto.sodium.PasswordHash.Salt raw() {
			return salt;
		}

		/**
		 * Provides the bytes of this salt.
		 *
		 * @return the bytes of this salt
		 */
		public byte[] bytes() {
			if (bytes == null)
				bytes = salt.bytesArray();

			return bytes;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this)
				return true;

			if (obj instanceof Salt that)
				return salt.equals(that.salt);

			return false;
		}

		@Override
		public int hashCode() {
			return 0x6030A + salt.hashCode();
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
	public static byte[] hashInteractive(String password, int length, Salt salt, Algorithm algorithm) {
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
	public static byte[] hashInteractive(byte[] password, int length, Salt salt, Algorithm algorithm) {
		return org.apache.tuweni.crypto.sodium.PasswordHash.hashInteractive(password, length, salt.raw(), algorithm.raw());
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
	public static byte[] hashModerate(String password, int length, Salt salt, Algorithm algorithm) {
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
	public static byte[] hashModerate(byte[] password, int length, Salt salt, Algorithm algorithm) {
		return org.apache.tuweni.crypto.sodium.PasswordHash.hash(password, length, salt.raw(), algorithm.raw());
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
	public static byte[] hashSensitive(String password, int length, Salt salt, Algorithm algorithm) {
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
	public static byte[] hashSensitive(byte[] password, int length, Salt salt, Algorithm algorithm) {
		return org.apache.tuweni.crypto.sodium.PasswordHash.hashSensitive(password, length, salt.raw(), algorithm.raw());
	}

	/**
	 * Compute a key from a password.
	 *
	 * @param password The password to hash.
	 * @param length The key length to generate.
	 * @param salt A salt.
	 * @param opsLimit The operations limit, which must be in the range minMemLimit to maxMemLimit.
	 * @param memLimit The memory limit, which must be in the range minMemLimit to maxMemLimit.
	 * @param algorithm The algorithm to use.
	 * @return The derived key.
	 * @throws IllegalArgumentException If the opsLimit is too low for the specified algorithm.
	 * @throws UnsupportedOperationException If the specified algorithm is not supported by the currently loaded sodium
	 *         native library.
	 */
	public static byte[] hash(String password, int length, Salt salt, long opsLimit, long memLimit, Algorithm algorithm) {
		return hash(password.getBytes(UTF_8), length, salt, opsLimit, memLimit, algorithm);
	}

	/**
	 * Compute a key from a password.
	 *
	 * @param password The password to hash.
	 * @param length The key length to generate.
	 * @param salt A salt.
	 * @param opsLimit The operations limit, which must be in the range minMemLimit to maxMemLimit.
	 * @param memLimit The memory limit, which must be in the range minMemLimit to maxMemLimit.
	 * @param algorithm The algorithm to use.
	 * @return The derived key.
	 * @throws IllegalArgumentException If the opsLimit is too low for the specified algorithm.
	 * @throws UnsupportedOperationException If the specified algorithm is not supported by the currently loaded sodium
	 *         native library.
	 */
	public static byte[] hash(byte[] password, int length, Salt salt, long opsLimit, long memLimit, Algorithm algorithm) {
		return org.apache.tuweni.crypto.sodium.PasswordHash.hash(password, length, salt.raw(), opsLimit, memLimit, algorithm.raw());
	}

	/**
	 * Compute a hash from a password, using limits on operations and memory that
	 * are suitable for interactive use-cases.
	 *
	 * @param password The password to hash.
	 * @return The hash string.
	 */
	public static String hashInteractive(String password) {
		return org.apache.tuweni.crypto.sodium.PasswordHash.hashInteractive(password);
	}

	/**
	 * Compute a hash from a password, using limits on operations and memory that
	 * are suitable for most moderate use-cases.
	 *
	 * @param password The password to hash.
	 * @return The hash string.
	 */
	public static String hashModerate(String password) {
		return org.apache.tuweni.crypto.sodium.PasswordHash.hash(password);
	}

	/**
	 * Compute a hash from a password, using limits on operations and memory that
	 * are suitable for sensitive use-cases.
	 *
	 * @param password The password to hash.
	 * @return The hash string.
	 */
	public static String hashSensitive(String password) {
		return org.apache.tuweni.crypto.sodium.PasswordHash.hashSensitive(password);
	}

	/**
	 * Compute a hash from a password.
	 *
	 * @param password The password to hash.
	 * @param opsLimit The operations limit, which must be in the range minMemLimit to maxMemLimit.
	 * @param memLimit The memory limit, which must be in the range minMemLimit to maxMemLimit.
	 * @return The hash string.
	 */
	public static String hash(String password, long opsLimit, long memLimit) {
		return org.apache.tuweni.crypto.sodium.PasswordHash.hash(password, opsLimit, memLimit);
	}

	/**
	 * Verify a password against a hash.
	 *
	 * @param hash     The hash.
	 * @param password The password to verify.
	 * @return {@code true} if the password matches the hash.
	 */
	public static boolean verify(String hash, String password) {
		return org.apache.tuweni.crypto.sodium.PasswordHash.verify(hash, password);
	}

	/**
	 * Check if a hash needs to be regenerated using limits on operations and memory
	 * that are suitable for interactive use-cases.
	 *
	 * <p>
	 * Note: only supported when the sodium native library version &gt;= 10.0.14 is
	 * available.
	 *
	 * @param hash The hash.
	 * @return {@code true} if the hash should be regenerated.
	 */
	public static boolean needsRehashForInteractive(String hash) {
		return org.apache.tuweni.crypto.sodium.PasswordHash.needsRehashForInteractive(hash);
	}

	/**
	 * Check if a hash needs to be regenerated using limits on operations and memory
	 * that are suitable for most moderate use-cases.
	 *
	 * <p>
	 * Note: only supported when the sodium native library version &gt;= 10.0.14 is
	 * available.
	 *
	 * @param hash The hash.
	 * @return {@code true} if the hash should be regenerated.
	 */
	public static boolean needsRehashForModerate(String hash) {
		return org.apache.tuweni.crypto.sodium.PasswordHash.needsRehash(hash);
	}

	/**
	 * Check if a hash needs to be regenerated using limits on operations and memory
	 * that are suitable for sensitive use-cases.
	 *
	 * <p>
	 * Note: only supported when the sodium native library version &gt;= 10.0.14 is
	 * available.
	 *
	 * @param hash The hash.
	 * @return {@code true} if the hash should be regenerated.
	 */
	public static boolean needsRehashForSensitive(String hash) {
		return org.apache.tuweni.crypto.sodium.PasswordHash.needsRehashForSensitive(hash);
	}
}