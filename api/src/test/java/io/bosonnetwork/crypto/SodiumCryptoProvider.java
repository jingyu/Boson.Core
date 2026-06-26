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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.TimeZone;

import org.apache.tuweni.crypto.sodium.Box;
import org.apache.tuweni.crypto.sodium.KeyDerivation;
import org.apache.tuweni.crypto.sodium.PasswordHash;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import io.bosonnetwork.utils.Base58;

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

	private static Box boxOf(CryptoBox box) {
		if (box instanceof SodiumCryptoBox b)
			return b.box;
		else
			throw new IllegalStateException("Not a SodiumCryptoBox: " + box.getClass().getName());
	}

	@Override
	public byte[] boxEncrypt(byte[] message, CryptoBox.Nonce nonce, CryptoBox box) {
		return boxOf(box).encrypt(message, nonceOf(nonce));
	}

	@Override
	public byte @Nullable [] boxDecrypt(byte[] cipher, CryptoBox.Nonce nonce, CryptoBox box) {
		return boxOf(box).decrypt(cipher, nonceOf(nonce));
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

	@Override
	public PemCertificateAndKey certificateFromSignatureKey(Signature.PrivateKey signaturePrivateKey,
															@Nullable String ipAddress, @Nullable String hostName,
															boolean enableWildcard) throws CryptoException {
		// Mirror BouncyCastleCryptoProvider: at least one SAN entry is required.
		if (ipAddress == null && hostName == null)
			throw new IllegalArgumentException("At least one SAN (hostname or IP) must be provided");

		try {
			// Extract the 32-byte seed and public key from libsodium 64-byte SK
			byte[] sodiumSecretKey = signaturePrivateKey.bytes();
			byte[] sodiumSeed = new byte[32];
			System.arraycopy(sodiumSecretKey, 0, sodiumSeed, 0, 32);
			byte[] sodiumPublicKey = new byte[32];
			System.arraycopy(sodiumSecretKey, 32, sodiumPublicKey, 0, 32);
			String keyId = Base58.encode(sodiumPublicKey);

			/*/ PKCS#8 v2 OneAsymmetricKey for Ed25519 (RFC 8410)
			// Use standard JDK 15+ Ed25519 support
			// PKCS#8 v2 OneAsymmetricKey for Ed25519 (RFC 8410)
			// Version v2 (1) because we include the public key
			// AlgorithmIdentifier: 1.3.101.112
			// PrivateKey: OCTET STRING containing OCTET STRING (32 bytes seed)
			// PublicKey: [1] IMPLICIT BIT STRING (32 bytes)
			byte[] pkcs8Bytes = new byte[83];
			System.arraycopy(new byte[]{
					0x30, 0x51, // SEQUENCE (81 bytes)
					0x02, 0x01, 0x01, // Version v2 (1)
					0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, // Algorithm (Ed25519: 1.3.101.112)
					0x04, 0x22, 0x04, 0x20 // PrivateKey OCTET STRING (34 bytes)
			}, 0, pkcs8Bytes, 0, 16);
			System.arraycopy(sodiumSeed, 0, pkcs8Bytes, 16, 32);
			System.arraycopy(new byte[]{
					(byte) 0x81, 0x21, 0x00 // [1] IMPLICIT BIT STRING (33 bytes: 0 padding + 32 bytes)
			}, 0, pkcs8Bytes, 48, 3);
			System.arraycopy(sodiumPublicKey, 0, pkcs8Bytes, 51, 32);
			*/

			// Use standard JDK 15+ Ed25519 support
			// PKCS#8 v1 OneAsymmetricKey for Ed25519 (RFC 8410)
			// Version v1 (0) because we don't include the public key
			// AlgorithmIdentifier: 1.3.101.112
			// PrivateKey: OCTET STRING containing OCTET STRING (32 bytes seed)
			byte[] pkcs8Bytes = new byte[48];
			System.arraycopy(new byte[]{
					0x30, 0x2e, // SEQUENCE (46 bytes)
					0x02, 0x01, 0x00, // Version v1 (0)
					0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, // Algorithm (Ed25519: 1.3.101.112)
					0x04, 0x22, 0x04, 0x20 // PrivateKey OCTET STRING (34 bytes)
			}, 0, pkcs8Bytes, 0, 16);
			System.arraycopy(sodiumSeed, 0, pkcs8Bytes, 16, 32);

			KeyFactory kf = KeyFactory.getInstance("Ed25519");
			PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));

			// Build TBSCertificate
			BigInteger serial = new BigInteger(128, new SecureRandom());
			Instant now = Instant.now();
			Date notBefore = Date.from(now.minus(10, ChronoUnit.MINUTES));
			Date notAfter = Date.from(now.plus(3650, ChronoUnit.DAYS));

			// Construct manual ASN.1/DER certificate
			byte[] tbs = encodeTBS(serial, keyId, notBefore, notAfter, sodiumPublicKey, ipAddress, hostName, enableWildcard);

			java.security.Signature sig = java.security.Signature.getInstance("Ed25519");
			sig.initSign(privateKey);
			sig.update(tbs);
			byte[] signatureValue = sig.sign();

			byte[] certDer = encodeCert(tbs, signatureValue);

			String keyPem = "-----BEGIN PRIVATE KEY-----\n" +
					Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pkcs8Bytes) +
					"\n-----END PRIVATE KEY-----\n";

			String certPem = "-----BEGIN CERTIFICATE-----\n" +
					Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certDer) +
					"\n-----END CERTIFICATE-----\n";

			return new PemCertificateAndKey(certPem, keyPem);
		} catch (IOException | InvalidKeyException | SignatureException | NoSuchAlgorithmException |
		         InvalidKeySpecException e) {
			throw new CryptoException("Failed to convert key using simple implementation", e);
		}
	}

	private static byte[] encodeTBS(BigInteger serial, String cn, Date notBefore, Date notAfter, byte[] pubKey,
	                                @Nullable String ip, @Nullable String host, boolean wildcard) throws IOException {
		DerBuilder tbs = new DerBuilder();
		tbs.addTag((byte) 0xA0, new DerBuilder().addInt(2).build()); // Version v3
		tbs.addInt(serial);
		tbs.addSeq(new DerBuilder().addOid("1.3.101.112")); // Algorithm: Ed25519
		tbs.addSeq(new DerBuilder().addSet(new DerBuilder().addSeq(new DerBuilder().addOid("2.5.4.3").addPrintableString(cn)))); // Issuer
		tbs.addSeq(new DerBuilder().addTime(notBefore).addTime(notAfter)); // Validity
		tbs.addSeq(new DerBuilder().addSet(new DerBuilder().addSeq(new DerBuilder().addOid("2.5.4.3").addPrintableString(cn)))); // Subject
		tbs.addSeq(new DerBuilder().addSeq(new DerBuilder().addOid("1.3.101.112")).addBitString(pubKey)); // SubjectPublicKeyInfo

		// Extensions
		DerBuilder exts = new DerBuilder();

		// Subject Key Identifier (critical=false)
		try {
			MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
			// SKI = SHA-1 (public key bytes).
			// For Ed25519, BouncyCastle calculates SHA-1 over the raw 32-byte public key.
			// The extension value is an OCTET STRING containing the key identifier (which is also an OCTET STRING per RFC 5280).
			// However, in X.509, the extension value field is ALREADY an OCTET STRING, so we just wrap the hash in an OCTET STRING.
			byte[] ski = sha1.digest(pubKey);
			exts.addSeq(new DerBuilder().addOid("2.5.29.14").addOctetString(new DerBuilder().addOctetString(ski).build()));
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("SHA-1 not found", e);
		}

		// KeyUsage (critical=true, digitalSignature=bit 0)
		exts.addSeq(new DerBuilder().addOid("2.5.29.15").addBool(true).addOctetString(new DerBuilder().addBitString(new byte[]{(byte) 0x80}, 7).build()));

		// SAN (critical=false)
		DerBuilder san = new DerBuilder();
		if (host != null) san.addTag((byte) 0x82, host.getBytes(StandardCharsets.US_ASCII));
		if (wildcard && host != null) san.addTag((byte) 0x82, ("*." + host).getBytes(StandardCharsets.US_ASCII));
		if (ip != null) {
			byte[] ipBytes = InetAddress.getByName(ip).getAddress(); // 4 bytes for IPv4, 16 for IPv6
			san.addTag((byte) 0x87, ipBytes);
		}
		if (ip != null || host != null)
			exts.addSeq(new DerBuilder().addOid("2.5.29.17").addOctetString(new DerBuilder().addSeq(san).build()));

		// BasicConstraints (critical=true, CA=false)
		exts.addSeq(new DerBuilder().addOid("2.5.29.19").addBool(true).addOctetString(new DerBuilder().addSeq(new DerBuilder()).build()));

		// ExtendedKeyUsage (critical=false, serverAuth, clientAuth)
		exts.addSeq(new DerBuilder().addOid("2.5.29.37").addOctetString(new DerBuilder().addSeq(new DerBuilder().addOid("1.3.6.1.5.5.7.3.1").addOid("1.3.6.1.5.5.7.3.2")).build()));

		tbs.addTag((byte) 0xA3, new DerBuilder().addSeq(exts).build());

		return tbs.buildSeq();
	}

	private static byte[] encodeCert(byte[] tbs, byte[] signature) throws IOException {
		return new DerBuilder()
				.addRaw(tbs)
				.addSeq(new DerBuilder().addOid("1.3.101.112"))
				.addBitString(signature)
				.buildSeq();
	}

	private static class DerBuilder {
		private final ByteArrayOutputStream out = new ByteArrayOutputStream();

		public DerBuilder addRaw(byte[] raw) throws IOException {
			out.write(raw);
			return this;
		}

		public DerBuilder addTag(byte tag, byte[] val) throws IOException {
			out.write(tag);
			writeLen(val.length);
			out.write(val);
			return this;
		}

		public DerBuilder addInt(long v) throws IOException {
			return addInt(BigInteger.valueOf(v));
		}

		public DerBuilder addInt(BigInteger v) throws IOException {
			return addTag((byte) 0x02, v.toByteArray());
		}

		public DerBuilder addOid(String oid) throws IOException {
			String[] parts = oid.split("\\.");
			ByteArrayOutputStream b = new ByteArrayOutputStream();
			b.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
			for (int i = 2; i < parts.length; i++) {
				long v = Long.parseLong(parts[i]);
				if (v == 0) b.write(0);
				else {
					byte[] buf = new byte[10];
					int pos = 10;
					buf[--pos] = (byte) (v & 0x7F);
					while ((v >>= 7) > 0) buf[--pos] = (byte) ((v & 0x7F) | 0x80);
					b.write(buf, pos, 10 - pos);
				}
			}
			return addTag((byte) 0x06, b.toByteArray());
		}

		public DerBuilder addPrintableString(String s) throws IOException {
			return addTag((byte) 0x13, s.getBytes(StandardCharsets.US_ASCII));
		}

		// RFC 5280: encode dates before 2050 as UTCTime, and 2050 or later as GeneralizedTime.
		public DerBuilder addTime(Date d) throws IOException {
			int year = d.toInstant().atZone(ZoneOffset.UTC).getYear();
			if (year < 2050) {
				SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmss'Z'");
				sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
				return addTag((byte) 0x17, sdf.format(d).getBytes(StandardCharsets.US_ASCII)); // UTCTime
			} else {
				SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss'Z'");
				sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
				return addTag((byte) 0x18, sdf.format(d).getBytes(StandardCharsets.US_ASCII)); // GeneralizedTime
			}
		}

		public DerBuilder addBitString(byte[] b) throws IOException {
			return addBitString(b, 0);
		}

		public DerBuilder addBitString(byte[] b, int pad) throws IOException {
			byte[] val = new byte[b.length + 1];
			val[0] = (byte) pad;
			System.arraycopy(b, 0, val, 1, b.length);
			return addTag((byte) 0x03, val);
		}

		public DerBuilder addOctetString(byte[] b) throws IOException {
			return addTag((byte) 0x04, b);
		}

		public DerBuilder addBool(boolean v) throws IOException {
			return addTag((byte) 0x01, new byte[]{(byte) (v ? 0xFF : 0x00)});
		}

		public DerBuilder addSeq(DerBuilder b) throws IOException {
			return addTag((byte) 0x30, b.build());
		}

		public DerBuilder addSet(DerBuilder b) throws IOException {
			return addTag((byte) 0x31, b.build());
		}

		public byte[] build() {
			return out.toByteArray();
		}

		public byte[] buildSeq() throws IOException {
			return new DerBuilder().addSeq(this).build();
		}

		private void writeLen(int len) {
			if (len < 128) out.write(len);
			else {
				byte[] b = BigInteger.valueOf(len).toByteArray();
				int skip = (b.length > 1 && b[0] == 0) ? 1 : 0;
				out.write(0x80 | (b.length - skip));
				out.write(b, skip, b.length - skip);
			}
		}
	}
}