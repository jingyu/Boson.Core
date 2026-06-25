/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
 * Copyright (c) 2023 -	  bosonnetwork.io
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

import java.util.Objects;
import javax.security.auth.Destroyable;

/**
 * Public-key(Curve 25519) authenticated encryption.
 * <p>
 * A {@code CryptoBox} instance is a precomputed shared key for a sender/receiver pair
 * (libsodium {@code crypto_box_beforenm}); its {@link #encrypt} / {@link #decrypt} methods are
 * the per-message {@code afternm} operations. Keys are provider-specific objects produced and
 * consumed by the active {@link CryptoProvider}; callers obtain them through the static
 * factories and treat them as opaque handles.
 */
public interface CryptoBox extends AutoCloseable, Destroyable {
	/**
	 * The Message Authentication Code size of the encrypted data in bytes.
	 */
	int MAC_BYTES = CryptoProvider.BOX_MAC_BYTES;

	/**
	 * The crypto box public key object.
	 */
	interface PublicKey extends Destroyable {
		/**
		 * The number of bytes used to represent a public key.
		 */
		int BYTES = CryptoProvider.BOX_PUBLIC_KEY_BYTES;

		/**
		 * Create a {@link PublicKey} from an array of bytes.
		 *
		 * @param key the bytes for the public key.
		 * @return the public key object.
		 * @throws IllegalArgumentException if {@code key} is not {@link #BYTES} bytes long.
		 */
		static PublicKey fromBytes(byte[] key) {
			if (Objects.requireNonNull(key, "key").length != BYTES)
				throw new IllegalArgumentException("Invalid public key size: expected " + BYTES + " bytes, got " + key.length);

			return provider().boxPublicKeyFromBytes(key);
		}

		/**
		 * Transforms the Ed25519 signature public key to a Curve25519 public key.
		 *
		 * @param key the signature public key.
		 * @return the public key as a Curve25519 public key.
		 */
		static PublicKey fromSignatureKey(Signature.PublicKey key) {
			return provider().signPublicKeyToBoxPublicKey(Objects.requireNonNull(key));
		}

		/**
		 * Returns the raw bytes of this key.
		 *
		 * @return the raw bytes of this key.
		 */
		byte[] bytes();

		@Override
		void destroy();

		@Override
		boolean isDestroyed();
	}

	/**
	 * The crypto box private key object.
	 */
	interface PrivateKey extends Destroyable {
		/**
		 * The number of bytes used to represent a private key.
		 */
		int BYTES = CryptoProvider.BOX_SECRET_KEY_BYTES;

		/**
		 * Generate a {@link PrivateKey} from a seed (libsodium {@code crypto_box_seed_keypair}).
		 *
		 * @param seed the {@link KeyPair#SEED_BYTES}-byte seed.
		 * @return the private key.
		 * @throws IllegalArgumentException if {@code seed} is not {@link KeyPair#SEED_BYTES} bytes long.
		 */
		static PrivateKey fromSeed(byte[] seed) {
			if (Objects.requireNonNull(seed, "seed").length != KeyPair.SEED_BYTES)
				throw new IllegalArgumentException("Invalid seed size: expected " + KeyPair.SEED_BYTES + " bytes, got " + seed.length);

			return provider().boxSecretKeyFromSeed(seed);
		}

		/**
		 * Create a {@link PrivateKey} from an array of bytes.
		 *
		 * @param key the bytes for the private key.
		 * @return the private key.
		 * @throws IllegalArgumentException if {@code key} is not {@link #BYTES} bytes long.
		 */
		static PrivateKey fromBytes(byte[] key) {
			if (Objects.requireNonNull(key, "key").length != BYTES)
				throw new IllegalArgumentException("Invalid private key size: expected " + BYTES + " bytes, got " + key.length);

			return provider().boxSecretKeyFromBytes(key);
		}

		/**
		 * Transforms the Ed25519 private key to a Curve25519 private key.
		 *
		 * @param key the signature secret key
		 * @return the secret key as a Curve25519 private key
		 */
		static PrivateKey fromSignatureKey(Signature.PrivateKey key) {
			return provider().signSecretKeyToBoxSecretKey(Objects.requireNonNull(key));
		}

		/**
		 * Returns the raw bytes of this secret key.
		 *
		 * @return the raw bytes of this secret key.
		 */
		byte[] bytes();

		@Override
		void destroy();

		@Override
		boolean isDestroyed();
	}

	/**
	 * The crypto box key pair.
	 */
	class KeyPair implements Destroyable {
		/**
		 * The seed length in bytes.
		 */
		public static final int SEED_BYTES = CryptoProvider.BOX_SEED_BYTES;

		private final PublicKey pk;
		private final PrivateKey sk;

		private KeyPair(PrivateKey sk) {
			this.sk = sk;
			this.pk = provider().boxPublicKeyFromSecretKey(sk);
		}

		/**
		 * Create a {@link KeyPair} from an array of private key bytes.
		 *
		 * @param privateKey the raw private key.
		 * @return the key pair object.
		 */
		public static KeyPair fromPrivateKey(byte[] privateKey) {
			return new KeyPair(PrivateKey.fromBytes(privateKey));
		}

		/**
		 * Create a {@link KeyPair} from a {@link PrivateKey} object.
		 *
		 * @param key the private key object.
		 * @return the key pair object.
		 */
		public static KeyPair fromPrivateKey(PrivateKey key) {
			return new KeyPair(key);
		}

		/**
		 * Generate a new key pair using a seed (libsodium {@code crypto_box_seed_keypair}).
		 *
		 * @param seed the {@link #SEED_BYTES}-byte seed.
		 * @return the new generated key pair.
		 * @throws IllegalArgumentException if {@code seed} is not {@link #SEED_BYTES} bytes long.
		 */
		public static KeyPair fromSeed(byte[] seed) {
			if (Objects.requireNonNull(seed, "seed").length != SEED_BYTES)
				throw new IllegalArgumentException("Invalid seed size: expected " + SEED_BYTES + " bytes, got " + seed.length);

			return new KeyPair(PrivateKey.fromSeed(seed));
		}

		/**
		 * Converts a signature key pair (Ed25519) to a box key pair (Curve25519).
		 *
		 * @param keyPair a {@link Signature.KeyPair}.
		 * @return the new generated box key pair.
		 */
		public static KeyPair fromSignatureKeyPair(Signature.KeyPair keyPair) {
			return new KeyPair(PrivateKey.fromSignatureKey(keyPair.privateKey()));
		}

		/**
		 * Generate a new key pair using a random generator.
		 *
		 * @return a randomly generated key pair.
		 */
		public static KeyPair random() {
			return new KeyPair(PrivateKey.fromBytes(Random.randomBytesSecure(SEED_BYTES)));
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
	 * The nonce object for the crypto box encryption.
	 */
	interface Nonce {
		/**
		 * The number of bytes used to represent a nonce.
		 */
		public static final int BYTES = CryptoProvider.BOX_NONCE_BYTES;

		/**
		 * Create a Nonce object from an array of bytes.
		 *
		 * @param nonce the bytes for the nonce.
		 * @return a nonce object based on these bytes.
		 * @throws IllegalArgumentException if {@code nonce} is not {@link #BYTES} bytes long.
		 */
		static Nonce fromBytes(byte[] nonce) {
			if (Objects.requireNonNull(nonce, "nonce").length != BYTES)
				throw new IllegalArgumentException("Invalid nonce size: expected " + BYTES + " bytes, got " + nonce.length);

			return provider().boxNonceFromBytes(nonce);
		}

		/**
		 * Generate a random Nonce object.
		 *
		 * @return a randomly generated nonce.
		 */
		static Nonce random() {
			return provider().boxNonceFromBytes(Random.randomBytesSecure(BYTES));
		}

		/**
		 * Create a zero Nonce object.
		 *
		 * @return a zero nonce object.
		 */
		static Nonce zero() {
			return provider().boxNonceFromBytes(new byte[BYTES]);
		}


		/**
		 * Increment this nonce.
		 *
		 * <p>
		 * The nonce is treated as a little-endian integer and incremented by one, matching
		 * libsodium's {@code sodium_increment}.
		 *
		 * @return A new nonce object.
		 */
		Nonce increment();

		/**
		 * Provides the bytes of this nonce object.
		 *
		 * @return The bytes of this nonce.
		 */
		byte[] bytes();
	}

	/**
	 * Precompute the shared key for a given sender and receiver.
	 *
	 * <p>
	 * Note that the returned instance should be closed using {@link #close()} (or
	 * try-with-resources) to release the shared key.
	 *
	 * @param pk the public key of the receiver.
	 * @param sk the secret key of the sender.
	 * @return a precomputed crypto box instance.
	 */
	static CryptoBox fromKeys(PublicKey pk, PrivateKey sk) {
		Objects.requireNonNull(pk);
		Objects.requireNonNull(sk);
		return provider().boxBeforeNm(pk, sk);
	}

	/**
	 * Encrypt a message with the given keys.
	 *
	 * @param message the message to encrypt. Must not be null.
	 * @param receiver the public key of the receiver. Must not be null.
	 * @param sender the private key of the sender. Must not be null.
	 * @param nonce a unique nonce object. Must not be null.
	 * @return the encrypted data.
	 * @throws NullPointerException if any argument is null.
	 */
	static byte[] encrypt(byte[] message, PublicKey receiver, PrivateKey sender, Nonce nonce) {
		Objects.requireNonNull(message, "message");
		Objects.requireNonNull(receiver, "receiver");
		Objects.requireNonNull(sender, "sender");
		Objects.requireNonNull(nonce, "nonce");
		return provider().boxEncrypt(message, nonce, receiver, sender);
	}

	/**
	 * Decrypt a message using the given keys.
	 *
	 * @param cipher the cipher text to decrypt. Must not be null.
	 * @param sender the public key of the sender. Must not be null.
	 * @param receiver the private key of the receiver. Must not be null.
	 * @param nonce the nonce that was used for encryption. Must not be null.
	 * @return the decrypted data.
	 * @throws NullPointerException if any argument is null.
	 * @throws CryptoException if the verification or decryption failed.
	 */
	static byte[] decrypt(byte[] cipher, PublicKey sender, PrivateKey receiver, Nonce nonce) throws CryptoException {
		Objects.requireNonNull(cipher, "cipher");
		Objects.requireNonNull(sender, "sender");
		Objects.requireNonNull(receiver, "receiver");
		Objects.requireNonNull(nonce, "nonce");
		byte[] plain = provider().boxDecrypt(cipher, nonce, sender, receiver);
		if (plain == null)
			throw new CryptoException("Decryption failed: invalid ciphertext or authentication failure");

		return plain;
	}

	/**
	 * Encrypt a sealed message for a given key.
	 * <p>
	 * Sealed boxes are designed to anonymously send messages to a recipient given its public key.
	 * Only the recipient can decrypt these messages, using its private key.
	 *
	 * @param message the message to encrypt. Must not be null.
	 * @param receiver the public key of the receiver. Must not be null.
	 * @return the encrypted data.
	 * @throws NullPointerException if {@code message} or {@code receiver} is null.
	 */
	static byte[] encryptSealed(byte[] message, PublicKey receiver) {
		Objects.requireNonNull(message, "message");
		Objects.requireNonNull(receiver, "receiver");
		return provider().boxSeal(message, receiver);
	}

	/**
	 * Decrypt a sealed message using the given keys.
	 *
	 * @param cipher the cipher text to decrypt. Must not be null.
	 * @param pk the public key of the sender. Must not be null.
	 * @param sk the private key of the receiver. Must not be null.
	 * @return the decrypted data.
	 * @throws NullPointerException if any argument is null.
	 * @throws CryptoException if the verification or decryption failed.
	 */
	static byte[] decryptSealed(byte[] cipher, PublicKey pk, PrivateKey sk) throws CryptoException {
		Objects.requireNonNull(cipher, "cipher");
		Objects.requireNonNull(pk, "pk");
		Objects.requireNonNull(sk, "sk");
		byte[] plain = provider().boxSealOpen(cipher, pk, sk);
		if (plain == null)
			throw new CryptoException("Sealed-box decryption failed: invalid ciphertext or authentication failure");

		return plain;
	}

	/**
	 * Encrypt a message with this precomputed box.
	 *
	 * @param message the message to encrypt. Must not be null.
	 * @param nonce a unique nonce object. Must not be null.
	 * @return the encrypted data.
	 * @throws NullPointerException if {@code message} or {@code nonce} is null.
	 */
	default byte[] encrypt(byte[] message, Nonce nonce) {
		Objects.requireNonNull(message, "message");
		Objects.requireNonNull(nonce, "nonce");
		return provider().boxEncrypt(message, nonce, this);
	}

	/**
	 * Decrypt a message with this precomputed box.
	 *
	 * @param cipher the cipher text to decrypt. Must not be null.
	 * @param nonce the nonce that was used for encryption. Must not be null.
	 * @return the decrypted data.
	 * @throws NullPointerException if {@code cipher} or {@code nonce} is null.
	 * @throws CryptoException if the verification or decryption failed.
	 */
	default byte[] decrypt(byte[] cipher, Nonce nonce) throws CryptoException {
		Objects.requireNonNull(cipher, "cipher");
		Objects.requireNonNull(nonce, "nonce");
		byte[] plain = provider().boxDecrypt(cipher, nonce, this);
		if (plain == null)
			throw new CryptoException("Decryption failed: invalid ciphertext or authentication failure");

		return plain;
	}

	@Override
	void close();

	@Override
	void destroy();

	@Override
	boolean isDestroyed();

	private static CryptoProvider provider() {
		return CryptoProviders.getDefault();
	}
}