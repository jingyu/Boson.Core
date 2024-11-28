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

import java.util.Arrays;

import javax.security.auth.Destroyable;

import org.apache.tuweni.crypto.sodium.Signature.Seed;
import org.apache.tuweni.crypto.sodium.Sodium;

/**
 * Public-key(Ed25519) signatures.
 */
public class Signature {
	/**
	 * The signing(Ed25519) public key object.
	 */
	public static class PublicKey implements Destroyable {
		/**
		 * The number of bytes used to represent a public key.
		 */
		public static final int BYTES = org.apache.tuweni.crypto.sodium.Signature.PublicKey.length();

		private org.apache.tuweni.crypto.sodium.Signature.PublicKey key;
		private byte[] bytes;

		private PublicKey(org.apache.tuweni.crypto.sodium.Signature.PublicKey key) {
			this.key = key;
		}

		/**
		 *  Create a PublicKey from an array of bytes. The byte array must be of
		 *  length {@link #BYTES}.
		 *
		 * @param key the bytes for the public key.
		 * @return the created public key object.
		 */
		public static PublicKey fromBytes(byte[] key) {
			// No SodiumException raised
			return new PublicKey(org.apache.tuweni.crypto.sodium.Signature.PublicKey.fromBytes(key));
		}

		org.apache.tuweni.crypto.sodium.Signature.PublicKey raw() {
			return key;
		}

		/**
		 * Provides the bytes of this key.
		 *
		 * @return the bytes of this key.
		 */
		public byte[] bytes() {
			if (bytes == null)
				bytes = key.bytesArray();

			return bytes;
		}

		/**
		 * Verifies the signature of a message.
		 *
		 * @param message the message to verify.
		 * @param signature the signature of the message.
		 * @return true if the signature matches the message according to this public key.
		 */
		public boolean verify(byte[] message, byte[] signature) {
			return Signature.verify(message, signature, this);
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
			return key.hashCode() + 0x62; // + 'b' - Boson
		}

		/**
		 * Destroy this PublicKey object.
		 *
		 * Sensitive information associated with this object is destroyed or cleared.
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
		 * Determine if this object has been destroyed.
		 *
		 * @return true if this object has been destroyed, false otherwise.
		 */
		@Override
		public boolean isDestroyed() {
			return key.isDestroyed();
		}
	}

	/**
	 * The signing(Ed25519) private key object.
	 */
	public static class PrivateKey implements Destroyable {
		/**
		 * The number of bytes used to represent a public key.
		 */
		public static final int BYTES = org.apache.tuweni.crypto.sodium.Signature.SecretKey.length();

		private org.apache.tuweni.crypto.sodium.Signature.SecretKey key;
		private byte[] bytes;

		private PrivateKey(org.apache.tuweni.crypto.sodium.Signature.SecretKey key) {
			this.key = key;
		}

		/**
		 * Create a PivateKey from an array of bytes. The byte array
		 * must be of length {@link #BYTES}.
		 *
		 * @param key the bytes for the secret key.
		 * @return the created private key object.
		 */
		public static PrivateKey fromBytes(byte[] key) {
			// no SodiumException raised
			return new PrivateKey(org.apache.tuweni.crypto.sodium.Signature.SecretKey.fromBytes(key));
		}

		public static PrivateKey fromSeed(byte[] seed) {
			return new PrivateKey(org.apache.tuweni.crypto.sodium.Signature.SecretKey.fromSeed(Seed.fromBytes(seed)));
		}

		org.apache.tuweni.crypto.sodium.Signature.SecretKey raw() {
			return key;
		}

		/**
		 * Provides the bytes of this key.
		 *
		 * @return the bytes of this key.
		 */
		public byte[] bytes() {
			if (bytes == null)
				bytes = key.bytesArray();

			return bytes;
		}

		/**
		 * Signs a message with this private key.
		 *
		 * @param message the message to sign.
		 * @return the signature of the message.
		 */
		public byte[] sign(byte[] message) {
			return Signature.sign(message, this);
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
			return key.hashCode() + 0x62; // + 'b' - Boson
		}

		/**
		 * Destroy this private key.
		 *
		 * Sensitive information associated with this private key
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
		 * Determine if this object has been destroyed.
		 *
		 * @return true if this object has been destroyed, false otherwise.
		 */
		@Override
		public boolean isDestroyed() {
			return key.isDestroyed();
		}
	}

	/**
	 * The signing(Ed25519) key pair.
	 */
	public static class KeyPair {
		/**
		 * The seed length in bytes.
		 */
		public int SEED_BYTES = Seed.length();

		private org.apache.tuweni.crypto.sodium.Signature.KeyPair keyPair;
		private PublicKey pk;
		private PrivateKey sk;

		private KeyPair(org.apache.tuweni.crypto.sodium.Signature.KeyPair keyPair) {
			this.keyPair = keyPair;
		}

		/**
		 * Create a KeyPair from an array of private key bytes. The byte array must be of
		 * length {@link PrivateKey#BYTES}.
		 *
		 * @param privateKey the private key bytes.
		 * @return the created key pair object.
		 */
		public static KeyPair fromPrivateKey(byte[] privateKey) {
			org.apache.tuweni.crypto.sodium.Signature.SecretKey sk = org.apache.tuweni.crypto.sodium.Signature.SecretKey.fromBytes(privateKey);
			// Normally, should never raise Exception
			return new KeyPair(org.apache.tuweni.crypto.sodium.Signature.KeyPair.forSecretKey(sk));
		}

		/**
		 * Create a KeyPair from a private key object.
		 *
		 * @param privateKey the private key object.
		 * @return the created key pair object.
		 */
		public static KeyPair fromPrivateKey(PrivateKey privateKey) {
			// Normally, should never raise Exception
			return new KeyPair(org.apache.tuweni.crypto.sodium.Signature.KeyPair.forSecretKey(privateKey.raw()));
		}

		/**
		 * Generate a new key using a seed. The seed byte array must be of
		 * length {@link #SEED_BYTES}.
		 *
		 * @param seed the seed bytes.
		 * @return the created key pair object.
		 */
		public static KeyPair fromSeed(byte[] seed) {
			org.apache.tuweni.crypto.sodium.Signature.Seed sd = org.apache.tuweni.crypto.sodium.Signature.Seed.fromBytes(seed);
			// Normally, should never raise Exception
			return new KeyPair(org.apache.tuweni.crypto.sodium.Signature.KeyPair.fromSeed(sd));
		}

		/**
		 * Generate a new key using a random generator.
		 *
		 * @return a randomly generated key pair.
		 */
		public static KeyPair random() {
			// Normally, should never raise Exception
			return new KeyPair(org.apache.tuweni.crypto.sodium.Signature.KeyPair.random());
		}

		org.apache.tuweni.crypto.sodium.Signature.KeyPair raw() {
			return keyPair;
		}

		/**
		 * Gets the public key the this key pair.
		 *
		 * @return the public key of the key pair.
		 */
		public PublicKey publicKey() {
			if (pk == null)
				pk = new PublicKey(keyPair.publicKey());

			return pk;
		}

		/**
		 * Gets the private key the this key pair.
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
			return keyPair.hashCode() + 0x62; // + 'b' - Boson key pair
		}
	}

	// Can not access internal method
	// should be (int)Sodium.crypto_sign_bytes();
	/**
	 * The number of bytes used to represent a signature.
	 */
	public static final int BYTES = 64;

	/**
	 * Signs a message with a given key.
	 *
	 * @param message the message to sign.
	 * @param key the private key to sign the message with.
	 * @return the signature of the message.
	 */
	public static byte[] sign(byte[] message, PrivateKey key) {
		// Normally, should never raise SodiumException
		return org.apache.tuweni.crypto.sodium.Signature.signDetached(message, key.raw());
	}

	/**
	 * Verifies the signature of a message.
	 *
	 * @param message the message to verify.
	 * @param signature the signature of the message.
	 * @param key the public key to verify the message with.
	 * @return true if the signature matches the message according to this public key.
	 */
	public static boolean verify(byte[] message, byte[] signature, PublicKey key) {
		// Normally, should never raise SodiumException
		return org.apache.tuweni.crypto.sodium.Signature.verifyDetached(message, signature, key.raw());
	}

	static {
		if (!Sodium.isAvailable()) {
			throw new RuntimeException("Sodium native library is not available!");
		}
	}
}
