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

import java.util.Arrays;

import javax.security.auth.Destroyable;

import org.apache.tuweni.crypto.sodium.Box;
import org.apache.tuweni.crypto.sodium.Box.Seed;
import org.apache.tuweni.crypto.sodium.Sodium;

/**
 * Public-key(Curve 25519) authenticated encryption.
 */
public class CryptoBox implements AutoCloseable {
	/**
	 * The Message Authentication Code size of the encrypted data in bytes.
	 */
	public static final int MAC_BYTES = 16;

	private Box box;

	/**
	 * The crypto box public key object.
	 */
	public static class PublicKey implements Destroyable {
		/**
		 * The number of bytes used to represent a public key.
		 */
		public static final int BYTES = Box.PublicKey.length();

		private Box.PublicKey key;
		private byte[] bytes;

		private PublicKey(Box.PublicKey key) {
			this.key = key;
		}

		/**
		 * Create a {@link PublicKey} from an array of bytes.
		 * The byte array must be of length {@link #BYTES}.
		 *
		 * @param key the bytes for the public key.
		 * @return the public key object.
		 */
		public static PublicKey fromBytes(byte[] key) {
			// no SodiumException raised
			return new PublicKey(Box.PublicKey.fromBytes(key));
		}

		/**
		 * Transforms the Ed25519 signature public key to a Curve25519 public key. See
		 * https://libsodium.gitbook.io/doc/advanced/ed25519-curve25519
		 *
		 * @param key the signature public key.
		 * @return the public key as a Curve25519 public key.
		 */
		public static PublicKey fromSignatureKey(Signature.PublicKey key) {
			return new PublicKey(Box.PublicKey.forSignaturePublicKey(key.raw()));
		}

		Box.PublicKey raw() {
			return key;
		}

		/**
		 * Get the raw bytes of this key.
		 *
		 * @return the raw bytes of this key.
		 */
		public byte[] bytes() {
			if (bytes == null)
				bytes = key.bytesArray();

			return bytes;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this)
				return true;

			if (obj instanceof PublicKey) {
				PublicKey other = (PublicKey)obj;
				return key.equals(other.key);
			}

			return false;
		}

		@Override
		public int hashCode() {
			return key.hashCode() + 0x63; // + 'c' - Boson
		}

		/**
		 * Destroy this {@code PublicKey}.
		 *
		 * Sensitive information associated with this {@code PublicKey}
		 * is destroyed or cleared.
		 */
		@Override
		public void destroy() {
			if (!key.isDestroyed()) {
				key.destroy();

				if (bytes != null) {
					Arrays.fill(bytes, (byte)0);
					bytes = null;
				}
			}
		}

		/**
		 * Determine if this {@code PublicKey} has been destroyed.
		 *
		 * @return true if this {@code PublicKey} has been destroyed,
		 *		 false otherwise.
		 */
		@Override
		public boolean isDestroyed() {
			return key.isDestroyed();
		}
	}

	/**
	 * The crypto box private key object.
	 */
	public static class PrivateKey implements Destroyable {
		/**
		 * The number of bytes used to represent a public key.
		 */
		public static final int BYTES = Box.SecretKey.length();

		private Box.SecretKey key;
		private byte[] bytes;

		private PrivateKey(Box.SecretKey key) {
			this.key = key;
		}

		/**
		 * Create a {@link PrivateKey} from an array of bytes.
		 * The byte array must be of length {@link #BYTES}.
		 *
		 * @param key the bytes for the private key.
		 * @return the private key.
		 */
		public static PrivateKey fromBytes(byte[] key) {
			// no SodiumException raised
			return new PrivateKey(Box.SecretKey.fromBytes(key));
		}

		/**
		 * Transforms the Ed25519 private key to a Curve25519 private key. See
		 * https://libsodium.gitbook.io/doc/advanced/ed25519-curve25519
		 *
		 * @param key the signature secret key
		 * @return the secret key as a Curve25519 private key
		 */
		public static PrivateKey fromSignatureKey(Signature.PrivateKey key) {
			return new PrivateKey(Box.SecretKey.forSignatureSecretKey(key.raw()));
		}

		Box.SecretKey raw() {
			return key;
		}

		/**
		 * Get the raw bytes of this key.
		 *
		 * @return the raw bytes of this key.
		 */
		public byte[] bytes() {
			if (bytes == null)
				bytes = key.bytesArray();

			return bytes;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this)
				return true;

			if (obj instanceof PrivateKey) {
				PrivateKey other = (PrivateKey)obj;
				return key.equals(other.key);
			}

			return false;
		}

		@Override
		public int hashCode() {
			return key.hashCode() + 0x63; // + 'c' - Boson
		}

		/**
		 * Destroy this {@code PrivateKey}.
		 *
		 * Sensitive information associated with this {@code PrivateKey}
		 * is destroyed or cleared.
		 */
		@Override
		public void destroy() {
			if (!key.isDestroyed()) {
				key.destroy();

				if (bytes != null) {
					Arrays.fill(bytes, (byte)0);
					bytes = null;
				}
			}
		}

		/**
		 * Determine if this {@code PrivateKey} has been destroyed.
		 *
		 * @return true if this {@code PrivateKey} has been destroyed,
		 *		 false otherwise.
		 */
		@Override
		public boolean isDestroyed() {
			return key.isDestroyed();
		}
	}

	/**
	 * The crypto box key pair.
	 */
	public static class KeyPair {
		/**
		 * The seed length in bytes.
		 */
		public int SEED_BYTES = Seed.length();

		private Box.KeyPair keyPair;
		private PublicKey pk;
		private PrivateKey sk;

		private KeyPair(Box.KeyPair keyPair) {
			this.keyPair = keyPair;
		}

		/**
		 * Create a {@link KeyPair} from an array of private key bytes.
		 *
		 * @param privateKey the raw private key.
		 * @return the key pair object.
		 */
		public static KeyPair fromPrivateKey(byte[] privateKey) {
			Box.SecretKey sk = Box.SecretKey.fromBytes(privateKey);
			// Normally, should never raise Exception
			return new KeyPair(Box.KeyPair.forSecretKey(sk));
		}

		/**
		 * Create a {@link KeyPair} from a {@link PrivateKey} object.
		 *
		 * @param key the private key object.
		 * @return the key pair object.
		 */
		public static KeyPair fromPrivateKey(PrivateKey key) {
			// Normally, should never raise Exception
			return new KeyPair(Box.KeyPair.forSecretKey(key.raw()));
		}

		/**
		 * Generate a new key pair using a seed.
		 * The seed must be of length {@link #SEED_BYTES}.
		 *
		 * @param seed the seed bytes.
		 * @return the new generated key pair.
		 */
		public static KeyPair fromSeed(byte[] seed) {
			Box.Seed sd = Box.Seed.fromBytes(seed);
			// Normally, should never raise Exception
			return new KeyPair(Box.KeyPair.fromSeed(sd));
		}

		/**
		 * Converts signature key pair (Ed25519) to a box key pair (Curve25519)
		 * so that the same key pair can be used both for authenticated encryption
		 * and for signatures. See
		 * https://libsodium.gitbook.io/doc/advanced/ed25519-curve25519
		 *
		 * @param keyPair A {@link Signature.KeyPair}.
		 * @return the new generated box key pair.
		 */
		public static KeyPair fromSignatureKeyPair(Signature.KeyPair keyPair)  {
			// Normally, should never raise Exception
			return new KeyPair(Box.KeyPair.forSignatureKeyPair(keyPair.raw()));
		}

		/**
		 * Generate a new key pair using a random generator.
		 *
		 * @return a randomly generated key pair.
		 */
		public static KeyPair random() {
			// Normally, should never raise Exception
			return new KeyPair(Box.KeyPair.random());
		}

		Box.KeyPair raw() {
			return keyPair;
		}

		/**
		 * Gets the public key of this key pair.
		 *
		 * @return the public key of the key pair.
		 */
		public PublicKey publicKey() {
			if (pk == null)
				pk = new PublicKey(keyPair.publicKey());

			return pk;
		}

		/**
		 * Gets the private key of this key pair.
		 *
		 * @return the private key of the key pair.
		 */
		public PrivateKey privateKey() {
			if (sk == null)
				sk = new PrivateKey(keyPair.secretKey());

			return sk;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this)
				return true;

			if (obj instanceof KeyPair) {
				KeyPair other = (KeyPair)obj;
				return keyPair.equals(other.keyPair);
			}

			return false;
		}

		@Override
		public int hashCode() {
			return keyPair.hashCode() + 0x63; // + 'c' - Boson key pair
		}
	}

	/**
	 * The nonce object for the crypto box encryption.
	 */
	public static class Nonce {
		/**
		 * The number of bytes used to represent a public key.
		 */
		public static final int BYTES = Box.Nonce.length();

		private Box.Nonce nonce;
		private byte[] bytes;

		private Nonce(Box.Nonce nonce) {
			this.nonce = nonce;
		}

		/**
		 * Create a Nonce object from an array of bytes.
		 * The byte array must be of length {@link #BYTES}.
		 *
		 * @param nonce the bytes for the nonce.
		 * @return a nonce object based on these bytes.
		 */
		public static Nonce fromBytes(byte[] nonce) {
			return new Nonce(Box.Nonce.fromBytes(nonce));
		}

		/**
		 * Generate a random Nonce object.
		 *
		 * @return a randomly generated nonce.
		 */
		public static Nonce random() {
			return new Nonce(Box.Nonce.random());
		}

		/**
		 * Create a zero Nonce object.
		 *
		 * @return a zero nonce object.
		 */
		public static Nonce zero() {
			return new Nonce(Box.Nonce.zero());
		}

		Box.Nonce raw() {
			return nonce;
		}

		/**
		 * Increment this nonce.
		 *
		 * <p>
		 * Note that this is not synchronized. If multiple threads are creating
		 * encrypted messages and incrementing this nonce, then external synchronization
		 * is required to ensure no two encrypt operations use the same nonce.
		 *
		 * @return A new nonce object.
		 */
		public Nonce increment() {
			return new Nonce(nonce.increment());
		}

		/**
		 * Provides the bytes of this nonce object.
		 *
		 * @return The bytes of this nonce.
		 */
		public byte[] bytes() {
			if (bytes == null)
				bytes = nonce.bytesArray();

			return bytes;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this)
				return true;

			if (obj instanceof Nonce) {
				Nonce other = (Nonce)obj;
				return nonce.equals(other.nonce);
			}

			return false;
		}

		@Override
		public int hashCode() {
			return nonce.hashCode() + 0x63; // + 'c' - Boson
		}
	}

	private CryptoBox(Box box) {
		this.box = box;
	}

	/**
	 * Precompute the shared key for a given sender and receiver.
	 *
	 * <p>
	 * Note that the returned instance of CryptoBox should be closed using
	 * {@link #close()} (or try-with-resources) to ensure timely release of the shared key,
	 * which is held in native memory.
	 *
	 * @param pk the public key of the receiver.
	 * @param sk the secret key of the sender.
	 * @return a precomputed crypto box instance.
	 */
	public static CryptoBox fromKeys(PublicKey pk, PrivateKey sk) {
		return new CryptoBox(Box.forKeys(pk.raw(), sk.raw()));
	}

	/**
	 * Encrypt a message with this precomputed box.
	 *
	 * @param message the message to encrypt.
	 * @param nonce a unique nonce object.
	 * @return the encrypted data.
	 */
	public byte[] encrypt(byte[] message, Nonce nonce) {
		return box.encrypt(message, nonce.raw());
	}

	/**
	 * Encrypt a message with the given keys
	 * @param message the message to encrypt.
	 * @param receiver the public key of the receiver.
	 * @param sender the private key of the sender.
	 * @param nonce a unique nonce object.
	 * @return the encrypted data.
	 */
	public static byte[] encrypt(byte[] message, PublicKey receiver, PrivateKey sender, Nonce nonce) {
		return Box.encrypt(message, receiver.raw(), sender.raw(), nonce.raw());
	}

	/**
	 * Encrypt a sealed message for a given key.
	 *
	 * Sealed boxes are designed to anonymously send messages to a recipient given its public key.
	 * Only the recipient can decrypt these messages, using its private key. While
	 * the recipient can verify the integrity of the message, it cannot verify
	 * the identity of the sender.
	 *
	 * A message is encrypted using an ephemeral key pair, whose secret part is destroyed
	 * right after the encryption process. Without knowing the secret key used for a given
	 * message, the sender cannot decrypt its own message later. And without additional data,
	 * a message cannot be correlated with the identity of its sender.
	 *
	 * @param message the message to encrypt.
	 * @param receiver the public key of the receiver.
	 * @return the encrypted data.
	 */
	public static byte[] encryptSealed(byte[] message, PublicKey receiver) {
		return Box.encryptSealed(message, receiver.raw());
	}

	/**
	 * Decrypt a message with this precomputed box.
	 *
	 * @param cipher the cipher text to decrypt.
	 * @param nonce the nonce that was used for encryption.
	 * @return The decrypted data.
	 * @throws CryptoException if the verification or decryption failed.
	 */
	public byte[] decrypt(byte[] cipher, Nonce nonce) throws CryptoException {
		byte[] plain = box.decrypt(cipher, nonce.raw());
		if (plain == null)
			throw new CryptoException("crypto_box_open_easy_afternm: failed");

		return plain;
	}

	/**
	 * Decrypt a message using the given keys.
	 *
	 * @param cipher the cipher text to decrypt.
	 * @param sender the public key of the sender.
	 * @param receiver the private key of the receiver.
	 * @param nonce the nonce that was used for encryption.
	 * @return the decrypted data.
	 * @throws CryptoException if the verification or decryption failed.
	 */
	public static byte[] decrypt(byte[] cipher, PublicKey sender, PrivateKey receiver, Nonce nonce) throws CryptoException {
		byte[] plain = Box.decrypt(cipher, sender.raw(), receiver.raw(), nonce.raw());
		if (plain == null)
			throw new CryptoException("crypto_box_open_easy: failed");

		return plain;
	}

	/**
	 * Decrypt a sealed message using the given keys.
	 *
	 * @param cipher the cipher text to decrypt.
	 * @param pk the public key of the sender.
	 * @param sk the private key of the receiver.
	 * @return the decrypted data.
	 * @throws CryptoException if the verification or decryption failed.
	 */
	public static byte[] decryptSealed(byte[] cipher, PublicKey pk, PrivateKey sk) throws CryptoException {
		byte[] plain = Box.decryptSealed(cipher, pk.raw(), sk.raw());
		if (plain == null)
			throw new CryptoException("crypto_box_seal_open: failed");

		return plain;
	}

	@Override
	public void close() {
		box.close();
	}

	@Override
	protected void finalize() {
		close();
	}

	static {
		if (!Sodium.isAvailable()) {
			throw new RuntimeException("Sodium native library is not available!");
		}
	}
}
