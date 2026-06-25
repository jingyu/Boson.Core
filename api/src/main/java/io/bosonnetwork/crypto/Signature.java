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

package io.bosonnetwork.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import javax.security.auth.Destroyable;

/**
 * Public-key(Ed25519) signatures.
 */
public interface Signature {
	/**
	 * The signing(Ed25519) public key object.
	 */
	interface PublicKey extends Destroyable {
		/**
		 * The number of bytes used to represent a public key.
		 */
		int BYTES = CryptoProvider.SIGN_PUBLIC_KEY_BYTES;

		/**
		 * Create a PublicKey from an array of bytes. The byte array must be of
		 * length {@link #BYTES}.
		 *
		 * @param key the bytes for the public key.
		 * @return the created public key object.
		 * @throws IllegalArgumentException if {@code key} is not {@link #BYTES} bytes long.
		 */
		static PublicKey fromBytes(byte[] key) {
			if (Objects.requireNonNull(key, "key").length != BYTES)
				throw new IllegalArgumentException("Invalid public key size: expected " + BYTES + " bytes, got " + key.length);

			return provider().ed25519PublicKeyFromBytes(key);
		}

		/**
		 * Derive the public key that corresponds to the given private key.
		 *
		 * @param key the private key.
		 * @return the matching public key.
		 */
		static PublicKey fromPrivateKey(PrivateKey key) {
			Objects.requireNonNull(key, "key");
			return provider().ed25519PublicKeyFromSecretKey(key);
		}

		/**
		 * Provides the bytes of this key.
		 *
		 * @return the bytes of this key.
		 */
		byte[] bytes();

		/**
		 * Verifies the signature of a message.
		 *
		 * @param message   the message to verify.
		 * @param signature the signature of the message.
		 * @return true if the signature matches the message according to this public key.
		 */
		default boolean verify(byte[] message, byte[] signature) {
			return provider().ed25519Verify(message, signature, this);
		}

		@Override
		void destroy();

		@Override
		boolean isDestroyed();
	}

	/**
	 * The signing(Ed25519) private key object.
	 */
	interface PrivateKey extends Destroyable {
		/**
		 * The number of bytes used to represent a private key (seed followed by public key).
		 */
		int BYTES = CryptoProvider.SIGN_SECRET_KEY_BYTES;

		/**
		 * Creates a new {@code PrivateKey} object from the specified seed.
		 *
		 * @param seed the {@link KeyPair#SEED_BYTES}-byte seed for the private key. Must not be null.
		 * @return a new {@code PrivateKey} created from the given seed.
		 * @throws IllegalArgumentException if {@code seed} is not {@link KeyPair#SEED_BYTES} bytes long.
		 */
		static PrivateKey fromSeed(byte[] seed) {
			if (Objects.requireNonNull(seed, "seed").length != KeyPair.SEED_BYTES)
				throw new IllegalArgumentException("Invalid seed size: expected " + KeyPair.SEED_BYTES + " bytes, got " + seed.length);

			return provider().ed25519SecretKeyFromSeed(seed);
		}

		/**
		 * Create a PrivateKey from an array of bytes. The byte array
		 * must be of length {@link #BYTES}.
		 *
		 * @param key the bytes for the secret key.
		 * @return the created private key object.
		 * @throws IllegalArgumentException if {@code key} is not {@link #BYTES} bytes long.
		 */
		static PrivateKey fromBytes(byte[] key) {
			if (Objects.requireNonNull(key, "key").length != BYTES)
				throw new IllegalArgumentException("Invalid private key size: expected " + BYTES + " bytes, got " + key.length);

			return provider().ed25519SecretKeyFromBytes(key);
		}

		/**
		 * Provides the {@link KeyPair#SEED_BYTES}-byte seed of this secret key.
		 *
		 * @return the seed bytes.
		 */
		byte[] seed();

		/**
		 * Provides the bytes of this secret key.
		 *
		 * @return the bytes of this secret key.
		 */
		byte[] bytes();

		/**
		 * Derives a new {@code PrivateKey} based on the provided subkey ID and context string.
		 *
		 * @param subKeyId the identifier for the derived subkey. This ensures that the generated private key
		 *                 is unique per subkey ID.
		 * @param context  the context string used during the key derivation process. Must not be null.
		 * @return a newly derived {@code PrivateKey} created using the specified subkey ID and context.
		 */
		default PrivateKey derive(long subKeyId, String context) {
			return derive(subKeyId, deriveContextBytes(context));
		}

		/**
		 * Derives a new {@code PrivateKey} based on the provided subkey ID and context.
		 *
		 * @param subKeyId the identifier for the derived subkey. This is used to ensure the generated key
		 *                 is unique per subkey ID.
		 * @param context  the context-specific data used during key derivation. Must be provided as a byte
		 *                 array and cannot be null.
		 * @return a new {@code PrivateKey} derived using the specified subkey ID and context.
		 */
		default PrivateKey derive(long subKeyId, byte[] context) {
			if (Objects.requireNonNull(context, "context").length != CryptoProvider.KDF_CONTEXT_BYTES)
				throw new IllegalArgumentException("Invalid context size: expected "
						+ CryptoProvider.KDF_CONTEXT_BYTES + " bytes, got " + context.length);

			byte[] master = seed();
			byte[] subSeed = provider().kdfDeriveFromKey(master, subKeyId, context, KeyPair.SEED_BYTES);
			return PrivateKey.fromSeed(subSeed);
		}

		/**
		 * Signs a message with this private key.
		 *
		 * @param message the message to sign.
		 * @return the signature of the message.
		 */
		default byte[] sign(byte[] message) {
			return provider().ed25519Sign(message, this);
		}

		@Override
		void destroy();

		@Override
		boolean isDestroyed();
	}

	/**
	 * The signing(Ed25519) key pair.
	 */
	class KeyPair implements Destroyable {
		/**
		 * The seed length in bytes.
		 */
		public static final int SEED_BYTES = CryptoProvider.SIGN_SEED_BYTES;

		private final PublicKey pk;
		private final PrivateKey sk;

		private KeyPair(PrivateKey sk) {
			this.sk = sk;
			this.pk = PublicKey.fromPrivateKey(sk);
		}

		/**
		 * Create a KeyPair from an array of private key bytes. The byte array must be of
		 * length {@link PrivateKey#BYTES}.
		 *
		 * @param privateKey the private key bytes.
		 * @return the created key pair object.
		 */
		public static KeyPair fromPrivateKey(byte[] privateKey) {
			return new KeyPair(PrivateKey.fromBytes(privateKey));
		}

		/**
		 * Create a KeyPair from a private key object.
		 *
		 * @param privateKey the private key object.
		 * @return the created key pair object.
		 */
		public static KeyPair fromPrivateKey(PrivateKey privateKey) {
			return new KeyPair(privateKey);
		}

		/**
		 * Generate a new key using a seed. The seed byte array must be of
		 * length {@link #SEED_BYTES}.
		 *
		 * @param seed the seed bytes.
		 * @return the created key pair object.
		 */
		public static KeyPair fromSeed(byte[] seed) {
			return new KeyPair(PrivateKey.fromSeed(seed));
		}

		/**
		 * Generate a new key using a random generator.
		 *
		 * @return a randomly generated key pair.
		 */
		public static KeyPair random() {
			return fromSeed(Random.randomBytesSecure(SEED_BYTES));
		}

		/**
		 * Gets the public key of this key pair.
		 *
		 * @return the public key of the key pair.
		 */
		public PublicKey publicKey() {
			return pk;
		}

		/**
		 * Gets the private key of this key pair.
		 *
		 * @return the private key of the key pair.
		 */
		public PrivateKey privateKey() {
			return sk;
		}

		/**
		 * Derives a new {@code KeyPair} based on the provided subkey ID and context.
		 *
		 * @param subKeyId the identifier for the derived subkey. This ensures that the generated private key
		 *                 is unique per subkey ID.
		 * @param context  the context string used during the key derivation process. Must not be null.
		 * @return the derived {@code KeyPair} instance.
		 */
		public KeyPair derive(long subKeyId, String context) {
			return new KeyPair(sk.derive(subKeyId, context));
		}

		/**
		 * Derives a new {@code KeyPair} based on the provided subkey ID and context.
		 *
		 * @param subKeyId the identifier for the derived subkey. This is used to ensure the generated key
		 *                 is unique per subkey ID.
		 * @param context  the context-specific data used during the key derivation process. Must be provided
		 *                 as a byte array and cannot be null.
		 * @return the derived {@code KeyPair} instance.
		 */
		public KeyPair derive(long subKeyId, byte[] context) {
			return new KeyPair(sk.derive(subKeyId, context));
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this)
				return true;

			if (obj instanceof KeyPair that)
				return sk.equals(that.sk) && pk.equals(that.pk);

			return false;
		}

		@Override
		public int hashCode() {
			return Objects.hash(sk, pk);
		}

		/**
		 * Destroys this key pair, wiping the underlying public and private key material.
		 */
		@Override
		public void destroy() {
			pk.destroy();
			sk.destroy();
		}

		/**
		 * Determine if this key pair has been destroyed.
		 *
		 * @return true if this key pair has been destroyed, false otherwise.
		 */
		@Override
		public boolean isDestroyed() {
			return sk.isDestroyed();
		}
	}

	/**
	 * The number of bytes used to represent a signature.
	 */
	public static final int BYTES = CryptoProvider.SIGN_BYTES;

	/**
	 * Derives the fixed-length (8-byte) key-derivation context from a context string.
	 * <p>
	 * The string is hashed with SHA-256 and the 32-byte digest is folded down to the 8 bytes
	 * required by the {@code crypto_kdf} context.
	 * <p>
	 * <strong>Note:</strong> the 8-byte context is a lossy reduction (the fixed context
	 * size), so distinct context strings can still collide and, for the same sub-key id, derive the
	 * same key. Use distinct sub-key ids when strong domain separation is required.
	 *
	 * @param context the context string; must not be null or empty
	 * @return the 8-byte derivation context
	 */
	private static byte[] deriveContextBytes(String context) {
		Objects.requireNonNull(context, "context");
		if (context.isEmpty())
			throw new IllegalArgumentException("context must not be empty");

		final int len = CryptoProvider.KDF_CONTEXT_BYTES; // 8 bytes
		byte[] contextBytes = new byte[len];
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			byte[] hashBytes = sha.digest(context.getBytes(StandardCharsets.UTF_8));
			for (int i = 0; i < hashBytes.length; i++)
				contextBytes[i % len] += hashBytes[i];
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException(e);
		}
		return contextBytes;
	}

	/**
	 * Signs a message with a given key.
	 *
	 * @param message the message to sign.
	 * @param key     the private key to sign the message with.
	 * @return the signature of the message.
	 */
	static byte[] sign(byte[] message, PrivateKey key) {
		return provider().ed25519Sign(message, key);
	}

	/**
	 * Verifies the signature of a message.
	 *
	 * @param message   the message to verify.
	 * @param signature the signature of the message.
	 * @param key       the public key to verify the message with.
	 * @return true if the signature matches the message according to this public key.
	 */
	static boolean verify(byte[] message, byte[] signature, PublicKey key) {
		return key.verify(message, signature);
	}

	private static CryptoProvider provider() {
		return CryptoProviders.getDefault();
	}
}