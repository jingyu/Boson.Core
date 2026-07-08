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

import java.net.Socket;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

import org.jspecify.annotations.Nullable;

/**
 * A hybrid {@link X509TrustManager} that supports both self-signed certificates and
 * certificates signed by a trusted CA.
 *
 * <p>For self-signed certificates, this trust manager validates the Common Name (CN)
 * and the public key against expected values. For other certificates, it delegates
 * to the system's default trust manager.</p>
 *
 * <p>Extends {@link X509ExtendedTrustManager} so TLS stacks hand over the hostname-aware
 * ({@link Socket}/{@link SSLEngine}) check variants, which are forwarded to the default
 * trust manager for CA-signed chains. Android's network-security-config trust manager
 * REQUIRES the hostname-aware variants whenever the app has per-domain rules and rejects
 * the plain two-argument check outright.</p>
 */
public class HybridTrustManager extends X509ExtendedTrustManager {
	private final X509TrustManager defaultTrustManager;
	private final String expectedCn;
	private final byte @Nullable [] expectedPublicKey;

	/**
	 * Creates a new {@code HybridTrustManager} with the specified expected CN and public key.
	 *
	 * @param expectedCn the expected Common Name (CN) in the certificate
	 * @param expectedPublicKey the expected public key bytes (last 32 bytes of SPKI)
	 */
	public HybridTrustManager(String expectedCn, byte @Nullable [] expectedPublicKey) {
		this.defaultTrustManager = getDefaultTrustManager();
		this.expectedCn = expectedCn;
		this.expectedPublicKey = expectedPublicKey == null ? null : expectedPublicKey.clone();
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
		checkTrusted(chain, authType, false, null, null);
	}

	/**
	 * Socket-aware variant of {@link #checkServerTrusted(X509Certificate[], String)}: same pinning for
	 * self-signed chains; CA-signed chains are delegated with the socket so hostname-aware default
	 * trust managers (e.g. Android network security config) can apply per-domain rules.
	 *
	 * @param chain the certificate chain
	 * @param authType the authentication type based on the client certificate
	 * @param socket the socket used for this connection
	 * @throws CertificateException if the certificate chain is invalid or not trusted
	 */
	@Override
	public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
		checkTrusted(chain, authType, false, socket, null);
	}

	/**
	 * Engine-aware variant of {@link #checkServerTrusted(X509Certificate[], String)}: same pinning for
	 * self-signed chains; CA-signed chains are delegated with the engine so hostname-aware default
	 * trust managers (e.g. Android network security config) can apply per-domain rules.
	 *
	 * @param chain the certificate chain
	 * @param authType the authentication type based on the client certificate
	 * @param engine the SSL engine used for this connection
	 * @throws CertificateException if the certificate chain is invalid or not trusted
	 */
	@Override
	public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
		checkTrusted(chain, authType, false, null, engine);
	}

	/**
	 * Checks whether the provided client certificate chain can be trusted.
	 *
	 * <p>If the certificate is self-signed, it is validated against the expected CN and public key
	 * (same pinning as {@link #checkServerTrusted}). Otherwise, the validation is delegated to the
	 * system default trust manager.</p>
	 *
	 * @param chain the certificate chain
	 * @param authType the key exchange algorithm used
	 * @throws CertificateException if the certificate chain is invalid or not trusted
	 */
	@Override
	public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
		checkTrusted(chain, authType, true, null, null);
	}

	/**
	 * Socket-aware variant of {@link #checkClientTrusted(X509Certificate[], String)}; see
	 * {@link #checkServerTrusted(X509Certificate[], String, Socket)} for the delegation rationale.
	 *
	 * @param chain the certificate chain
	 * @param authType the key exchange algorithm used
	 * @param socket the socket used for this connection
	 * @throws CertificateException if the certificate chain is invalid or not trusted
	 */
	@Override
	public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
		checkTrusted(chain, authType, true, socket, null);
	}

	/**
	 * Engine-aware variant of {@link #checkClientTrusted(X509Certificate[], String)}; see
	 * {@link #checkServerTrusted(X509Certificate[], String, SSLEngine)} for the delegation rationale.
	 *
	 * @param chain the certificate chain
	 * @param authType the key exchange algorithm used
	 * @param engine the SSL engine used for this connection
	 * @throws CertificateException if the certificate chain is invalid or not trusted
	 */
	@Override
	public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
		checkTrusted(chain, authType, true, null, engine);
	}

	/**
	 * Shared trust check: pins self-signed certificates against the expected CN and public key,
	 * otherwise delegates to the system default trust manager (client or server side).
	 *
	 * @param chain    the certificate chain
	 * @param authType the authentication type
	 * @param client   {@code true} to delegate non-self-signed chains as a client cert, {@code false} as a server cert
	 * @param socket   the connection's socket, when checked through the socket-aware variant
	 * @param engine   the connection's SSL engine, when checked through the engine-aware variant
	 * @throws CertificateException if the certificate chain is invalid or not trusted
	 */
	private void checkTrusted(X509Certificate[] chain, String authType, boolean client,
			@Nullable Socket socket, @Nullable SSLEngine engine) throws CertificateException {
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

			CertUtil.IdentityBinding binding = extractIdentityBinding(cert);
			if (binding != null)
				// Modern: a browser-compatible ECDSA certificate carrying an Ed25519 identity binding.
				verifyIdentityBinding(cert, binding);
			else
				// Legacy: an Ed25519 identity certificate pinned directly by CN and raw public key.
				verifyLegacyEd25519Pin(cert);
		} else {
			delegateToDefault(chain, authType, client, socket, engine);
		}
	}

	/**
	 * Delegates a CA-signed chain to the system default trust manager, preferring the hostname-aware
	 * {@link X509ExtendedTrustManager} variants when both sides support them. Android's
	 * network-security-config trust manager throws on the plain two-argument check whenever the app
	 * declares per-domain rules, so the socket/engine context must be passed through when available.
	 *
	 * @param chain    the certificate chain
	 * @param authType the authentication type
	 * @param client   {@code true} to check as a client cert, {@code false} as a server cert
	 * @param socket   the connection's socket, or {@code null}
	 * @param engine   the connection's SSL engine, or {@code null}
	 * @throws CertificateException if the certificate chain is invalid or not trusted
	 */
	private void delegateToDefault(X509Certificate[] chain, String authType, boolean client,
			@Nullable Socket socket, @Nullable SSLEngine engine) throws CertificateException {
		if (defaultTrustManager instanceof X509ExtendedTrustManager extended) {
			if (engine != null) {
				if (client)
					extended.checkClientTrusted(chain, authType, engine);
				else
					extended.checkServerTrusted(chain, authType, engine);
				return;
			}
			if (socket != null) {
				if (client)
					extended.checkClientTrusted(chain, authType, socket);
				else
					extended.checkServerTrusted(chain, authType, socket);
				return;
			}
		}
		if (client)
			defaultTrustManager.checkClientTrusted(chain, authType);
		else
			defaultTrustManager.checkServerTrusted(chain, authType);
	}

	/**
	 * Extracts a Boson identity binding from the certificate's standard {@code issuerAltName} extension,
	 * parsed with the JDK ({@link X509Certificate#getIssuerAlternativeNames()}) so no ASN.1 library is
	 * required on the verify path.
	 *
	 * @param cert the certificate
	 * @return the parsed binding, or {@code null} if the certificate carries no Boson binding URI
	 * @throws CertificateException if the issuerAltName extension is malformed
	 */
	private static CertUtil.@Nullable IdentityBinding extractIdentityBinding(X509Certificate cert) throws CertificateException {
		Collection<List<?>> names;
		try {
			names = cert.getIssuerAlternativeNames();
		} catch (CertificateParsingException e) {
			throw new CertificateException("Malformed issuerAltName extension", e);
		}
		if (names == null)
			return null;

		for (List<?> entry : names) {
			// GeneralName type 6 == uniformResourceIdentifier; value is the URI String.
			if (entry.size() >= 2 && entry.get(0) instanceof Integer type && type == 6
					&& entry.get(1) instanceof String uri) {
				CertUtil.IdentityBinding binding = CertUtil.parseIdentityBinding(uri);
				if (binding != null)
					return binding;
			}
		}
		return null;
	}

	/**
	 * Verifies the Boson Ed25519 identity binding carried by a self-signed certificate. The binding proves
	 * that the holder of the expected Ed25519 identity key endorsed this certificate's public key, so the
	 * TLS key itself need not be the identity key. This is what allows browser-compatible ECDSA
	 * certificates to be pinned to a Boson id.
	 *
	 * @param cert    the self-signed certificate
	 * @param binding the parsed identity binding
	 * @throws CertificateException if the binding does not match or verify against the expected identity
	 */
	private void verifyIdentityBinding(X509Certificate cert, CertUtil.IdentityBinding binding) throws CertificateException {
		if (expectedPublicKey == null)
			throw new CertificateException("No expected identity to verify certificate binding against");

		if (!Arrays.equals(binding.publicKey(), expectedPublicKey))
			throw new CertificateException("Identity binding public key mismatch");

		// The binding signature covers the certificate's SubjectPublicKeyInfo DER encoding.
		byte[] spki = cert.getPublicKey().getEncoded();
		boolean valid;
		try {
			valid = Signature.verify(spki, binding.signature(), Signature.PublicKey.fromBytes(binding.publicKey()));
		} catch (Exception e) {
			throw new CertificateException("Invalid identity binding signature", e);
		}
		if (!valid)
			throw new CertificateException("Identity binding signature verification failed");
	}

	/**
	 * Legacy pinning for self-signed Ed25519 identity certificates: the CN must equal the expected id
	 * and the raw Ed25519 public key (last 32 bytes of the SPKI) must equal the expected public key.
	 *
	 * @param cert the self-signed certificate
	 * @throws CertificateException if the CN or public key does not match the expected identity
	 */
	private void verifyLegacyEd25519Pin(X509Certificate cert) throws CertificateException {
		// Validate CN
		String dn = cert.getSubjectX500Principal().getName();
		String cn = extractCn(dn);
		if (cn == null)
			throw new CertificateException("No CN in certificate");
		if (!cn.equals(expectedCn))
			throw new CertificateException("CN mismatch");

		// Validate public key (Ed25519 raw key = last 32 bytes of the SPKI encoding)
		PublicKey publicKey = cert.getPublicKey();
		byte[] spki = publicKey.getEncoded();
		if (spki == null || spki.length < 32)
			throw new CertificateException("Unexpected public key encoding");
		byte[] pk = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
		if (!Arrays.equals(pk, expectedPublicKey))
			throw new CertificateException("Public key mismatch");
	}

	/**
	 * Extracts the Common Name (CN) value from an RFC 2253 distinguished name, as returned by
	 * {@link javax.security.auth.x500.X500Principal#getName()}.
	 *
	 * <p>This intentionally avoids {@code javax.naming.ldap.LdapName}, which is unavailable on Android.
	 * It handles backslash escapes and double-quoted values, and stops an attribute value at an
	 * unescaped RDN separator ({@code ,} or {@code +}).
	 *
	 * @param dn the RFC 2253 distinguished name
	 * @return the first CN value found, or {@code null} if the DN has no CN attribute
	 */
	private static @Nullable String extractCn(String dn) {
		int i = 0;
		final int n = dn.length();
		while (i < n) {
			int eq = i;
			while (eq < n && dn.charAt(eq) != '=')
				eq++;
			if (eq >= n)
				break;

			String type = dn.substring(i, eq).trim();
			StringBuilder value = new StringBuilder();
			int j = eq + 1;
			boolean quoted = false;
			while (j < n) {
				char c = dn.charAt(j);
				if (c == '\\' && j + 1 < n) {
					value.append(dn.charAt(j + 1));
					j += 2;
					continue;
				}
				if (c == '"') {
					quoted = !quoted;
					j++;
					continue;
				}
				if (!quoted && (c == ',' || c == '+'))
					break;
				value.append(c);
				j++;
			}

			if (type.equalsIgnoreCase("CN"))
				return value.toString().trim();

			i = j + 1;
		}
		return null;
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