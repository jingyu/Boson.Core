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
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.PfxOptions;
import org.jspecify.annotations.Nullable;

/**
 * Utility class for certificate and key management.
 */
public class CertUtil {
	private CertUtil() {}

	/**
	 * Generates a self-signed X.509 certificate and private key from a signature private key without Bouncy Castle.
	 *
	 * @param privateKey     the signature private key
	 * @param ipAddress      the IP address to include in the Subject Alternative Name (SAN), or
	 *                       {@code null} to omit an IP SAN entry
	 * @param hostName       the host name to include in the Subject Alternative Name (SAN), or
	 *                       {@code null} to omit a DNS SAN entry
	 * @param enableWildcard whether to include a wildcard host name in the SAN
	 * @return a {@link PemCertificateAndKey} containing the PEM-encoded certificate and private key
	 * @throws CryptoException if an error occurs during key conversion or certificate generation
	 */
	public static PemCertificateAndKey certificateFromSignatureKey(Signature.PrivateKey privateKey, @Nullable String ipAddress,
	                                                               @Nullable String hostName, boolean enableWildcard) throws CryptoException {
		Objects.requireNonNull(privateKey, "privateKey");
		if (ipAddress == null && hostName == null)
			throw new IllegalArgumentException("At least one SAN entry must be specified");

		return CryptoProviders.getDefault().certificateFromSignatureKey(privateKey, ipAddress, hostName, enableWildcard);
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
	 * Generates a random password containing a mix of uppercase and lowercase letters, digits, and special characters.
	 *
	 * @param length the length of the password to generate; must be a positive integer
	 * @return a randomly generated password as a String
	 * @throws IllegalArgumentException if the specified length is not positive
	 */
	private static String randomPassword(int length) {
		if (length <= 0)
			throw new IllegalArgumentException("Length must be positive");

		String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()_-+=<>?/|";
		StringBuilder sb = new StringBuilder(length);
		SecureRandom random = new SecureRandom();
		for (int i = 0; i < length; i++) {
			int index = random.nextInt(characters.length());
			sb.append(characters.charAt(index));
		}

		return sb.toString();
	}
}