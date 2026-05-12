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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Random;
import java.util.TimeZone;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.PfxOptions;

import io.bosonnetwork.utils.Base58;

/**
 * Utility class for certificate and key management.
 */
public class CryptoUtil {
	/**
	 * Represents a pair of PEM-encoded certificate and private key.
	 *
	 * @param cert       the PEM-encoded certificate
	 * @param privateKey the PEM-encoded private key
	 */
	public record PemCertificateAndKey(String cert, String privateKey) {
	}

	/**
	 * Generates a self-signed X.509 certificate and private key from a signature private key without Bouncy Castle.
	 *
	 * @param signaturePrivateKey the signature private key
	 * @param ipAddress           the IP address to include in the Subject Alternative Name (SAN)
	 * @param hostName            the host name to include in the Subject Alternative Name (SAN)
	 * @param enableWildcard      whether to include a wildcard host name in the SAN
	 * @return a {@link PemCertificateAndKey} containing the PEM-encoded certificate and private key
	 * @throws KeyConvertException if an error occurs during key conversion or certificate generation
	 */
	public static PemCertificateAndKey certificateFromSignatureKey(Signature.PrivateKey signaturePrivateKey,
	                                                               String ipAddress, String hostName, boolean enableWildcard)
			throws KeyConvertException {
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
		} catch (Exception e) {
			throw new KeyConvertException("Failed to convert key using simple implementation", e);
		}
	}

	private static byte[] encodeTBS(BigInteger serial, String cn, Date notBefore, Date notAfter, byte[] pubKey,
	                                String ip, String host, boolean wildcard) throws IOException {
		DerBuilder tbs = new DerBuilder();
		tbs.addTag((byte) 0xA0, new DerBuilder().addInt(2).build()); // Version v3
		tbs.addInt(serial);
		tbs.addSeq(new DerBuilder().addOid("1.3.101.112")); // Algorithm: Ed25519
		tbs.addSeq(new DerBuilder().addSet(new DerBuilder().addSeq(new DerBuilder().addOid("2.5.4.3").addPrintableString(cn)))); // Issuer
		tbs.addSeq(new DerBuilder().addUtcTime(notBefore).addUtcTime(notAfter)); // Validity
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
			byte[] ipBytes = InetAddress.getByName(ip).getAddress(); // 16 bytes
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

		public DerBuilder addUtcTime(Date d) throws IOException {
			SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmss'Z'");
			sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
			return addTag((byte) 0x17, sdf.format(d).getBytes(StandardCharsets.US_ASCII));
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

	/**
	 * Generates a random password containing a mix of uppercase and lowercase letters, digits, and special characters.
	 *
	 * @param length the length of the password to generate; must be a positive integer
	 * @return a randomly generated password as a String
	 * @throws IllegalArgumentException if the specified length is not positive
	 */
	public static String randomPassword(int length) {
		if (length <= 0)
			throw new IllegalArgumentException("Length must be positive");

		String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_-+=<>?/|";
		StringBuilder sb = new StringBuilder(length);
		java.util.Random random = new Random();
		for (int i = 0; i < length; i++) {
			int index = random.nextInt(characters.length());
			sb.append(characters.charAt(index));
		}

		return sb.toString();
	}

	/*
	 * Although using Vert.x PemKeyCertOptions is more direct:
	 *
	 * PemKeyCertOptions keyCertOptions = new PemKeyCertOptions()
	 *     .setKeyValue(Buffer.buffer(certAndKey.privateKey()))
	 *     .setCertValue(Buffer.buffer(certAndKey.cert()));
	 * options.setKeyCertOptions(keyCertOptions);
	 *
	 * Vert.x (Netty) does not currently support PEM-encoded PKCS#8 Ed25519 private keys.
	 * Therefore, we must package them into a PKCS#12 keystore and use PfxOptions instead.
	 */

	/**
	 * Creates a {@link PfxOptions} instance from a pair of PEM-encoded certificate and private key.
	 *
	 * @param certAndKey the {@link PemCertificateAndKey} containing the PEM-encoded certificate and private key
	 * @return a {@link PfxOptions} containing a PKCS#12 keystore created from the provided certificate and private key
	 * @throws InvalidKeySpecException if the private key could not be parsed correctly
	 * @throws NoSuchAlgorithmException if the "Ed25519" algorithm required for the private key is not available
	 * @throws CertificateException if the certificate could not be parsed correctly
	 * @throws KeyStoreException if an error occurs while accessing or modifying the keystore
	 */
	public static PfxOptions pfxOptionsFromCertAndPrivateKey(PemCertificateAndKey certAndKey)
			throws InvalidKeySpecException, NoSuchAlgorithmException, CertificateException, KeyStoreException {
		// Remove PEM headers
		String normalized = certAndKey.privateKey()
				.replace("-----BEGIN PRIVATE KEY-----", "")
				.replace("-----END PRIVATE KEY-----", "")
				.replaceAll("\\s", "");

		// Decode DER
		byte[] der = Base64.getDecoder().decode(normalized);
		// PKCS#8 -> PrivateKey
		PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
		// Note: PKCS8EncodedKeySpec#getAlgorithm() returns null since it doesn't parse the DER,
		// so we must specify "Ed25519" explicitly for the KeyFactory.
		KeyFactory kf = KeyFactory.getInstance("Ed25519");
		PrivateKey privateKey = kf.generatePrivate(spec);

		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		X509Certificate cert = (X509Certificate) cf.generateCertificate(
				new ByteArrayInputStream(certAndKey.cert().getBytes(StandardCharsets.US_ASCII)));
		KeyStore ks = KeyStore.getInstance("PKCS12");
		try {
			ks.load(null, null);
		} catch (IOException e) {
			throw new KeyStoreException("Failed to load empty KeyStore", e);
		}
		String password = randomPassword(16);
		ks.setKeyEntry(
				"server",
				privateKey,
				password.toCharArray(),
				new Certificate[]{cert});

		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try {
			ks.store(bos, password.toCharArray());
		} catch (IOException e) {
			throw new KeyStoreException("Failed to store KeyStore", e);
		}
		return new PfxOptions()
				.setValue(Buffer.buffer(bos.toByteArray()))
				.setPassword(password);
	}

	/**
	 * Exception thrown when an error occurs during key conversion or certificate generation.
	 */
	public static class KeyConvertException extends Exception {
		private static final long serialVersionUID = -5975318365528633648L;

		/**
		 * Constructs a new KeyConvertException with the specified detail message.
		 *
		 * @param message the detail message
		 */
		public KeyConvertException(String message) {
			super(message);
		}

		/**
		 * Constructs a new KeyConvertException with the specified detail message and cause.
		 *
		 * @param message the detail message
		 * @param cause   the cause
		 */
		public KeyConvertException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}