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

import java.io.IOException;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import io.bosonnetwork.utils.Base58;

public class CertUtilBouncyCastle {
	/**
	 * Represents a pair of PEM-encoded certificate and private key.
	 *
	 * @param cert       the PEM-encoded certificate
	 * @param privateKey the PEM-encoded private key
	 */
	public record PemCertificateAndKey(String cert, String privateKey) {
	}

	/**
	 * Initializes the security provider.
	 * Adds {@link BouncyCastleProvider} to the security providers.
	 */
	public static void init() {
		Security.addProvider(new BouncyCastleProvider());
	}

	/**
	 * Generates a self-signed X.509 certificate and private key from a signature private key using Bouncy Castle.
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

			// Build Bouncy Castle Ed25519 key parameters
			Ed25519PrivateKeyParameters privateKeyParams = new Ed25519PrivateKeyParameters(sodiumSeed);
			Ed25519PublicKeyParameters publicKeyParams = new Ed25519PublicKeyParameters(sodiumPublicKey);

			/*/ PKCS#8 v2 OneAsymmetricKey for Ed25519 (RFC 8410)
			// Convert to JCA PrivateKey / PublicKey via PKCS#8 v2 DER encoding (version=1, include public key)
			// Encode to PKCS#8 DER, then load via JCA KeyFactory
			byte[] pkcs8Bytes = PrivateKeyInfoFactory.createPrivateKeyInfo(privateKeyParams).getEncoded();
			*/

			// Convert to JCA PrivateKey via PKCS#8 v1 DER encoding (version=0, no public key)
			// BC defaults to v2 (RFC 5958) for Ed25519 which Vert.x rejects
			PrivateKeyInfo v2PrivateKeyInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(privateKeyParams);
			PrivateKeyInfo v1PrivateKeyInfo = new PrivateKeyInfo(
					v2PrivateKeyInfo.getPrivateKeyAlgorithm(),
					v2PrivateKeyInfo.parsePrivateKey()
			);
			byte[] pkcs8Bytes = v1PrivateKeyInfo.getEncoded();

			byte[] spkiBytes = SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(publicKeyParams).getEncoded();

			KeyFactory kf = KeyFactory.getInstance("Ed25519", "BC");
			PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));
			// unused, but useful for verifying the public key matches the private key
			// PublicKey publicKey = kf.generatePublic(new X509EncodedKeySpec(spkiBytes));

			// Build a self-signed X.509 certificate
			X500Name subject = new X500Name("CN=" + keyId);
			BigInteger serial = new BigInteger(128, new SecureRandom());

			// Subtract 10 minutes to handle clock skew
			Instant now = Instant.now();
			Date notBefore = Date.from(now.minus(10, ChronoUnit.MINUTES));
			Date notAfter = Date.from(now.plus(3650, ChronoUnit.DAYS));

			SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(spkiBytes);

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
				throw new KeyConvertException("At least one SAN (hostname or IP) must be provided");

			// Ed25519 signatures don't use a hash — pass "Ed25519" directly
			ContentSigner signer = new JcaContentSignerBuilder("Ed25519")
					.setProvider("BC")
					.build(privateKey);

			DigestCalculator digestCalc = new BcDigestCalculatorProvider()
					.get(new AlgorithmIdentifier(OIWObjectIdentifiers.idSHA1));

			X509CertificateHolder certHolder = new X509v3CertificateBuilder(subject, serial, notBefore, notAfter, subject, spki)
					// Subject Key Identifier (optional but good practice)
					.addExtension(Extension.subjectKeyIdentifier, false,
							new X509ExtensionUtils(digestCalc).createSubjectKeyIdentifier(spki))
					// KeyUsage: required for TLS
					.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature))
					// SAN — critical for client acceptance
					.addExtension(Extension.subjectAlternativeName, false,
							new GeneralNames(subjectAltNames.toArray(new GeneralName[0])))
					// BasicConstraints: CA=false, this is a server/end-entity cert
					.addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
					// Extended Key Usage: HTTPS, WSS, MQTTS server, only if also used for mTLS client certs
					.addExtension(Extension.extendedKeyUsage, false,
							new ExtendedKeyUsage(new KeyPurposeId[]{KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}))
					.build(signer);

			// Write private key and certificate to PEM strings
			String keyPem = toPemString(privateKey);
			String certPem = toPemString(certHolder);

			return new PemCertificateAndKey(certPem, keyPem);
		} catch (KeyConvertException e) {
			throw e;
		} catch (Exception e) {
			throw new KeyConvertException("Failed to convert key to PEM format key and certificate", e);
		}
	}

	private static String toPemString(Object obj) throws IOException {
		StringWriter sw = new StringWriter();
		try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
			writer.writeObject(obj);
		}
		return sw.toString();
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