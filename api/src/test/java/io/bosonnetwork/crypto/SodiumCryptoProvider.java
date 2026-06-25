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

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.apache.tuweni.crypto.sodium.Box;
import org.apache.tuweni.crypto.sodium.KeyDerivation;
import org.apache.tuweni.crypto.sodium.PasswordHash;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Test-only {@link CryptoProvider} backed by libsodium through Apache Tuweni. It exists solely
 * so the crypto compatibility test can verify, primitive by primitive, that the production
 * {@link BouncyCastleCryptoProvider} stays byte-for-byte compatible with libsodium.
 * <p>
 * Key, nonce and precomputed-box objects wrap the corresponding native Tuweni handles directly:
 * {@link #boxBeforeNm} returns a {@link CryptoBox} backed by a real Tuweni {@link Box} (from
 * {@link Box#forKeys}) whose native shared key is released on {@code close()}. A foreign key
 * object created by another provider is accepted by reconstructing the Tuweni handle from its
 * raw bytes.
 */
@NullMarked
public class SodiumCryptoProvider implements CryptoProvider {
	@Override
	public String name() {
		return "libsodium";
	}

	private static class Ed25519SecretKey implements Signature.PrivateKey {
		private final org.apache.tuweni.crypto.sodium.Signature.SecretKey key;

		private Ed25519SecretKey(org.apache.tuweni.crypto.sodium.Signature.SecretKey key) {
			this.key = key;
		}

		@Override
		public byte[] seed() {
			// guard before touching native memory: bytesArray() after destroy() is a use-after-free
			if (isDestroyed())
				throw new IllegalStateException("Private key has been destroyed");
			return Arrays.copyOfRange(key.bytesArray(), 0, SIGN_SEED_BYTES);
		}

		@Override
		public byte[] bytes() {
			if (isDestroyed())
				throw new IllegalStateException("Private key has been destroyed");
			return key.bytesArray();
		}

		@Override
		public void destroy() {
			key.destroy();
		}

		@Override
		public boolean isDestroyed() {
			return key.isDestroyed();
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this)
				return true;
			if (!(obj instanceof Signature.PrivateKey that) || isDestroyed() || that.isDestroyed())
				return false;
			return Arrays.equals(bytes(), that.bytes());
		}

		@Override
		public int hashCode() {
			return isDestroyed() ? 0 : Arrays.hashCode(bytes());
		}
	}

	private static class Ed25519PublicKey implements Signature.PublicKey {
		private final org.apache.tuweni.crypto.sodium.Signature.PublicKey key;

		private Ed25519PublicKey(org.apache.tuweni.crypto.sodium.Signature.PublicKey key) {
			this.key = key;
		}

		@Override
		public byte[] bytes() {
			if (isDestroyed())
				throw new IllegalStateException("Public key has been destroyed");
			return key.bytesArray();
		}

		@Override
		public void destroy() {
			key.destroy();
		}

		@Override
		public boolean isDestroyed() {
			return key.isDestroyed();
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this)
				return true;
			if (!(obj instanceof Signature.PublicKey that) || isDestroyed() || that.isDestroyed())
				return false;
			return Arrays.equals(bytes(), that.bytes());
		}

		@Override
		public int hashCode() {
			return isDestroyed() ? 0 : Arrays.hashCode(bytes());
		}
	}

	@Override
	public Signature.PrivateKey ed25519SecretKeyFromSeed(byte[] seed) {
		// Use KeyPair.fromSeed to obtain the full 64-byte secret key (seed || public key);
		// SecretKey.fromSeed alone does not expand it, which corrupts later sk_to_pk reads.
		org.apache.tuweni.crypto.sodium.Signature.KeyPair kp =
				org.apache.tuweni.crypto.sodium.Signature.KeyPair.fromSeed(
						org.apache.tuweni.crypto.sodium.Signature.Seed.fromBytes(seed));
		return new Ed25519SecretKey(kp.secretKey());
	}

	@Override
	public Signature.PrivateKey ed25519SecretKeyFromBytes(byte[] key) {
		org.apache.tuweni.crypto.sodium.Signature.SecretKey sk =
				org.apache.tuweni.crypto.sodium.Signature.SecretKey.fromBytes(key);
		return new Ed25519SecretKey(sk);
	}

	private static org.apache.tuweni.crypto.sodium.Signature.SecretKey keyOf(Signature.PrivateKey secretKey) {
		return secretKey instanceof Ed25519SecretKey k ? k.key :
				org.apache.tuweni.crypto.sodium.Signature.SecretKey.fromBytes(secretKey.bytes());
	}

	private static org.apache.tuweni.crypto.sodium.Signature.PublicKey keyOf(Signature.PublicKey publicKey) {
		return publicKey instanceof Ed25519PublicKey k ? k.key :
				org.apache.tuweni.crypto.sodium.Signature.PublicKey.fromBytes(publicKey.bytes());
	}

	@Override
	public Signature.PublicKey ed25519PublicKeyFromSecretKey(Signature.PrivateKey secretKey) {
		org.apache.tuweni.crypto.sodium.Signature.PublicKey pk =
				org.apache.tuweni.crypto.sodium.Signature.KeyPair.forSecretKey(keyOf(secretKey)).publicKey();
		return new Ed25519PublicKey(pk);
	}

	@Override
	public Signature.PublicKey ed25519PublicKeyFromBytes(byte[] key) {
		org.apache.tuweni.crypto.sodium.Signature.PublicKey pk =
				org.apache.tuweni.crypto.sodium.Signature.PublicKey.fromBytes(key);
		return new Ed25519PublicKey(pk);
	}

	@Override
	public byte[] ed25519Sign(byte[] message, Signature.PrivateKey secretKey) {
		return org.apache.tuweni.crypto.sodium.Signature.signDetached(message, keyOf(secretKey));
	}

	@Override
	public boolean ed25519Verify(byte[] message, byte[] signature, Signature.PublicKey publicKey) {
		return org.apache.tuweni.crypto.sodium.Signature.verifyDetached(message, signature, keyOf(publicKey));
	}

	@Override
	public byte[] kdfDeriveFromKey(byte[] masterKey, long subKeyId, byte[] context, int subKeyLength) {
		return KeyDerivation.MasterKey.fromBytes(masterKey).deriveKeyArray(subKeyLength, subKeyId, context);
	}

	private static class SodiumBoxPublicKey implements CryptoBox.PublicKey {
		private final Box.PublicKey key;

		private SodiumBoxPublicKey(Box.PublicKey key) {
			this.key = key;
		}

		@Override
		public byte[] bytes() {
			if (isDestroyed())
				throw new IllegalStateException("Public key has been destroyed");
			return key.bytesArray();
		}

		@Override
		public void destroy() {
			key.destroy();
		}

		@Override
		public boolean isDestroyed() {
			return key.isDestroyed();
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this)
				return true;
			if (!(obj instanceof CryptoBox.PublicKey that) || isDestroyed() || that.isDestroyed())
				return false;
			return Arrays.equals(bytes(), that.bytes());
		}

		@Override
		public int hashCode() {
			return isDestroyed() ? 0 : Arrays.hashCode(bytes());
		}
	}

	private static class SodiumBoxSecretKey implements CryptoBox.PrivateKey {
		private final Box.SecretKey key;

		private SodiumBoxSecretKey(Box.SecretKey key) {
			this.key = key;
		}

		@Override
		public byte[] bytes() {
			if (isDestroyed())
				throw new IllegalStateException("Private key has been destroyed");
			return key.bytesArray();
		}

		@Override
		public void destroy() {
			key.destroy();
		}

		@Override
		public boolean isDestroyed() {
			return key.isDestroyed();
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this)
				return true;
			if (!(obj instanceof CryptoBox.PrivateKey that) || isDestroyed() || that.isDestroyed())
				return false;
			return Arrays.equals(bytes(), that.bytes());
		}

		@Override
		public int hashCode() {
			return isDestroyed() ? 0 : Arrays.hashCode(bytes());
		}
	}

	private static class SodiumBoxNonce implements CryptoBox.Nonce {
		private final Box.Nonce nonce;

		private SodiumBoxNonce(Box.Nonce nonce) {
			this.nonce = nonce;
		}

		@Override
		public CryptoBox.Nonce increment() {
			return new SodiumBoxNonce(nonce.increment());
		}

		@Override
		public byte[] bytes() {
			return nonce.bytesArray();
		}

		@Override
		public int hashCode() {
			return nonce.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this)
				return true;
			if (!(obj instanceof CryptoBox.Nonce that))
				return false;
			return Arrays.equals(bytes(), that.bytes());
		}
	}

	// Holds the real precomputed Tuweni Box (crypto_box_beforenm), released on close().
	private static class SodiumCryptoBox implements CryptoBox {
		private final Box box;
		private boolean destroyed = false;

		private SodiumCryptoBox(Box box) {
			this.box = box;
		}

		@Override
		public byte[] encrypt(byte[] message, CryptoBox.Nonce nonce) {
			return box.encrypt(message, nonceOf(nonce));
		}

		@Override
		public byte[] decrypt(byte[] cipher, CryptoBox.Nonce nonce) throws CryptoException {
			byte[] plain = box.decrypt(cipher, nonceOf(nonce));
			if (plain == null)
				throw new CryptoException("Decryption failed: invalid ciphertext or authentication failure");
			return plain;
		}

		@Override
		public void close() {
			destroy();
		}

		@Override
		public void destroy() {
			if (!destroyed) {
				box.close();
				destroyed = true;
			}
		}

		@Override
		public boolean isDestroyed() {
			return destroyed;
		}
	}

	private static Box.PublicKey keyOf(CryptoBox.PublicKey publicKey) {
		return publicKey instanceof SodiumBoxPublicKey k ? k.key : Box.PublicKey.fromBytes(publicKey.bytes());
	}

	private static Box.SecretKey keyOf(CryptoBox.PrivateKey secretKey) {
		return secretKey instanceof SodiumBoxSecretKey k ? k.key : Box.SecretKey.fromBytes(secretKey.bytes());
	}

	@Override
	public CryptoBox.PublicKey signPublicKeyToBoxPublicKey(Signature.PublicKey publicKey) {
		return new SodiumBoxPublicKey(Box.PublicKey.forSignaturePublicKey(keyOf(publicKey)));
	}

	@Override
	public CryptoBox.PrivateKey signSecretKeyToBoxSecretKey(Signature.PrivateKey secretKey) {
		return new SodiumBoxSecretKey(Box.SecretKey.forSignatureSecretKey(keyOf(secretKey)));
	}

	@Override
	public CryptoBox.PrivateKey boxSecretKeyFromSeed(byte[] seed) {
		return new SodiumBoxSecretKey(Box.KeyPair.fromSeed(Box.Seed.fromBytes(seed)).secretKey());
	}

	@Override
	public CryptoBox.PublicKey boxPublicKeyFromBytes(byte[] bytes) {
		return new SodiumBoxPublicKey(Box.PublicKey.fromBytes(bytes));
	}

	@Override
	public CryptoBox.PrivateKey boxSecretKeyFromBytes(byte[] bytes) {
		return new SodiumBoxSecretKey(Box.SecretKey.fromBytes(bytes));
	}

	@Override
	public CryptoBox.PublicKey boxPublicKeyFromSecretKey(CryptoBox.PrivateKey secretKey) {
		return new SodiumBoxPublicKey(Box.KeyPair.forSecretKey(keyOf(secretKey)).publicKey());
	}

	@Override
	public CryptoBox.Nonce boxNonceFromBytes(byte[] bytes) {
		return new SodiumBoxNonce(Box.Nonce.fromBytes(bytes));
	}

	@Override
	public CryptoBox boxBeforeNm(CryptoBox.PublicKey publicKey, CryptoBox.PrivateKey secretKey) {
		return new SodiumCryptoBox(Box.forKeys(keyOf(publicKey), keyOf(secretKey)));
	}

	private static Box.Nonce nonceOf(CryptoBox.Nonce nonce) {
		return nonce instanceof SodiumBoxNonce n ? n.nonce : Box.Nonce.fromBytes(nonce.bytes());
	}

	@Override
	public byte[] boxEncrypt(byte[] message, CryptoBox.Nonce nonce, CryptoBox.PublicKey publicKey, CryptoBox.PrivateKey secretKey) {
		return Box.encrypt(message, keyOf(publicKey), keyOf(secretKey), nonceOf(nonce));
	}

	@Override
	public byte @Nullable [] boxDecrypt(byte[] cipher, CryptoBox.Nonce nonce, CryptoBox.PublicKey publicKey, CryptoBox.PrivateKey secretKey) {
		return Box.decrypt(cipher, keyOf(publicKey), keyOf(secretKey), nonceOf(nonce));
	}

	@Override
	public byte[] boxSeal(byte[] message, CryptoBox.PublicKey publicKey) {
		return Box.encryptSealed(message, keyOf(publicKey));
	}

	@Override
	public byte @Nullable [] boxSealOpen(byte[] cipher, CryptoBox.PublicKey publicKey, CryptoBox.PrivateKey secretKey) {
		return Box.decryptSealed(cipher, keyOf(publicKey), keyOf(secretKey));
	}

	@Override
	public byte[] pwHash(byte[] password, int length, byte[] salt, long opsLimit, long memLimit, int algorithm) {
		return PasswordHash.hash(password, length, PasswordHash.Salt.fromBytes(salt), opsLimit, memLimit,
				algorithm == PWHASH_ALG_ARGON2I13 ? PasswordHash.Algorithm.argon2i13()
						: PasswordHash.Algorithm.argon2id13());
	}

	@Override
	public String pwHashString(byte[] password, long opsLimit, long memLimit, int algorithm) {
		return PasswordHash.hash(new String(password, StandardCharsets.UTF_8), opsLimit, memLimit);
	}

	@Override
	public boolean pwHashVerify(String hash, byte[] password) {
		return PasswordHash.verify(hash, new String(password, StandardCharsets.UTF_8));
	}

	@Override
	public boolean pwHashNeedsRehash(String hash, long opsLimit, long memLimit) {
		// Honour the requested limits (matches libsodium crypto_pwhash_str_needs_rehash);
		// the no-arg needsRehash(hash) would compare against the MODERATE defaults instead.
		return PasswordHash.needsRehash(hash, opsLimit, memLimit);
	}
}