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

import static org.bouncycastle.util.Arrays.constantTimeAreEqual;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.engines.XSalsa20Engine;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.macs.Poly1305;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.math.ec.rfc7748.X25519;
import org.jspecify.annotations.Nullable;

/**
 * Pure-Java {@link CryptoProvider} backed by Bouncy Castle. This is the default Boson crypto
 * backend; it has no native dependency and runs on the JVM and Android alike.
 * <p>
 * Every construction is byte-for-byte compatible with libsodium. Where Bouncy Castle does not
 * expose a libsodium building block directly, it is implemented here against verified test
 * vectors (see the crypto compatibility test): the HSalsa20 core used by {@code crypto_box}
 * key derivation, the Ed25519 to Curve25519 birational map, the NaCl secretbox layout, and the
 * Argon2 PHC string format produced by {@code crypto_pwhash_str}.
 */
public class BouncyCastleCryptoProvider implements CryptoProvider {
	// "expand 32-byte k" - the Salsa20/HSalsa20 sigma constant.
	private static final byte[] SIGMA = "expand 32-byte k".getBytes(StandardCharsets.US_ASCII);
	// Curve25519 field prime: 2^255 - 19.
	private static final BigInteger P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19));

	@Override
	public String name() {
		return "bc";
	}

	// ---- Ed25519 ----------------------------------------------------------

	private static final class Ed25519SecretKey implements Signature.PrivateKey {
		// The 32-byte seed is the authoritative material; the BC parameter object is rebuilt on
		// demand so destroy() can actually wipe the secret.
		private byte @Nullable [] seed;

		private Ed25519SecretKey(byte[] seed) {
			this.seed = seed.clone();
		}

		private byte[] seedOrThrow() {
			if (seed == null)
				throw new IllegalStateException("Private key has been destroyed");
			return seed;
		}

		private Ed25519PrivateKeyParameters params() {
			return new Ed25519PrivateKeyParameters(seedOrThrow(), 0);
		}

		@Override
		public byte[] seed() {
			return seedOrThrow().clone();
		}

		@Override
		public byte[] bytes() {
			byte[] pub = params().generatePublicKey().getEncoded();
			byte[] out = new byte[SIGN_SECRET_KEY_BYTES];
			System.arraycopy(seedOrThrow(), 0, out, 0, SIGN_SEED_BYTES);
			System.arraycopy(pub, 0, out, SIGN_SEED_BYTES, SIGN_PUBLIC_KEY_BYTES);
			return out;
		}

		@Override
		public void destroy() {
			if (seed != null) {
				Arrays.fill(seed, (byte) 0);
				seed = null;
			}
		}

		@Override
		public boolean isDestroyed() {
			return seed == null;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this)
				return true;
			if (!(obj instanceof Signature.PrivateKey that) || isDestroyed() || that.isDestroyed())
				return false;
			return constantTimeAreEqual(bytes(), that.bytes());
		}

		@Override
		public int hashCode() {
			return isDestroyed() ? 0 : Arrays.hashCode(bytes());
		}
	}

	private static final class Ed25519PublicKey implements Signature.PublicKey {
		private @Nullable Ed25519PublicKeyParameters key;

		private Ed25519PublicKey(Ed25519PublicKeyParameters key) {
			this.key = key;
		}

		private Ed25519PublicKeyParameters params() {
			if (key == null)
				throw new IllegalStateException("Public key has been destroyed");
			return key;
		}

		@Override
		public byte[] bytes() {
			return params().getEncoded();
		}

		@Override
		public void destroy() {
			key = null;
		}

		@Override
		public boolean isDestroyed() {
			return key == null;
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
		return new Ed25519SecretKey(seed);
	}

	@Override
	public Signature.PrivateKey ed25519SecretKeyFromBytes(byte[] key) {
		// libsodium secret key is seed || public key; the seed is the first 32 bytes.
		return new Ed25519SecretKey(Arrays.copyOfRange(key, 0, SIGN_SEED_BYTES));
	}

	private static Ed25519PrivateKeyParameters keyOf(Signature.PrivateKey secretKey) {
		return secretKey instanceof Ed25519SecretKey k ? k.params() :
				new Ed25519PrivateKeyParameters(secretKey.seed(), 0);
	}

	private static Ed25519PublicKeyParameters keyOf(Signature.PublicKey publicKey) {
		return publicKey instanceof Ed25519PublicKey k ? k.params() :
				new Ed25519PublicKeyParameters(publicKey.bytes(), 0);
	}

	@Override
	public Signature.PublicKey ed25519PublicKeyFromSecretKey(Signature.PrivateKey secretKey) {
		Ed25519PublicKeyParameters pk = keyOf(secretKey).generatePublicKey();
		return new Ed25519PublicKey(pk);
	}

	@Override
	public Signature.PublicKey ed25519PublicKeyFromBytes(byte[] key) {
		return new Ed25519PublicKey(new Ed25519PublicKeyParameters(key, 0));
	}

	@Override
	public byte[] ed25519Sign(byte[] message, Signature.PrivateKey secretKey) {
		Ed25519Signer signer = new Ed25519Signer();
		signer.init(true, keyOf(secretKey));
		signer.update(message, 0, message.length);
		return signer.generateSignature();
	}

	@Override
	public boolean ed25519Verify(byte[] message, byte[] signature, Signature.PublicKey publicKey) {
		Ed25519Signer verifier = new Ed25519Signer();
		verifier.init(false, keyOf(publicKey));
		verifier.update(message, 0, message.length);
		return verifier.verifySignature(signature);
	}

	// ---- crypto_kdf (keyed BLAKE2b) ---------------------------------------

	@Override
	public byte[] kdfDeriveFromKey(byte[] masterKey, long subKeyId, byte[] context, int subKeyLength) {
		// salt[16] = LE64(subKeyId) || zeros; personal[16] = context[0..8] || zeros
		byte[] salt = new byte[16];
		for (int i = 0; i < 8; i++)
			salt[i] = (byte) (subKeyId >>> (8 * i));
		byte[] personal = new byte[16];
		System.arraycopy(context, 0, personal, 0, KDF_CONTEXT_BYTES);

		Blake2bDigest digest = new Blake2bDigest(masterKey, subKeyLength, salt, personal);
		byte[] out = new byte[subKeyLength];
		digest.doFinal(out, 0); // no input bytes
		return out;
	}

	// ---- Ed25519 -> Curve25519 conversions --------------------------------

	@Override
	public CryptoBox.PublicKey signPublicKeyToBoxPublicKey(Signature.PublicKey publicKey) {
		return new BcBoxPublicKey(edPublicKeyToCurve(publicKey.bytes()));
	}

	@Override
	public CryptoBox.PrivateKey signSecretKeyToBoxSecretKey(Signature.PrivateKey secretKey) {
		// Curve25519 secret key = clamp(SHA-512(seed)[0..32]).
		byte[] h = sha512(secretKey.seed());
		byte[] sk = Arrays.copyOfRange(h, 0, BOX_SECRET_KEY_BYTES);
		sk[0] &= (byte) 248;
		sk[31] &= (byte) 127;
		sk[31] |= (byte) 64;
		return new BcBoxSecretKey(sk);
	}

	// Curve25519 u = (1 + y) / (1 - y) (mod p), where y is the Edwards y-coordinate.
	private static byte[] edPublicKeyToCurve(byte[] ed25519PublicKey) {
		byte[] yle = ed25519PublicKey.clone();
		yle[31] &= 0x7f; // clear the x sign bit
		BigInteger y = decodeLittleEndian(yle);
		BigInteger oneMinusY = BigInteger.ONE.subtract(y).mod(P);
		BigInteger onePlusY = BigInteger.ONE.add(y).mod(P);
		BigInteger u = onePlusY.multiply(oneMinusY.modInverse(P)).mod(P);
		return encodeLittleEndian(u, BOX_PUBLIC_KEY_BYTES);
	}

	// ---- crypto_box -------------------------------------------------------

	private static final class BcBoxPublicKey implements CryptoBox.PublicKey {
		private byte @Nullable [] key;

		private BcBoxPublicKey(byte[] key) {
			this.key = key.clone();
		}

		private byte[] keyOrThrow() {
			if (key == null)
				throw new IllegalStateException("Public key has been destroyed");
			return key;
		}

		@Override
		public byte[] bytes() {
			return keyOrThrow().clone();
		}

		@Override
		public void destroy() {
			if (key != null) {
				Arrays.fill(key, (byte) 0);
				key = null;
			}
		}

		@Override
		public boolean isDestroyed() {
			return key == null;
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

	private static final class BcBoxSecretKey implements CryptoBox.PrivateKey {
		private byte @Nullable [] key;

		private BcBoxSecretKey(byte[] key) {
			this.key = key.clone();
		}

		private byte[] keyOrThrow() {
			if (key == null)
				throw new IllegalStateException("Private key has been destroyed");
			return key;
		}

		@Override
		public byte[] bytes() {
			return keyOrThrow().clone();
		}

		@Override
		public void destroy() {
			if (key != null) {
				Arrays.fill(key, (byte) 0);
				key = null;
			}
		}

		@Override
		public boolean isDestroyed() {
			return key == null;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this)
				return true;
			if (!(obj instanceof CryptoBox.PrivateKey that) || isDestroyed() || that.isDestroyed())
				return false;
			return constantTimeAreEqual(bytes(), that.bytes());
		}

		@Override
		public int hashCode() {
			return isDestroyed() ? 0 : Arrays.hashCode(bytes());
		}
	}

	private static final class BcBoxNonce implements CryptoBox.Nonce {
		private final byte[] nonce;

		private BcBoxNonce(byte[] nonce) {
			this.nonce = nonce.clone();
		}

		@Override
		public CryptoBox.Nonce increment() {
			byte[] next = nonce.clone();
			int c = 1;
			for (int i = 0; i < next.length; i++) {
				c += next[i] & 0xff;
				next[i] = (byte) c;
				c >>>= 8;
			}
			return new BcBoxNonce(next);
		}

		@Override
		public byte[] bytes() {
			return nonce.clone();
		}

		@Override
		public int hashCode() {
			return Arrays.hashCode(nonce);
		}

		@Override
		public boolean equals(Object obj) {
			if (obj == this)
				return true;
			if (!(obj instanceof CryptoBox.Nonce that))
				return false;
			return Arrays.equals(nonce, that.bytes());
		}
	}

	private static final class BcCryptoBox implements CryptoBox {
		private byte @Nullable [] sharedKey;

		private BcCryptoBox(byte[] sharedKey) {
			this.sharedKey = sharedKey;
		}

		private byte[] sharedKeyOrThrow() {
			if (sharedKey == null)
				throw new IllegalStateException("CryptoBox has been closed");
			return sharedKey;
		}

		@Override
		public byte[] encrypt(byte[] message, CryptoBox.Nonce nonce) {
			return secretboxSeal(message, nonceOf(nonce), sharedKeyOrThrow());
		}

		@Override
		public byte[] decrypt(byte[] cipher, CryptoBox.Nonce nonce) throws CryptoException {
			byte[] plain = secretboxOpen(cipher, nonceOf(nonce), sharedKeyOrThrow());
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
			if (sharedKey != null) {
				Arrays.fill(sharedKey, (byte) 0);
				sharedKey = null;
			}
		}

		@Override
		public boolean isDestroyed() {
			return sharedKey == null;
		}
	}

	private static byte[] boxKeyOf(CryptoBox.PublicKey publicKey) {
		return publicKey instanceof BcBoxPublicKey k ? k.keyOrThrow() : publicKey.bytes();
	}

	private static byte[] boxKeyOf(CryptoBox.PrivateKey secretKey) {
		return secretKey instanceof BcBoxSecretKey k ? k.keyOrThrow() : secretKey.bytes();
	}

	// shared = HSalsa20(X25519(sk, pk), nonce=0^16, sigma)
	private static byte[] sharedKey(byte[] boxPublicKey, byte[] boxSecretKey) {
		byte[] s = new byte[BOX_SHARED_KEY_BYTES];
		X25519.calculateAgreement(boxSecretKey, 0, boxPublicKey, 0, s, 0);
		return hsalsa20(s, new byte[16], SIGMA);
	}

	@Override
	public CryptoBox.PublicKey boxPublicKeyFromBytes(byte[] bytes) {
		return new BcBoxPublicKey(bytes);
	}

	@Override
	public CryptoBox.PrivateKey boxSecretKeyFromSeed(byte[] seed) {
		// crypto_box_seed_keypair: secret key = SHA-512(seed)[0..32]
		byte[] sk = Arrays.copyOfRange(sha512(seed), 0, BOX_SEED_BYTES);
		return new BcBoxSecretKey(sk);
	}

	@Override
	public CryptoBox.PrivateKey boxSecretKeyFromBytes(byte[] bytes) {
		return new BcBoxSecretKey(bytes);
	}

	@Override
	public CryptoBox.PublicKey boxPublicKeyFromSecretKey(CryptoBox.PrivateKey secretKey) {
		byte[] pk = new byte[BOX_PUBLIC_KEY_BYTES];
		X25519.scalarMultBase(boxKeyOf(secretKey), 0, pk, 0);
		return new BcBoxPublicKey(pk);
	}

	@Override
	public CryptoBox.Nonce boxNonceFromBytes(byte[] bytes) {
		return new BcBoxNonce(bytes);
	}

	@Override
	public CryptoBox boxBeforeNm(CryptoBox.PublicKey publicKey, CryptoBox.PrivateKey secretKey) {
		return new BcCryptoBox(sharedKey(boxKeyOf(publicKey), boxKeyOf(secretKey)));
	}

	private static byte[] nonceOf(CryptoBox.Nonce nonce) {
		return nonce instanceof BcBoxNonce n ? n.nonce : nonce.bytes();
	}

	@Override
	public byte[] boxEncrypt(byte[] message, CryptoBox.Nonce nonce, CryptoBox.PublicKey publicKey, CryptoBox.PrivateKey secretKey) {
		return secretboxSeal(message, nonceOf(nonce), sharedKey(boxKeyOf(publicKey), boxKeyOf(secretKey)));
	}

	@Override
	public byte @Nullable [] boxDecrypt(byte[] cipher, CryptoBox.Nonce nonce, CryptoBox.PublicKey publicKey, CryptoBox.PrivateKey secretKey) {
		return secretboxOpen(cipher, nonceOf(nonce), sharedKey(boxKeyOf(publicKey), boxKeyOf(secretKey)));
	}

	@Override
	public byte[] boxSeal(byte[] message, CryptoBox.PublicKey publicKey) {
		byte[] recipientPk = boxKeyOf(publicKey);
		byte[] esk = Random.randomBytesSecure(BOX_SECRET_KEY_BYTES);
		byte[] epk = new byte[BOX_PUBLIC_KEY_BYTES];
		X25519.scalarMultBase(esk, 0, epk, 0);
		byte[] nonce = sealNonce(epk, recipientPk);
		byte[] cipher = secretboxSeal(message, nonce, sharedKey(recipientPk, esk));

		byte[] out = new byte[BOX_PUBLIC_KEY_BYTES + cipher.length];
		System.arraycopy(epk, 0, out, 0, BOX_PUBLIC_KEY_BYTES);
		System.arraycopy(cipher, 0, out, BOX_PUBLIC_KEY_BYTES, cipher.length);
		return out;
	}

	@Override
	public byte @Nullable [] boxSealOpen(byte[] cipher, CryptoBox.PublicKey publicKey, CryptoBox.PrivateKey secretKey) {
		if (cipher.length < BOX_PUBLIC_KEY_BYTES + BOX_MAC_BYTES)
			return null;

		byte[] epk = Arrays.copyOfRange(cipher, 0, BOX_PUBLIC_KEY_BYTES);
		byte[] nonce = sealNonce(epk, boxKeyOf(publicKey));
		byte[] boxed = Arrays.copyOfRange(cipher, BOX_PUBLIC_KEY_BYTES, cipher.length);
		return secretboxOpen(boxed, nonce, sharedKey(epk, boxKeyOf(secretKey)));
	}

	// crypto_box_seal nonce = BLAKE2b-192(ephemeralPublicKey || recipientPublicKey)
	private static byte[] sealNonce(byte[] ephemeralPublicKey, byte[] recipientPublicKey) {
		Blake2bDigest digest = new Blake2bDigest(BOX_NONCE_BYTES * 8); // bit length
		digest.update(ephemeralPublicKey, 0, ephemeralPublicKey.length);
		digest.update(recipientPublicKey, 0, recipientPublicKey.length);
		byte[] nonce = new byte[BOX_NONCE_BYTES];
		digest.doFinal(nonce, 0);
		return nonce;
	}

	// ---- crypto_secretbox: XSalsa20-Poly1305 (NaCl easy layout) -----------

	private static byte[] secretboxSeal(byte[] message, byte[] nonce, byte[] key) {
		XSalsa20Engine cipher = new XSalsa20Engine();
		cipher.init(true, new ParametersWithIV(new KeyParameter(key), nonce));

		byte[] subkey = new byte[32];
		cipher.processBytes(new byte[32], 0, 32, subkey, 0); // first 32 keystream bytes -> Poly1305 key

		byte[] out = new byte[BOX_MAC_BYTES + message.length];
		cipher.processBytes(message, 0, message.length, out, BOX_MAC_BYTES);

		Poly1305 mac = new Poly1305();
		mac.init(new KeyParameter(subkey));
		mac.update(out, BOX_MAC_BYTES, message.length);
		mac.doFinal(out, 0);
		return out;
	}

	private static byte @Nullable [] secretboxOpen(byte[] boxed, byte[] nonce, byte[] key) {
		if (boxed.length < BOX_MAC_BYTES)
			return null;
		int clen = boxed.length - BOX_MAC_BYTES;

		XSalsa20Engine cipher = new XSalsa20Engine();
		cipher.init(true, new ParametersWithIV(new KeyParameter(key), nonce));

		byte[] subkey = new byte[32];
		cipher.processBytes(new byte[32], 0, 32, subkey, 0);

		Poly1305 mac = new Poly1305();
		mac.init(new KeyParameter(subkey));
		mac.update(boxed, BOX_MAC_BYTES, clen);
		byte[] tag = new byte[BOX_MAC_BYTES];
		mac.doFinal(tag, 0);

		if (!constantTimeAreEqual(BOX_MAC_BYTES, tag, 0, boxed, 0))
			return null;

		byte[] message = new byte[clen];
		cipher.processBytes(boxed, BOX_MAC_BYTES, clen, message, 0);
		return message;
	}

	// ---- HSalsa20 core (crypto_core_hsalsa20) -----------------------------
	// Salsa20 core run for 20 rounds, emitting the constant/input diagonal words without the
	// final feed-forward add. Used by crypto_box to derive the shared key from the X25519 output.

	@SuppressWarnings("SameParameterValue")
	private static byte[] hsalsa20(byte[] key, byte[] in, byte[] c) {
		int x0 = load(c, 0), x5 = load(c, 4), x10 = load(c, 8), x15 = load(c, 12);
		int x1 = load(key, 0), x2 = load(key, 4), x3 = load(key, 8), x4 = load(key, 12);
		int x11 = load(key, 16), x12 = load(key, 20), x13 = load(key, 24), x14 = load(key, 28);
		int x6 = load(in, 0), x7 = load(in, 4), x8 = load(in, 8), x9 = load(in, 12);

		for (int i = 0; i < 10; i++) {
			x4 ^= Integer.rotateLeft(x0 + x12, 7);
			x8 ^= Integer.rotateLeft(x4 + x0, 9);
			x12 ^= Integer.rotateLeft(x8 + x4, 13);
			x0 ^= Integer.rotateLeft(x12 + x8, 18);
			x9 ^= Integer.rotateLeft(x5 + x1, 7);
			x13 ^= Integer.rotateLeft(x9 + x5, 9);
			x1 ^= Integer.rotateLeft(x13 + x9, 13);
			x5 ^= Integer.rotateLeft(x1 + x13, 18);
			x14 ^= Integer.rotateLeft(x10 + x6, 7);
			x2 ^= Integer.rotateLeft(x14 + x10, 9);
			x6 ^= Integer.rotateLeft(x2 + x14, 13);
			x10 ^= Integer.rotateLeft(x6 + x2, 18);
			x3 ^= Integer.rotateLeft(x15 + x11, 7);
			x7 ^= Integer.rotateLeft(x3 + x15, 9);
			x11 ^= Integer.rotateLeft(x7 + x3, 13);
			x15 ^= Integer.rotateLeft(x11 + x7, 18);

			x1 ^= Integer.rotateLeft(x0 + x3, 7);
			x2 ^= Integer.rotateLeft(x1 + x0, 9);
			x3 ^= Integer.rotateLeft(x2 + x1, 13);
			x0 ^= Integer.rotateLeft(x3 + x2, 18);
			x6 ^= Integer.rotateLeft(x5 + x4, 7);
			x7 ^= Integer.rotateLeft(x6 + x5, 9);
			x4 ^= Integer.rotateLeft(x7 + x6, 13);
			x5 ^= Integer.rotateLeft(x4 + x7, 18);
			x11 ^= Integer.rotateLeft(x10 + x9, 7);
			x8 ^= Integer.rotateLeft(x11 + x10, 9);
			x9 ^= Integer.rotateLeft(x8 + x11, 13);
			x10 ^= Integer.rotateLeft(x9 + x8, 18);
			x12 ^= Integer.rotateLeft(x15 + x14, 7);
			x13 ^= Integer.rotateLeft(x12 + x15, 9);
			x14 ^= Integer.rotateLeft(x13 + x12, 13);
			x15 ^= Integer.rotateLeft(x14 + x13, 18);
		}

		byte[] out = new byte[32];
		store(out, 0, x0);
		store(out, 4, x5);
		store(out, 8, x10);
		store(out, 12, x15);
		store(out, 16, x6);
		store(out, 20, x7);
		store(out, 24, x8);
		store(out, 28, x9);
		return out;
	}

	private static int load(byte[] b, int off) {
		return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
				| ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
	}

	private static void store(byte[] b, int off, int v) {
		b[off] = (byte) v;
		b[off + 1] = (byte) (v >>> 8);
		b[off + 2] = (byte) (v >>> 16);
		b[off + 3] = (byte) (v >>> 24);
	}

	// ---- crypto_pwhash (Argon2) -------------------------------------------

	@Override
	public byte[] pwHash(byte[] password, int length, byte[] salt, long opsLimit, long memLimit, int algorithm) {
		return argon2(password, salt, length, opsLimit, memLimit, algorithm);
	}

	@Override
	public String pwHashString(byte[] password, long opsLimit, long memLimit, int algorithm) {
		byte[] salt = Random.randomBytesSecure(PWHASH_SALT_BYTES);
		int memKiB = (int) (memLimit / 1024);
		int ops = (int) opsLimit;
		byte[] hash = argon2(password, salt, 32, opsLimit, memLimit, algorithm);

		Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
		return "$" + argon2Name(algorithm) + "$v=19$m=" + memKiB + ",t=" + ops + ",p=1$"
				+ b64.encodeToString(salt) + "$" + b64.encodeToString(hash);
	}

	@Override
	public boolean pwHashVerify(String hash, byte[] password) {
		Phc phc = Phc.parse(hash);
		if (phc == null)
			return false;
		byte[] expected = phc.hash;
		byte[] actual = argon2(password, phc.salt, expected.length, phc.t,
				(long) phc.m * 1024L, phc.algorithm);
		return constantTimeAreEqual(actual, expected);
	}

	@Override
	public boolean pwHashNeedsRehash(String hash, long opsLimit, long memLimit) {
		Phc phc = Phc.parse(hash);
		if (phc == null)
			return true;
		int memKiB = (int) (memLimit / 1024);
		return phc.algorithm != PWHASH_ALG_ARGON2ID13 || phc.t != opsLimit || phc.m != memKiB || phc.p != 1;
	}

	private static byte[] argon2(byte[] password, byte[] salt, int length, long opsLimit, long memLimit, int algorithm) {
		int type = algorithm == PWHASH_ALG_ARGON2I13 ? Argon2Parameters.ARGON2_i : Argon2Parameters.ARGON2_id;
		Argon2Parameters params = new Argon2Parameters.Builder(type)
				.withVersion(Argon2Parameters.ARGON2_VERSION_13)
				.withIterations((int) opsLimit)
				.withMemoryAsKB((int) (memLimit / 1024))
				.withParallelism(1)
				.withSalt(salt)
				.build();
		Argon2BytesGenerator generator = new Argon2BytesGenerator();
		generator.init(params);
		byte[] out = new byte[length];
		generator.generateBytes(password, out);
		return out;
	}

	private static String argon2Name(int algorithm) {
		return algorithm == PWHASH_ALG_ARGON2I13 ? "argon2i" : "argon2id";
	}

	// Minimal Argon2 PHC string parser: $argon2id$v=19$m=..,t=..,p=..$<b64salt>$<b64hash>
	private static final class Phc {
		final int algorithm;
		final int m;
		final long t;
		final int p;
		final byte[] salt;
		final byte[] hash;

		private Phc(int algorithm, int m, long t, int p, byte[] salt, byte[] hash) {
			this.algorithm = algorithm;
			this.m = m;
			this.t = t;
			this.p = p;
			this.salt = salt;
			this.hash = hash;
		}

		static @Nullable Phc parse(String s) {
			try {
				// Leading '$' produces an empty first token.
				String[] parts = s.split("\\$");
				if (parts.length < 5)
					return null;

				int algorithm;
				if ("argon2id".equals(parts[1]))
					algorithm = PWHASH_ALG_ARGON2ID13;
				else if ("argon2i".equals(parts[1]))
					algorithm = PWHASH_ALG_ARGON2I13;
				else
					return null;

				int idx = 2;
				if (parts[idx].startsWith("v="))
					idx++; // skip optional version segment

				int m = 0, p = 0;
				long t = 0;
				for (String kv : parts[idx].split(",")) {
					if (kv.startsWith("m="))
						m = Integer.parseInt(kv.substring(2));
					else if (kv.startsWith("t="))
						t = Long.parseLong(kv.substring(2));
					else if (kv.startsWith("p="))
						p = Integer.parseInt(kv.substring(2));
				}
				idx++;

				byte[] salt = Base64.getDecoder().decode(parts[idx++]);
				byte[] hash = Base64.getDecoder().decode(parts[idx]);
				return new Phc(algorithm, m, t, p, salt, hash);
			} catch (RuntimeException e) {
				return null;
			}
		}
	}

	// ---- small helpers ----------------------------------------------------

	private static BigInteger decodeLittleEndian(byte[] le) {
		byte[] be = new byte[le.length];
		for (int i = 0; i < le.length; i++)
			be[i] = le[le.length - 1 - i];
		return new BigInteger(1, be);
	}

	@SuppressWarnings("SameParameterValue")
	private static byte[] encodeLittleEndian(BigInteger value, int length) {
		byte[] be = value.toByteArray();
		byte[] le = new byte[length];
		// be may have a leading zero sign byte or be shorter than length
		for (int i = 0; i < be.length; i++) {
			int pos = be.length - 1 - i;
			if (i < length)
				le[i] = be[pos];
		}
		return le;
	}

	private static byte[] sha512(byte[] data) {
		SHA512Digest digest = new SHA512Digest();
		byte[] hashBytes = new byte[digest.getDigestSize()];
		digest.update(data, 0, data.length);
		digest.doFinal(hashBytes, 0);
		return hashBytes;
	}
}