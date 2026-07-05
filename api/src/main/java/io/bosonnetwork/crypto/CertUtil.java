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
import java.net.URI;
import java.net.URISyntaxException;
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

import io.netty.util.NetUtil;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PfxOptions;
import org.jspecify.annotations.Nullable;

import io.bosonnetwork.utils.Base58;

/**
 * Utility class for certificate and key management.
 */
public class CertUtil {
	/**
	 * URI scheme/prefix of the Boson Ed25519 identity binding carried in a certificate's standard
	 * {@code issuerAltName} extension (RFC 5280) as a {@code uniformResourceIdentifier} GeneralName.
	 * The full URI is {@code boson:ed25519:<base58 public key>:<base64url signature>}, where the
	 * signature is an Ed25519 signature over the certificate's ECDSA {@code SubjectPublicKeyInfo} DER.
	 * Using a standard extension lets verifiers parse it with the JDK ({@code getIssuerAlternativeNames})
	 * without Bouncy Castle, and avoids a private OID.
	 */
	public static final String ID_BINDING_URI_PREFIX = "boson:ed25519:";

	private CertUtil() {}

	/**
	 * Formats a Boson identity binding URI from an Ed25519 public key and a signature over the
	 * certificate's {@code SubjectPublicKeyInfo}.
	 *
	 * @param publicKey the Ed25519 public key bytes (the Boson id)
	 * @param signature the Ed25519 signature over the certificate SPKI
	 * @return the binding URI, suitable for an {@code issuerAltName} URI GeneralName
	 */
	public static String formatIdentityBinding(byte[] publicKey, byte[] signature) {
		return ID_BINDING_URI_PREFIX + Base58.encode(publicKey) + ":"
				+ Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
	}

	/**
	 * Parsed Boson identity binding: the endorsing Ed25519 public key and its signature over the
	 * certificate's {@code SubjectPublicKeyInfo}.
	 *
	 * @param publicKey the Ed25519 public key bytes
	 * @param signature the Ed25519 signature bytes
	 */
	public record IdentityBinding(byte[] publicKey, byte[] signature) {}

	/**
	 * Parses a Boson identity binding URI produced by {@link #formatIdentityBinding(byte[], byte[])}.
	 *
	 * @param uri the candidate URI (for example, an {@code issuerAltName} URI GeneralName value)
	 * @return the parsed {@link IdentityBinding}, or {@code null} if {@code uri} is not a Boson binding
	 * @throws IllegalArgumentException if the URI has the Boson prefix but is malformed
	 */
	public static @Nullable IdentityBinding parseIdentityBinding(@Nullable String uri) {
		if (uri == null || !uri.startsWith(ID_BINDING_URI_PREFIX))
			return null;
		String[] parts = uri.substring(ID_BINDING_URI_PREFIX.length()).split(":");
		if (parts.length != 2)
			throw new IllegalArgumentException("Malformed Boson identity binding URI");
		byte[] publicKey = Base58.decode(parts[0]);
		byte[] signature = Base64.getUrlDecoder().decode(parts[1]);
		return new IdentityBinding(publicKey, signature);
	}

	/**
	 * Generates a self-signed X.509 certificate and private key from a signature private key without Bouncy Castle.
	 *
	 * @param privateKey     the signature private key
	 * @param ipAddress      the IP address to include in the Subject Alternative Name (SAN), or
	 *                       {@code null} to omit an IP SAN entry
	 * @param hostName       the host name to include in the Subject Alternative Name (SAN), or
	 *                       {@code null} to omit a DNS SAN entry
	 * @param enableWildcard whether to include a wildcard host name in the SAN
	 * @return a {@link PemKeyCertificate} containing the PEM-encoded certificate and private key
	 * @throws CryptoException if an error occurs during key conversion or certificate generation
	 */
	public static PemKeyCertificate certificateFromSignatureKey(Signature.PrivateKey privateKey, @Nullable String ipAddress,
	                                                            @Nullable String hostName, boolean enableWildcard) throws CryptoException {
		Objects.requireNonNull(privateKey, "privateKey");
		if (ipAddress == null && hostName == null)
			throw new IllegalArgumentException("At least one SAN entry must be specified");

		return CryptoProviders.getDefault().certificateFromSignatureKey(privateKey, ipAddress, hostName, enableWildcard);
	}

	/**
	 * Generates a self-signed X.509 certificate backed by a freshly generated ECDSA P-256 (secp256r1) key pair.
	 * <p>
	 * Unlike {@link #certificateFromSignatureKey}, this certificate is <b>not</b> derived from a Boson node
	 * identity key. It exists specifically for browser-facing HTTPS endpoints: mainstream browsers
	 * (Chrome/BoringSSL, Firefox, Safari) do not support Ed25519 server certificates, so an Ed25519 leaf
	 * certificate fails the TLS handshake with "No available authentication scheme". ECDSA P-256 is the
	 * closest widely supported equivalent and is accepted by every current browser.
	 * <p>
	 * At least one Subject Alternative Name (SAN) entry must be produced: if both {@code ipAddress} and
	 * {@code hostName} are {@code null}, this method throws {@link IllegalArgumentException}.
	 *
	 * @param ipAddress      the IP address to include in the Subject Alternative Name (SAN), or
	 *                       {@code null} to omit an IP SAN entry
	 * @param hostName       the host name to include in the Subject Alternative Name (SAN), or
	 *                       {@code null} to omit a DNS SAN entry
	 * @param enableWildcard whether to include a wildcard host name in the SAN
	 * @return a {@link PemKeyCertificate} containing the PEM-encoded certificate and PKCS#8 private key
	 * @throws CryptoException if an error occurs during key generation or certificate generation
	 */
	public static PemKeyCertificate certificateSelfSignedEcdsa(@Nullable String ipAddress,
	                                                           @Nullable String hostName, boolean enableWildcard) throws CryptoException {
		return certificateSelfSignedEcdsa(ipAddress, hostName, enableWildcard, null);
	}

	/**
	 * Generates a self-signed ECDSA P-256 certificate that additionally carries an Ed25519 identity
	 * binding, allowing Boson peers to authenticate the certificate against a known node/service id
	 * while remaining a plain browser-compatible ECDSA certificate.
	 * <p>
	 * When {@code identityKey} is non-null, a Boson identity binding is added to the certificate's
	 * standard {@code issuerAltName} extension as a {@code boson:ed25519:...} URI (see
	 * {@link #ID_BINDING_URI_PREFIX}); the Ed25519 signature covers the certificate's ECDSA
	 * {@code SubjectPublicKeyInfo} DER. This cryptographically binds the ephemeral ECDSA key to the Boson
	 * identity, so {@link HybridTrustManager} can pin the identity even though the TLS key is not the
	 * identity key. Browsers ignore the extension. Certificate assembly is delegated to the crypto provider.
	 *
	 * @param ipAddress      the IP address SAN entry, or {@code null}
	 * @param hostName       the host name SAN entry, or {@code null}
	 * @param enableWildcard whether to include a wildcard host name in the SAN
	 * @param identityKey    the Ed25519 identity key to bind the certificate to, or {@code null} for none
	 * @return a {@link PemKeyCertificate} containing the PEM-encoded certificate and PKCS#8 private key
	 * @throws CryptoException if an error occurs during key generation or certificate generation
	 */
	public static PemKeyCertificate certificateSelfSignedEcdsa(@Nullable String ipAddress, @Nullable String hostName,
	                                                           boolean enableWildcard, Signature.@Nullable PrivateKey identityKey) throws CryptoException {
		if (ipAddress == null && hostName == null)
			throw new IllegalArgumentException("At least one SAN entry must be specified");

		return CryptoProviders.getDefault().certificateSelfSignedEcdsa(ipAddress, hostName, enableWildcard, identityKey);
	}

	/**
	 * Generates a self-signed ECDSA P-256 certificate whose SAN matches the host of the given endpoint URL,
	 * bound to the supplied Boson identity key (see {@link #certificateSelfSignedEcdsa}).
	 * <p>
	 * This is the fallback path taken by the Director portal and by each service when the host provides no
	 * user-provisioned certificate (that is, when
	 * {@link io.bosonnetwork.service.ServiceContext#getKeyCertificate()} returns {@code null}). The endpoint's
	 * host component becomes the certificate SAN: a literal IPv4/IPv6 address is emitted as an IP SAN,
	 * otherwise it is emitted as a DNS host SAN. Because the Ed25519 binding covers {@code identityKey},
	 * pinning clients authenticate the endpoint by its Boson identity regardless of the ephemeral TLS key.
	 *
	 * @param endpoint    the public endpoint URL whose host/IP becomes the certificate SAN
	 * @param identityKey the Ed25519 identity key to bind the certificate to
	 * @return a {@link PemKeyCertificate} containing the PEM-encoded certificate and PKCS#8 private key
	 * @throws CryptoException if the endpoint is malformed or certificate generation fails
	 */
	public static PemKeyCertificate selfSignedEcdsaFor(String endpoint, Signature.PrivateKey identityKey) throws CryptoException {
		String host;
		try {
			host = new URI(endpoint).getHost();
		} catch (URISyntaxException e) {
			throw new CryptoException("Invalid endpoint URI: " + endpoint, e);
		}

		String ipAddress = null;
		String hostName = null;
		if (NetUtil.isValidIpV4Address(host) || NetUtil.isValidIpV6Address(host))
			ipAddress = host;
		else
			hostName = host;

		return certificateSelfSignedEcdsa(ipAddress, hostName, false, identityKey);
	}

	/**
	 * Creates a {@link PemKeyCertOptions} instance from a pair of PEM-encoded certificate and private key.
	 * <p>
	 * Unlike {@link #pfxOptionsFromCertAndPrivateKey}, this does not repackage the material into a PKCS#12
	 * keystore. Netty accepts PEM PKCS#8 EC and RSA private keys directly, so this is the preferred path for
	 * ECDSA (see {@link #certificateSelfSignedEcdsa}) and for real certificates loaded from PEM files. It is
	 * <b>not</b> suitable for Ed25519 keys, which Netty cannot parse from PEM - use
	 * {@link #pfxOptionsFromCertAndPrivateKey} for those.
	 *
	 * @param certAndKey the {@link PemKeyCertificate} containing the PEM-encoded certificate and private key
	 * @return a {@link PemKeyCertOptions} referencing the provided certificate and private key
	 */
	public static PemKeyCertOptions pemKeyCertOptions(PemKeyCertificate certAndKey) {
		return new PemKeyCertOptions()
				.setKeyValue(Buffer.buffer(certAndKey.privateKey()))
				.setCertValue(Buffer.buffer(certAndKey.cert()));
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
	 * @param certAndKey the {@link PemKeyCertificate} containing the PEM-encoded certificate and private key
	 * @return a {@link PfxOptions} containing a PKCS#12 keystore created from the provided certificate and private key
	 * @throws InvalidKeySpecException if the private key could not be parsed correctly
	 * @throws NoSuchAlgorithmException if the "Ed25519" algorithm required for the private key is not available
	 * @throws CertificateException if the certificate could not be parsed correctly
	 * @throws KeyStoreException if an error occurs while accessing or modifying the keystore
	 */
	public static PfxOptions pfxOptionsFromCertAndPrivateKey(PemKeyCertificate certAndKey)
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
		// so we must specify "Ed25519" explicitly for the KeyFactory. This PKCS#12 detour exists
		// solely because Netty rejects PEM PKCS#8 Ed25519 keys; EC/RSA certs use pemKeyCertOptions().
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