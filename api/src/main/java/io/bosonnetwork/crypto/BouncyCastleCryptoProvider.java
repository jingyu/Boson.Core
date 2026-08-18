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

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.oiw.OIWObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509ExtensionUtils;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.bouncycastle.crypto.digests.SHA512Digest;
import org.bouncycastle.crypto.engines.ChaCha7539Engine;
import org.bouncycastle.crypto.engines.XSalsa20Engine;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.macs.Poly1305;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.bouncycastle.math.ec.rfc7748.X25519;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider;
import org.bouncycastle.operator.bc.BcEdECContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.jspecify.annotations.Nullable;

import io.bosonnetwork.utils.Base58;

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

	private static byte[] sharedKeyOf(CryptoBox box) {
		if (box instanceof BcCryptoBox c)
			return c.sharedKeyOrThrow();

		throw new IllegalStateException("Not a BcCryptoBox: " + box.getClass().getName());
	}

	@Override
	public byte[] boxKeyBytes(CryptoBox box) {
		return sharedKeyOf(box).clone();
	}

	@Override
	public byte[] boxEncrypt(byte[] message, CryptoBox.Nonce nonce, CryptoBox box) {
		return secretboxSeal(message, nonceOf(nonce), sharedKeyOf(box));
	}

	@Override
	public byte @Nullable [] boxDecrypt(byte[] cipher, CryptoBox.Nonce nonce, CryptoBox box) {
		return secretboxOpen(cipher, nonceOf(nonce), sharedKeyOf(box));
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

	// ---- crypto_secretstream -------------------------------------------

	/**
	 * Implementation of {@link SecretStreamState} wrapping an {@link Xchacha20poly1305State}.
	 */
	private static class SecretStreamStateImpl implements SecretStreamState {
		/** The underlying XChaCha20-Poly1305 streaming state. */
		private final Xchacha20poly1305State state;

		/** The stream header copy. */
		private final byte[] header;

		/** Flag indicating if the final block has been pushed or pulled. */
		private boolean complete = false;

		/** Flag indicating if the state has been destroyed/closed. */
		private boolean destroyed = false;

		/**
		 * Constructs a {@code SecretStreamStateImpl} with the given state and header.
		 *
		 * @param state  the underlying state
		 * @param header the header byte array
		 */
		private SecretStreamStateImpl(Xchacha20poly1305State state, byte[] header) {
			this.state = state;
			this.header = header;
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public byte[] header() {
			return header.clone();
		}

		/**
		 * {@inheritDoc}
		 */
		@Override
		public boolean isComplete() {
			return complete;
		}

		/**
		 * {@inheritDoc}
		 *
		 * @throws IllegalStateException if the state has been destroyed or is already complete
		 */
		@Override
		public byte[] push(byte @Nullable [] message, byte @Nullable [] additional, boolean finalBlock) {
			/*/
			// already checked in public API level
			if (destroyed)
				throw new IllegalStateException("SecretStreamState has been destroyed");
			if (complete)
				throw new IllegalStateException("SecretStreamState is already complete");
			*/

			byte[] ciphertext = state.push(message, additional, finalBlock ? SECRET_STREAM_TAG_FINAL : SECRET_STREAM_TAG_MESSAGE);
			if (finalBlock)
				complete = true;
			return ciphertext;
		}

		/**
		 * {@inheritDoc}
		 *
		 * @throws IllegalStateException if the state has been destroyed or is already complete
		 */
		@Override
		public byte[] pull(byte[] ciphertext, byte @Nullable [] additional) {
			/*/
			// already checked in public API level
			if (destroyed)
				throw new IllegalStateException("SecretStreamState has been destroyed");
			if (complete)
				throw new IllegalStateException("SecretStreamState is already complete");
			*/

			Xchacha20poly1305State.PullResult result = state.pull(ciphertext, additional);
			if (result.tag == SECRET_STREAM_TAG_FINAL)
				complete = true;
			return result.message;
		}

		/**
		 * Destroys this state and zeroes out sensitive key material.
		 */
		@Override
		public void destroy() {
			destroyed = true;
			state.clear();
		}

		/**
		 * Returns whether this state has been destroyed.
		 *
		 * @return {@code true} if destroyed; {@code false} otherwise
		 */
		@Override
		public boolean isDestroyed() {
			return destroyed;
		}
	}

	/**
	 * Initializes a secret stream encryption state with the specified key.
	 *
	 * @param key the {@value #SECRET_STREAM_KEY_BYTES}-byte secret key
	 * @return a new {@link SecretStreamState} configured for encryption
	 * @throws NullPointerException if {@code key} is null
	 * @throws IllegalArgumentException if {@code key} length is not equal to {@link #SECRET_STREAM_KEY_BYTES}
	 */
	@Override
	public SecretStreamState secretStreamInitPush(byte[] key) {
		/*/
		// already checked in public API level
		Objects.requireNonNull(key, "key cannot be null");
		if (key.length != SECRET_STREAM_KEY_BYTES)
			throw new IllegalArgumentException("key must be " + SECRET_STREAM_KEY_BYTES + " bytes");
		*/
		Xchacha20poly1305State state = new Xchacha20poly1305State();
		byte[] header = new byte[SECRET_STREAM_HEADER_BYTES];
		state.initPush(header, key);
		return new SecretStreamStateImpl(state, header);
	}

	/**
	 * Initializes a secret stream decryption state with the specified header and key.
	 *
	 * @param header the {@value #SECRET_STREAM_HEADER_BYTES}-byte stream header
	 * @param key    the {@value #SECRET_STREAM_KEY_BYTES}-byte secret key
	 * @return a new {@link SecretStreamState} configured for decryption
	 * @throws NullPointerException if {@code header} or {@code key} is null
	 * @throws IllegalArgumentException if {@code header} length is not {@link #SECRET_STREAM_HEADER_BYTES} or {@code key} length is not {@link #SECRET_STREAM_KEY_BYTES}
	 */
	@Override
	public SecretStreamState secretStreamInitPull(byte[] header, byte[] key) {
		/*/
		// already checked in public API level
		Objects.requireNonNull(header, "header cannot be null");
		if (header.length != SECRET_STREAM_HEADER_BYTES)
			throw new IllegalArgumentException("header must be " + SECRET_STREAM_HEADER_BYTES + " bytes");
		Objects.requireNonNull(key, "key cannot be null");
		if (key.length != SECRET_STREAM_KEY_BYTES)
			throw new IllegalArgumentException("key must be " + SECRET_STREAM_KEY_BYTES + " bytes");
		*/
		Xchacha20poly1305State state = new Xchacha20poly1305State();
		state.initPull(header, key);
		return new SecretStreamStateImpl(state, header.clone());
	}

	// =====================================================================================
	//  crypto_secretstream_xchacha20poly1305_state  ->  Java: the Xchacha20poly1305State class below
	// =====================================================================================

	/**
	 * Mutable streaming state, equivalent to
	 * {@code crypto_secretstream_xchacha20poly1305_state}.
	 *
	 * <p>Layout (52 bytes):
	 * <pre>
	 *   k[32]       IETF ChaCha20 stream key (derived, NOT the master key)
	 *   nonce[12]   counter(4) || inonce(8)
	 *   pad[8]      unused (kept only to match STATEBYTES)
	 * </pre>
	 */
	public static final class Xchacha20poly1305State {
		/**
		 * Width of the 32-bit counter occupying nonce[0..4).
		 */
		private static final int COUNTERBYTES = 4;
		/**
		 * Width of the 64-bit "initial nonce" occupying nonce[4..12).
		 */
		private static final int INONCEBYTES = 8;
		/**
		 * HChaCha20 input length: 16 bytes (a 256-bit key / 128-bit input).
		 */
		private static final int HCHACHA20_INPUTBYTES = 16;
		/**
		 * IETF ChaCha20 nonce length: 12 bytes (counter || inonce).
		 */
		private static final int IETF_NONCEBYTES = 12;

		/** ChaCha20 block size in bytes (64 bytes). */
		private static final int BLOCKBYTES = 64;
		/** Poly1305 authentication tag length in bytes (16 bytes). */
		private static final int POLY1305_BYTES = 16;
		/**
		 * Poly1305 key length = 32 bytes (r || s), taken from the first 32 bytes of block 0.
		 */
		private static final int POLY1305_KEYBYTES = 32;

		/** Constant empty byte array for default parameters. */
		private static final byte[] EMPTY = new byte[0];

		/**
		 * 16 zero bytes used for the Poly1305 padding (the {@code _pad0[16]} in libsodium).
		 */
		private static final byte[] PAD0 = new byte[16];

		/** Shared SecureRandom instance for header generation. */
		private static final SecureRandom RNG = new SecureRandom();

		/** Derived 32-byte IETF ChaCha20 key. */
		final byte[] k = new byte[SECRET_STREAM_KEY_BYTES];

		/**
		 * 12-byte IETF nonce: bytes [0..4) are the counter, [4..12) the inonce.
		 */
		final byte[] nonce = new byte[IETF_NONCEBYTES];

		/** 8-byte padding buffer matching libsodium STATEBYTES layout. */
		final byte[] pad = new byte[8];

		/**
		 * Constructs an uninitialized {@code Xchacha20poly1305State}.
		 */
		private Xchacha20poly1305State() {}

		/**
		 * Index into {@link #nonce} of the 4-byte counter region.
		 *
		 * @return the counter byte offset (0)
		 */
		private static int counterOffset() {
			return 0;
		}

		/**
		 * Index into {@link #nonce} of the 8-byte inonce region.
		 *
		 * @return the inonce byte offset (4)
		 */
		private static int inonceOffset() {
			return COUNTERBYTES;
		}

		/**
		 * Validates that the key array has length {@link #SECRET_STREAM_KEY_BYTES}.
		 *
		 * @param key the key byte array
		 * @throws IllegalArgumentException if the key length is invalid
		 */
		private static void requireKey(byte[] key) {
			if (key.length != SECRET_STREAM_KEY_BYTES) {
				throw new IllegalArgumentException("key must be " + SECRET_STREAM_KEY_BYTES + " bytes");
			}
		}

		/**
		 * Resets the 4-byte counter in {@link #nonce} to 1 (little-endian: nonce[0] = 1, nonce[1..4) = 0).
		 */
		private void counterReset() {
			Arrays.fill(nonce, 0, COUNTERBYTES, (byte) 0);
			nonce[0] = 1;   // _crypto_secretstream_..._counter_reset sets nonce[0] = 1
		}

		/**
		 * Zeroes out all sensitive state memory (key, nonce, pad).
		 */
		public void clear() {
			Arrays.fill(k, (byte) 0);
			Arrays.fill(nonce, (byte) 0);
			Arrays.fill(pad, (byte) 0);
		}

		// =====================================================================================
		//  crypto_secretstream_xchacha20poly1305_init_push
		// =====================================================================================

		/**
		 * Initialize the push (encrypt) side of a stream.
		 *
		 * <p>Equivalent to {@code crypto_secretstream_xchacha20poly1305_init_push}.
		 *
		 * @param header output buffer of length {@link #SECRET_STREAM_HEADER_BYTES}; receives the header that must
		 *               be transmitted to the receiver (it is random - that is what makes each
		 *               stream unique).
		 * @param key    the master key ({@link #SECRET_STREAM_KEY_BYTES} bytes)
		 * @throws IllegalArgumentException if header or key length is invalid
		 */
		public void initPush(byte[] header, byte[] key) {
			if (header.length != SECRET_STREAM_HEADER_BYTES) {
				throw new IllegalArgumentException("header must be " + SECRET_STREAM_HEADER_BYTES + " bytes");
			}
			requireKey(key);

			// randombytes_buf(out, HEADERBYTES) - header = HChaCha20-input(16) || inonce(8)
			RNG.nextBytes(header);

			// crypto_core_hchacha20(state->k, out, k, NULL)
			hChaCha20(k, header, 0, key);

			// _crypto_secretstream_..._counter_reset(state)
			counterReset();

			// memcpy(STATE_INONCE(state), out + HCHACHA20_INPUTBYTES, INONCEBYTES)
			System.arraycopy(header, HCHACHA20_INPUTBYTES, nonce, Xchacha20poly1305State.inonceOffset(), INONCEBYTES);

			// memset(state->_pad, 0, ...)
			Arrays.fill(pad, (byte) 0);
		}

		// =====================================================================================
		//  crypto_secretstream_xchacha20poly1305_init_pull
		// =====================================================================================

		/**
		 * Initialize the pull (decrypt) side of a stream.
		 *
		 * <p>Equivalent to {@code crypto_secretstream_xchacha20poly1305_init_pull}.
		 *
		 * @param header input stream header buffer of length {@link #SECRET_STREAM_HEADER_BYTES}
		 * @param key    the master key ({@link #SECRET_STREAM_KEY_BYTES} bytes)
		 * @throws IllegalArgumentException if header or key length is invalid
		 */
		public void initPull(byte[] header, byte[] key) {
			if (header.length != SECRET_STREAM_HEADER_BYTES) {
				throw new IllegalArgumentException("header must be " + SECRET_STREAM_HEADER_BYTES + " bytes");
			}
			requireKey(key);

			hChaCha20(k, header, 0, key);
			counterReset();
			System.arraycopy(header, HCHACHA20_INPUTBYTES, nonce, Xchacha20poly1305State.inonceOffset(), INONCEBYTES);
			Arrays.fill(pad, (byte) 0);
		}

		// =====================================================================================
		//  crypto_secretstream_xchacha20poly1305_push
		// =====================================================================================

		/**
		 * Encrypt one message chunk.
		 *
		 * <p>Equivalent to {@code crypto_secretstream_xchacha20poly1305_push}.
		 *
		 * @param m   plaintext message (length 0..MESSAGEBYTES_MAX)
		 * @param ad  additional data to authenticate but not encrypt (may be empty/null)
		 * @param tag one of {@link #SECRET_STREAM_TAG_MESSAGE}, {@link #SECRET_STREAM_TAG_PUSH},
		 *            {@link #SECRET_STREAM_TAG_REKEY}, {@link #SECRET_STREAM_TAG_FINAL}
		 * @return the ciphertext
		 */
		public byte[] push(byte @Nullable [] m, byte @Nullable [] ad, byte tag) {
			if (m == null) m = EMPTY;
			if (ad == null) ad = EMPTY;
			int mlen = m.length;

			byte[] c = new byte[mlen + SECRET_STREAM_ABYTES];

			// --- Poly1305 key = ChaCha20 block 0 over (nonce, key) -------------------------
			byte[] block = new byte[BLOCKBYTES];
			chacha20IetfXor(block, block, BLOCKBYTES, nonce, 0L, k);

			Poly1305 poly = new Poly1305();
			// libsodium: crypto_onetimeauth_poly1305_init(poly, block). Poly1305 is keyed with
			// the first 32 bytes of the ChaCha20 keystream block (r=block[0..16) clamped,
			// s=block[16..32)). BC's Poly1305 expects the full 32-byte key and clamps r itself.
			poly.init(new KeyParameter(block, 0, POLY1305_KEYBYTES));
			Arrays.fill(block, (byte) 0);

			// --- AD + 0x10-alignment padding -----------------------------------------------
			poly.update(ad, 0, ad.length);
			poly.update(PAD0, 0, (0x10 - ad.length) & 0xf);

			// --- The 64-byte tag-bearing block ---------------------------------------------
			// block = zeros; block[0] = tag; then XOR with ChaCha20 block 1.
			block[0] = tag;
			chacha20IetfXor(block, block, BLOCKBYTES, nonce, 1L, k);
			// libsodium updates Poly1305 with the whole 64-byte block, then writes block[0]
			// (the now-encrypted tag byte) to out[0].
			poly.update(block, 0, BLOCKBYTES);
			c[0] = block[0];

			// --- Encrypt the message with ChaCha20 starting at block 2 --------------------
			int cOff = 1;   // out + sizeof(tag)
			chacha20IetfXor(c, cOff, m, 0, mlen, nonce, 2L, k);
			poly.update(c, cOff, mlen);
			// NOTE: libsodium pads with (0x10 - sizeof(block) + mlen) & 0xf. The comment in the
			// source flags this as a deviation from "block-aligned" padding; we MUST replicate
			// it exactly for interop. Arithmetic is mod 256 (byte-width), and "& 0xf" keeps it
			// in [0,15].
			poly.update(PAD0, 0, (0x10 - BLOCKBYTES + mlen) & 0xf);

			// --- Two 8-byte length fields --------------------------------------------------
			byte[] slen = new byte[8];
			store64LE(slen, 0, ad.length & 0xFFFFFFFFL);
			poly.update(slen, 0, 8);
			store64LE(slen, 0, (BLOCKBYTES + mlen) & 0xFFFFFFFFL);
			poly.update(slen, 0, 8);

			// --- Finalize Poly1305 tag, place it after the ciphertext ---------------------
			byte[] mac = new byte[POLY1305_BYTES];
			poly.doFinal(mac, 0);
			System.arraycopy(mac, 0, c, cOff + mlen, POLY1305_BYTES);

			// --- Update the inonce (XOR with low 8 bytes of mac), bump counter ------------
			for (int i = 0; i < INONCEBYTES; i++)
				nonce[Xchacha20poly1305State.inonceOffset() + i] ^= mac[i];

			increment(nonce, Xchacha20poly1305State.counterOffset(), COUNTERBYTES);

			// --- Re-key if requested, or if the counter wrapped to zero -------------------
			if ((tag & SECRET_STREAM_TAG_REKEY) != 0 || isZero(nonce, Xchacha20poly1305State.counterOffset(), COUNTERBYTES))
				rekey();

			return c;
		}

		// =====================================================================================
		//  crypto_secretstream_xchacha20poly1305_pull
		// =====================================================================================

		/**
		 * Decrypt and authenticate one message chunk.
		 *
		 * <p>Equivalent to {@code crypto_secretstream_xchacha20poly1305_pull}.
		 *
		 * @param c     ciphertext, length {@code >= ABYTES}
		 * @param ad    additional data (must match what was passed to {@link #push})
		 * @return a {@link PullResult} carrying the plaintext message and the recovered tag
		 * @throws IllegalArgumentException if ciphertext length is less than {@link #SECRET_STREAM_ABYTES}
		 * @throws IllegalStateException if the Poly1305 tag verification fails
		 */
		public PullResult pull(byte[] c, byte @Nullable [] ad) {
			if (ad == null) ad = EMPTY;
			if (c.length < SECRET_STREAM_ABYTES)
				throw new IllegalArgumentException("ciphertext too short");

			int mlen = c.length - SECRET_STREAM_ABYTES;
			byte[] m = new byte[mlen];

			// --- Poly1305 key = ChaCha20 block 0 ------------------------------------------
			byte[] block = new byte[BLOCKBYTES];
			chacha20IetfXor(block, block, BLOCKBYTES, nonce, 0L, k);

			Poly1305 poly = new Poly1305();
			poly.init(new KeyParameter(block, 0, POLY1305_KEYBYTES));
			Arrays.fill(block, (byte) 0);

			poly.update(ad, 0, ad.length);
			poly.update(PAD0, 0, (0x10 - ad.length) & 0xf);

			// --- Recover the tag from the encrypted 64-byte tag block ---------------------
			// block = zeros; block[0] = in[0]; XOR with ChaCha20 block 1; tag = block[0].
			// Then restore block[0] = in[0] (the ciphertext tag byte) before authenticating.
			block[0] = c[0];
			chacha20IetfXor(block, block, BLOCKBYTES, nonce, 1L, k);
			byte tag = block[0];
			block[0] = c[0];
			poly.update(block, 0, BLOCKBYTES);

			// --- Authenticate the ciphertext (in[1..1+mlen]) ------------------------------
			int cOff = 1;
			poly.update(c, cOff, mlen);
			poly.update(PAD0, 0, (0x10 - BLOCKBYTES + mlen) & 0xf);

			byte[] slen = new byte[8];
			store64LE(slen, 0, ad.length & 0xFFFFFFFFL);
			poly.update(slen, 0, 8);
			store64LE(slen, 0, (BLOCKBYTES + mlen) & 0xFFFFFFFFL);
			poly.update(slen, 0, 8);

			byte[] mac = new byte[POLY1305_BYTES];
			poly.doFinal(mac, 0);

			// --- Constant-time compare against the stored mac (in[cOff+mlen .. +16)) ------
			byte[] storedMac = Arrays.copyOfRange(c, cOff + mlen, cOff + mlen + POLY1305_BYTES);
			boolean match = constantTimeEquals(mac, storedMac);
			Arrays.fill(storedMac, (byte) 0);

			if (!match) {
				Arrays.fill(mac, (byte) 0);
				throw new IllegalStateException("Poly1305 tag mismatch");
			}

			// --- Only after MAC verification: decrypt the ciphertext ----------------------
			chacha20IetfXor(m, 0, c, cOff, mlen, nonce, 2L, k);

			// --- Update inonce + counter + (maybe) rekey ----------------------------------
			for (int i = 0; i < INONCEBYTES; i++) {
				nonce[Xchacha20poly1305State.inonceOffset() + i] ^= mac[i];
			}
			Arrays.fill(mac, (byte) 0);

			increment(nonce, Xchacha20poly1305State.counterOffset(), COUNTERBYTES);
			if ((tag & SECRET_STREAM_TAG_REKEY) != 0 || isZero(nonce, Xchacha20poly1305State.counterOffset(), COUNTERBYTES))
				rekey();

			return new PullResult(m, tag);
		}

		// =====================================================================================
		//  crypto_secretstream_xchacha20poly1305_rekey
		// =====================================================================================

		/**
		 * Manually force a re-key of the stream state.
		 *
		 * <p>Equivalent to {@code crypto_secretstream_xchacha20poly1305_rekey}. Derives a new
		 * stream key and a new inonce from the current ones via one IETF ChaCha20 invocation,
		 * then resets the counter to 1.
		 */
		public void rekey() {
			// Build plaintext = current_k(32) || current_inonce(8); XOR with ChaCha20 keystream
			// over (state->nonce, state->k); split the result back out.
			byte[] buf = new byte[SECRET_STREAM_KEY_BYTES + INONCEBYTES];
			System.arraycopy(k, 0, buf, 0, SECRET_STREAM_KEY_BYTES);
			System.arraycopy(nonce, Xchacha20poly1305State.inonceOffset(), buf, SECRET_STREAM_KEY_BYTES, INONCEBYTES);

			// crypto_stream_chacha20_ietf_xor(buf, buf, sizeof buf, state->nonce, state->k)
			chacha20IetfXor(buf, buf, buf.length, nonce, 0L, k);

			System.arraycopy(buf, 0, k, 0, SECRET_STREAM_KEY_BYTES);
			System.arraycopy(buf, SECRET_STREAM_KEY_BYTES, nonce, Xchacha20poly1305State.inonceOffset(), INONCEBYTES);
			counterReset();
			Arrays.fill(buf, (byte) 0);
		}

		/**
		 * Result of {@link #pull}, carrying the decrypted message and the recovered tag.
		 */
		public static final class PullResult {
			/**
			 * The decrypted plain message.
			 */
			public final byte[] message;
			/**
			 * Recovered tag byte (one of the {@code SECRET_STREAM_TAG_*} constants).
			 */
			public final byte tag;

			/**
			 * Constructs a {@code PullResult} holding the decrypted message and tag.
			 *
			 * @param message the decrypted message byte array
			 * @param tag     the tag byte recovered from the block
			 */
			public PullResult(byte[] message, byte tag) {
				this.message = message;
				this.tag = tag;
			}
		}

		/**
		 * IETF ChaCha20 used as a keystream/XOR cipher with an explicit starting block counter.
		 *
		 * <p>This is the exact equivalent of libsodium's
		 * {@code crypto_stream_chacha20_ietf_xor_ic(m, c, mlen, nonce, ic, key)}: the 12-byte
		 * {@code nonce} is interpreted as counter(4) || iv(8), and ChaCha20 starts at block
		 * {@code ic}.
		 *
		 * <p>BC's {@link ChaCha7539Engine} expects a 12-byte IV (counter=0 prefix + 8-byte nonce
		 * in word 14/15 layout). libsodium's layout splits the 12 bytes the same way: word 12 is
		 * the counter (advanced by {@code ic}), words 13-15 carry iv. BC's {@code seekTo(bytePos)}
		 * lets us jump straight to block {@code ic} by passing {@code ic * 64} - verified against
		 * RFC 8439 and sequential reads.
		 *
		 * @param out          output byte array for XOR result
		 * @param outOff       starting offset in {@code out}
		 * @param in           input byte array
		 * @param inOff        starting offset in {@code in}
		 * @param len          number of bytes to process
		 * @param nonce        12-byte IETF nonce (counter || inonce)
		 * @param blockCounter initial block counter
		 * @param key          32-byte ChaCha20 key
		 */
		private static void chacha20IetfXor(byte[] out, int outOff,
		                                    byte[] in, int inOff, int len,
		                                    byte[] nonce, long blockCounter, byte[] key) {
			ChaCha7539Engine eng = new ChaCha7539Engine();
			eng.init(true, new ParametersWithIV(new KeyParameter(key), nonce));
			eng.seekTo(blockCounter * (long) BLOCKBYTES);
			eng.processBytes(in, inOff, len, out, outOff);
		}

		/**
		 * In-place overload for convenience (e.g. the 64-byte tag block).
		 *
		 * @param buf          buffer used for both input and output
		 * @param in           input byte array
		 * @param len          number of bytes to process
		 * @param nonce        12-byte IETF nonce
		 * @param blockCounter initial block counter
		 * @param key          32-byte key
		 */
		private static void chacha20IetfXor(byte[] buf, byte[] in, int len,
		                                    byte[] nonce, long blockCounter, byte[] key) {
			chacha20IetfXor(buf, 0, in, 0, len, nonce, blockCounter, key);
		}
	}

	/**
	 * HChaCha20: derive a 32-byte subkey from a 32-byte key and 16-byte input.
	 *
	 * <p>Equivalent to libsodium's {@code crypto_core_hchacha20}. Runs the 20-round ChaCha
	 * permutation on the standard initial state and returns words 0-3 and 12-15 (no final
	 * addition, unlike full ChaCha20 keystream blocks).
	 *
	 * <p>Verified against the test vector in draft-irtf-cfrg-xchacha section 2.2.1. We implement
	 * this directly rather than reusing BC's {@code ChaChaEngine.chachaCore}, because that
	 * helper performs the final state addition (it targets keystream generation) and so does
	 * not yield the correct HChaCha20 output.
	 *
	 * @param out   32-byte output buffer for derived subkey
	 * @param in    input buffer containing 16-byte input at {@code inOff}
	 * @param inOff starting offset in {@code in}
	 * @param key   32-byte key
	 */
	@SuppressWarnings("SameParameterValue")
	private static void hChaCha20(byte[] out, byte[] in, int inOff, byte[] key) {
		int[] s = new int[16];
		s[0] = 0x61707865;
		s[1] = 0x3320646e;
		s[2] = 0x79622d32;
		s[3] = 0x6b206574;
		s[4] = load32LE(key, 0);
		s[5] = load32LE(key, 4);
		s[6] = load32LE(key, 8);
		s[7] = load32LE(key, 12);
		s[8] = load32LE(key, 16);
		s[9] = load32LE(key, 20);
		s[10] = load32LE(key, 24);
		s[11] = load32LE(key, 28);
		s[12] = load32LE(in, inOff);
		s[13] = load32LE(in, inOff + 4);
		s[14] = load32LE(in, inOff + 8);
		s[15] = load32LE(in, inOff + 12);

		for (int i = 0; i < 10; i++) {                 // 10 double-rounds = 20 rounds
			quarterRound(s, 0, 4, 8, 12);              // columns
			quarterRound(s, 1, 5, 9, 13);
			quarterRound(s, 2, 6, 10, 14);
			quarterRound(s, 3, 7, 11, 15);
			quarterRound(s, 0, 5, 10, 15);             // diagonals
			quarterRound(s, 1, 6, 11, 12);
			quarterRound(s, 2, 7, 8, 13);
			quarterRound(s, 3, 4, 9, 14);
		}
		// HChaCha20 returns the first row (words 0..3) and the last row (words 12..15).
		store32LE(out, 0, s[0]);
		store32LE(out, 4, s[1]);
		store32LE(out, 8, s[2]);
		store32LE(out, 12, s[3]);
		store32LE(out, 16, s[12]);
		store32LE(out, 20, s[13]);
		store32LE(out, 24, s[14]);
		store32LE(out, 28, s[15]);

		Arrays.fill(s, 0);
	}

	/**
	 * The ChaCha quarter-round function.
	 *
	 * @param s 16-element state array
	 * @param a index of word A
	 * @param b index of word B
	 * @param c index of word C
	 * @param d index of word D
	 */
	private static void quarterRound(int[] s, int a, int b, int c, int d) {
		s[a] += s[b];
		s[d] = Integer.rotateLeft(s[d] ^ s[a], 16);
		s[c] += s[d];
		s[b] = Integer.rotateLeft(s[b] ^ s[c], 12);
		s[a] += s[b];
		s[d] = Integer.rotateLeft(s[d] ^ s[a], 8);
		s[c] += s[d];
		s[b] = Integer.rotateLeft(s[b] ^ s[c], 7);
	}

	/**
	 * Decodes a 32-bit little-endian integer from a byte array.
	 *
	 * @param b   byte array
	 * @param off offset in byte array
	 * @return decoded int value
	 */
	private static int load32LE(byte[] b, int off) {
		return (b[off] & 0xff)
				| ((b[off + 1] & 0xff) << 8)
				| ((b[off + 2] & 0xff) << 16)
				| ((b[off + 3] & 0xff) << 24);
	}

	// ---- Little-endian helpers ------------------------------------------------------------

	/**
	 * Encodes a 32-bit integer as little-endian into a byte array.
	 *
	 * @param b   byte array
	 * @param off offset in byte array
	 * @param v   int value to encode
	 */
	private static void store32LE(byte[] b, int off, int v) {
		b[off] = (byte) v;
		b[off + 1] = (byte) (v >>> 8);
		b[off + 2] = (byte) (v >>> 16);
		b[off + 3] = (byte) (v >>> 24);
	}

	/**
	 * Encodes a 64-bit integer as little-endian into a byte array.
	 *
	 * @param b   byte array
	 * @param off offset in byte array
	 * @param v   long value to encode
	 */
	@SuppressWarnings("SameParameterValue")
	private static void store64LE(byte[] b, int off, long v) {
		b[off] = (byte) v;
		b[off + 1] = (byte) (v >>> 8);
		b[off + 2] = (byte) (v >>> 16);
		b[off + 3] = (byte) (v >>> 24);
		b[off + 4] = (byte) (v >>> 32);
		b[off + 5] = (byte) (v >>> 40);
		b[off + 6] = (byte) (v >>> 48);
		b[off + 7] = (byte) (v >>> 56);
	}

	/**
	 * Little-endian increment of {@code len} bytes starting at {@code off} (libsodium's sodium_increment).
	 *
	 * @param b   byte array
	 * @param off starting offset
	 * @param len number of bytes to increment
	 */
	@SuppressWarnings("SameParameterValue")
	private static void increment(byte[] b, int off, int len) {
		for (int i = 0; i < len; i++)
			if (++b[off + i] != 0) break;   // carry only while byte wraps to 0
	}

	/**
	 * Checks whether {@code len} bytes starting at {@code off} in byte array {@code b} are all zero.
	 *
	 * @param b   byte array
	 * @param off starting offset
	 * @param len number of bytes to check
	 * @return {@code true} if all bytes are zero; {@code false} otherwise
	 */
	@SuppressWarnings("SameParameterValue")
	private static boolean isZero(byte[] b, int off, int len) {
		for (int i = 0; i < len; i++)
			if (b[off + i] != 0) return false;

		return true;
	}

	/**
	 * Compares two byte arrays in constant time to prevent timing side-channel attacks.
	 *
	 * @param a first byte array
	 * @param b second byte array
	 * @return {@code true} if byte arrays are equal; {@code false} otherwise
	 */
	private static boolean constantTimeEquals(byte[] a, byte[] b) {
		if (a.length != b.length) return false;
		int d = 0;
		for (int i = 0; i < a.length; i++) d |= a[i] ^ b[i];
		return d == 0;
	}

	// ---- certificate utiles -------------------------------------------

	@Override
	public PemKeyCertificate certificateFromSignatureKey(Signature.PrivateKey secretKey,
	                                                     @Nullable String ipAddress, @Nullable String hostName,
	                                                     boolean enableWildcard) throws CryptoException {
		try {
			// Extract the 32-byte seed and public key from libsodium 64-byte SK
			byte[] sk = secretKey.bytes();
			byte[] seed = new byte[32];
			System.arraycopy(sk, 0, seed, 0, 32);
			byte[] pk = new byte[32];
			System.arraycopy(sk, 32, pk, 0, 32);
			String keyId = Base58.encode(pk);

			// Build Bouncy Castle Ed25519 key parameters. The whole certificate is produced with the
			// Bouncy Castle low-level API (no JCA provider), so callers do not have to register the BC
			// JCE provider via Security.addProvider().
			Ed25519PrivateKeyParameters privateKeyParams = new Ed25519PrivateKeyParameters(seed);
			Ed25519PublicKeyParameters publicKeyParams = new Ed25519PublicKeyParameters(pk);

			/*/ PKCS#8 v2 OneAsymmetricKey for Ed25519 (RFC 8410)
			// Convert to JCA PrivateKey / PublicKey via PKCS#8 v2 DER encoding (version=1, include public key)
			// Encode to PKCS#8 DER, then load via JCA KeyFactory
			byte[] pkcs8Bytes = PrivateKeyInfoFactory.createPrivateKeyInfo(privateKeyParams).getEncoded();
			*/

			// Encode the private key as PKCS#8 v1 DER (version=0, no public key).
			// BC defaults to v2 (RFC 5958) for Ed25519 which Vert.x (Netty) rejects.
			PrivateKeyInfo v2PrivateKeyInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(privateKeyParams);
			PrivateKeyInfo v1PrivateKeyInfo = new PrivateKeyInfo(
					v2PrivateKeyInfo.getPrivateKeyAlgorithm(),
					v2PrivateKeyInfo.parsePrivateKey()
			);
			byte[] pkcs8Bytes = v1PrivateKeyInfo.getEncoded();

			SubjectPublicKeyInfo spki = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(publicKeyParams);

			// Build a self-signed X.509 certificate
			X500Name subject = new X500Name("CN=" + keyId);
			BigInteger serial = new BigInteger(128, new SecureRandom());

			// Subtract 10 minutes to handle clock skew
			Instant now = Instant.now();
			Date notBefore = Date.from(now.minus(10, ChronoUnit.MINUTES));
			Date notAfter = Date.from(now.plus(3650, ChronoUnit.DAYS));

			// Without SAN, modern browsers and most TLS clients REJECT the cert
			// Chrome/Firefox dropped CN-only matching in 2017
			List<GeneralName> subjectAltNames = new ArrayList<>();
			if (hostName != null)
				subjectAltNames.add(new GeneralName(GeneralName.dNSName, hostName));
			if (enableWildcard && hostName != null)
				subjectAltNames.add(new GeneralName(GeneralName.dNSName, "*." + hostName));
			if (ipAddress != null)
				subjectAltNames.add(new GeneralName(GeneralName.iPAddress, ipAddress));
			if (subjectAltNames.isEmpty())
				throw new IllegalArgumentException("At least one SAN (hostname or IP) must be provided");

			// Sign with the Bouncy Castle Ed25519 implementation directly (no JCA provider).
			ContentSigner signer = new BcEdECContentSignerBuilder(new AlgorithmIdentifier(EdECObjectIdentifiers.id_Ed25519))
					.build(privateKeyParams);

			DigestCalculator digestCalc = new BcDigestCalculatorProvider()
					.get(new AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1));

			X509CertificateHolder certHolder = new X509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, spki)
					// Subject Key Identifier (optional but good practice)
					.addExtension(Extension.subjectKeyIdentifier, false,
							new X509ExtensionUtils(digestCalc).createSubjectKeyIdentifier(spki))
					// KeyUsage: required for TLS
					.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature))
					// SAN - critical for client acceptance
					.addExtension(Extension.subjectAlternativeName, false,
							new GeneralNames(subjectAltNames.toArray(new GeneralName[0])))
					// BasicConstraints: CA=false, this is a server/end-entity cert
					.addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
					// Extended Key Usage: HTTPS, WSS, MQTTS server, only if also used for mTLS client certs
					.addExtension(Extension.extendedKeyUsage, false,
							new ExtendedKeyUsage(new KeyPurposeId[]{KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}))
					.build(signer);

			// Write private key and certificate to PEM strings
			String keyPem = toPem("PRIVATE KEY", pkcs8Bytes);
			String certPem = toPem("CERTIFICATE", certHolder.getEncoded());

			return new PemKeyCertificate(certPem, keyPem);
		} catch (IOException | OperatorCreationException e) {
			throw new CryptoException("Failed to convert key to PEM format key and certificate", e);
		}
	}

	@Override
	public PemKeyCertificate certificateSelfSignedEcdsa(@Nullable String ipAddress, @Nullable String hostName,
	                                                    boolean enableWildcard, Signature.@Nullable PrivateKey identityKey) throws CryptoException {
		if (ipAddress == null && hostName == null)
			throw new IllegalArgumentException("At least one SAN (hostname or IP) must be provided");

		try {
			// Fresh ECDSA P-256 (secp256r1) key pair from the platform JCA provider (SunEC). Browsers do
			// not support Ed25519 server certificates, so the TLS key is ECDSA.
			KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
			kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
			KeyPair keyPair = kpg.generateKeyPair();

			// CN is ignored by browsers (they match on SAN) but keeps the certificate readable.
			String cn = hostName != null ? hostName : ipAddress;
			X500Name subject = new X500Name("CN=" + cn);
			BigInteger serial = new BigInteger(128, new SecureRandom());

			Instant now = Instant.now();
			Date notBefore = Date.from(now.minus(10, ChronoUnit.MINUTES));
			Date notAfter = Date.from(now.plus(3650, ChronoUnit.DAYS));

			List<GeneralName> subjectAltNames = new ArrayList<>();
			if (hostName != null)
				subjectAltNames.add(new GeneralName(GeneralName.dNSName, hostName));
			if (enableWildcard && hostName != null)
				subjectAltNames.add(new GeneralName(GeneralName.dNSName, "*." + hostName));
			if (ipAddress != null)
				subjectAltNames.add(new GeneralName(GeneralName.iPAddress, ipAddress));

			ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.getPrivate());
			JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();

			X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
					subject, serial, notBefore, notAfter, subject, keyPair.getPublic())
					.addExtension(Extension.subjectKeyIdentifier, false,
							extUtils.createSubjectKeyIdentifier(keyPair.getPublic()))
					// KeyUsage: digitalSignature is required for ECDHE_ECDSA TLS cipher suites
					.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature))
					.addExtension(Extension.subjectAlternativeName, false,
							new GeneralNames(subjectAltNames.toArray(new GeneralName[0])))
					.addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
					.addExtension(Extension.extendedKeyUsage, false,
							new ExtendedKeyUsage(new KeyPurposeId[]{KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));

			// Optional Boson identity binding as a standard issuerAltName URI GeneralName: an Ed25519
			// signature over the ECDSA SubjectPublicKeyInfo, so peers can pin the identity via HybridTrustManager.
			if (identityKey != null) {
				byte[] spki = keyPair.getPublic().getEncoded();
				byte[] sk = identityKey.bytes();
				byte[] publicKey = Arrays.copyOfRange(sk, 32, 64);
				Ed25519Signer ed = new Ed25519Signer();
				ed.init(true, keyOf(identityKey));
				ed.update(spki, 0, spki.length);
				byte[] signature = ed.generateSignature();
				String uri = CertUtil.formatIdentityBinding(publicKey, signature);
				builder.addExtension(Extension.issuerAlternativeName, false,
						new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, uri)));
			}

			X509CertificateHolder certHolder = builder.build(signer);

			// getEncoded() on a JCA EC private key yields a standard PKCS#8 encoding that Netty accepts.
			String keyPem = toPem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
			String certPem = toPem("CERTIFICATE", certHolder.getEncoded());
			return new PemKeyCertificate(certPem, keyPem);
		} catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | OperatorCreationException | IOException e) {
			throw new CryptoException("Failed to generate self-signed ECDSA certificate", e);
		}
	}

	private static String toPem(String type, byte[] der) throws IOException {
		StringWriter sw = new StringWriter();
		try (PemWriter writer = new PemWriter(sw)) {
			writer.writeObject(new PemObject(type, der));
		}
		return sw.toString();
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