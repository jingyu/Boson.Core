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

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * A hybrid {@link X509TrustManager} that supports both self-signed certificates and
 * certificates signed by a trusted CA.
 *
 * <p>For self-signed certificates, this trust manager validates the Common Name (CN)
 * and the public key against expected values. For other certificates, it delegates
 * to the system's default trust manager.</p>
 */
public class HybridTrustManager implements X509TrustManager {
	private final X509TrustManager defaultTrustManager;
	private final String expectedCn;
	private final byte[] expectedPublicKey;

	/**
	 * Creates a new {@code HybridTrustManager} with the specified expected CN and public key.
	 *
	 * @param expectedCn the expected Common Name (CN) in the certificate
	 * @param expectedPublicKey the expected public key bytes (last 32 bytes of SPKI)
	 */
	public HybridTrustManager(String expectedCn, byte[] expectedPublicKey) {
		this.defaultTrustManager = getDefaultTrustManager();
		this.expectedCn = expectedCn;
		this.expectedPublicKey = expectedPublicKey;
	}

	private static X509TrustManager getDefaultTrustManager() {
		try {
			TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			tmf.init((KeyStore) null);

			TrustManager[] trustManagers = tmf.getTrustManagers();
			for (TrustManager tm : trustManagers) {
				if (tm instanceof X509TrustManager)
					return (X509TrustManager) tm;
			}
		} catch (NoSuchAlgorithmException | KeyStoreException e) {
			throw new RuntimeException("JCE Error", e);
		}

		throw new RuntimeException("No system default TrustManager found");
	}

	/**
	 * Checks whether the provided server certificate chain can be trusted.
	 *
	 * <p>If the certificate is self-signed, it is validated against the expected CN
	 * and public key. Otherwise, the validation is delegated to the system default
	 * trust manager.</p>
	 *
	 * @param chain the certificate chain
	 * @param authType the authentication type based on the client certificate
	 * @throws CertificateException if the certificate chain is invalid or not trusted
	 */
	@Override
	public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		if (chain == null || chain.length == 0)
			throw new CertificateException("Null or empty certificate chain");

		X509Certificate cert = chain[0];
		boolean selfSigned = cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal());
		if (selfSigned) {
			// 1. validity check
			cert.checkValidity();

			// 2. verify self-signature
			try {
				cert.verify(cert.getPublicKey());
			} catch (Exception e) {
				throw new CertificateException("Invalid self signature", e);
			}

			// 3. Validate CN
			String dn = cert.getSubjectX500Principal().getName();
			LdapName ldapName = null;
			try {
				ldapName = new LdapName(dn);
			} catch (InvalidNameException e) {
				throw new CertificateException(e);
			}
			String cn = ldapName.getRdns().stream()
					.filter(r -> r.getType().equalsIgnoreCase("CN"))
					.map(r -> r.getValue().toString())
					.findFirst().orElseThrow(() -> new CertificateException("No CN in certificate"));
			if (!cn.equals(expectedCn))
				throw new CertificateException("CN mismatch");

			// 4. Validate public key
			PublicKey publicKey = cert.getPublicKey();
			byte[] spki = publicKey.getEncoded();
			byte[] pk = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
			if (!Arrays.equals(pk, expectedPublicKey))
				throw new CertificateException("Public key mismatch");
		} else {
			defaultTrustManager.checkServerTrusted(chain, authType);
		}
	}

	/**
	 * Checks whether the provided client certificate chain can be trusted.
	 *
	 * <p>This implementation delegates the check to the system default trust manager.</p>
	 *
	 * @param chain the certificate chain
	 * @param authType the key exchange algorithm used
	 * @throws CertificateException if the certificate chain is invalid or not trusted
	 */
	@Override
	public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		defaultTrustManager.checkClientTrusted(chain, authType);
	}

	/**
	 * Returns the list of certificate issuer authorities which are trusted for
	 * authenticating peers.
	 *
	 * @return a non-null (possibly empty) array of acceptable CA issuer certificates
	 */
	@Override
	public X509Certificate[] getAcceptedIssuers() {
		return defaultTrustManager.getAcceptedIssuers();
	}
}