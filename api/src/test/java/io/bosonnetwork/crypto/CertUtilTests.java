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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.utils.Base58;

public class CertUtilTests {
	@BeforeAll
	static void setup() {
		Security.addProvider(new BouncyCastleProvider());
	}

	@Test
	public void testCertificateFromSignatureKeyBCWithIP() throws Exception {
		Signature.KeyPair kp = Signature.KeyPair.random();
		String ipAddress = "127.0.0.1";

		PemKeyCertificate result = CertUtil.certificateFromSignatureKey(kp.privateKey(), ipAddress, null, false);

		assertNotNull(result);
		assertNotNull(result.cert());
		assertNotNull(result.privateKey());

		System.out.println(result.cert());
		System.out.println(result.privateKey());

		assertTrue(result.cert().contains("-----BEGIN CERTIFICATE-----"));
		assertTrue(result.cert().contains("-----END CERTIFICATE-----"));
		assertTrue(result.privateKey().contains("-----BEGIN PRIVATE KEY-----"));
		assertTrue(result.privateKey().contains("-----END PRIVATE KEY-----"));
	}

	@Test
	public void testCertificateFromSignatureKeyBCWithHostName() throws Exception {
		Signature.KeyPair kp = Signature.KeyPair.random();
		String hostName = "localhost";

		PemKeyCertificate result = CertUtil.certificateFromSignatureKey(kp.privateKey(), null, hostName, true);

		assertNotNull(result);
		assertNotNull(result.cert());
		assertNotNull(result.privateKey());

		System.out.println(result.cert());
		System.out.println(result.privateKey());

		assertTrue(result.cert().contains("-----BEGIN CERTIFICATE-----"));
		assertTrue(result.cert().contains("-----END CERTIFICATE-----"));
		assertTrue(result.privateKey().contains("-----BEGIN PRIVATE KEY-----"));
		assertTrue(result.privateKey().contains("-----END PRIVATE KEY-----"));
	}

	@Test
	public void testCertificateFromSignatureKeyBCWithBoth() throws Exception {
		Signature.KeyPair kp = Signature.KeyPair.random();
		String ipAddress = "127.0.0.1";
		String hostName = "localhost";

		PemKeyCertificate result = CertUtil.certificateFromSignatureKey(kp.privateKey(), ipAddress, hostName, true);

		assertNotNull(result);
		assertNotNull(result.cert());
		assertNotNull(result.privateKey());

		System.out.println(result.cert());
		System.out.println(result.privateKey());

		assertTrue(result.cert().contains("-----BEGIN CERTIFICATE-----"));
		assertTrue(result.privateKey().contains("-----BEGIN PRIVATE KEY-----"));
	}

	@Test
	public void testCertificateFromSignatureKeyBCNoSAN() {
		Signature.KeyPair kp = Signature.KeyPair.random();

		assertThrows(IllegalArgumentException.class, () ->
				CertUtil.certificateFromSignatureKey(kp.privateKey(), null, null, false)
		);
	}

	@Test
	public void testCertificateFromSignatureKey() throws Exception {
		Signature.KeyPair kp = Signature.KeyPair.random();
		String ipAddress = "127.0.0.1";
		String hostName = "localhost";

		PemKeyCertificate result = CertUtil.certificateFromSignatureKey(kp.privateKey(), ipAddress, hostName, true);

		assertNotNull(result);
		assertNotNull(result.cert());
		assertNotNull(result.privateKey());

		System.out.println("Simple Implementation Result:");
		System.out.println(result.cert());
		System.out.println(result.privateKey());

		assertTrue(result.cert().contains("-----BEGIN CERTIFICATE-----"));
		assertTrue(result.privateKey().contains("-----BEGIN PRIVATE KEY-----"));

		// Compare with reference implementation
		PemKeyCertificate ref = CertUtil.certificateFromSignatureKey(kp.privateKey(), ipAddress, hostName, true);

		System.out.println("Reference Implementation Result:");
		System.out.println(ref.cert());
		System.out.println(ref.privateKey());

		// the private key should be identical
		assertEquals(ref.privateKey(), result.privateKey());

		// Verify the certificate can be parsed by standard JDK CertificateFactory
		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(result.cert().getBytes()));
		assertNotNull(cert);
		assertEquals(3, cert.getVersion());
		assertEquals("X.509", cert.getType());
		cert.checkValidity();
		assertEquals("Ed25519", cert.getSigAlgName());
		Instant now = Instant.now();
		Date notBefore = Date.from(now.minus(10, ChronoUnit.MINUTES));
		Date notAfter = Date.from(now.plus(3650, ChronoUnit.DAYS));
		assertTrue(cert.getNotAfter().getTime() <= notAfter.getTime());
		assertTrue(cert.getNotAfter().getTime() > Date.from(now.plus(3649, ChronoUnit.DAYS)).getTime());
		assertTrue(cert.getNotBefore().getTime() <= notBefore.getTime());
		assertTrue(cert.getNotBefore().getTime() > Date.from(now.minus(11, ChronoUnit.MINUTES)).getTime());

		assertEquals("CN=" + Base58.encode(kp.publicKey().bytes()), cert.getSubjectX500Principal().getName());

		// Verify it can be used for signature verification
		cert.verify(cert.getPublicKey());

		X509Certificate refCert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(ref.cert().getBytes()));

		// Verify Subject Key Identifier extension
		byte[] extValue = cert.getExtensionValue("2.5.29.14");
		assertNotNull(extValue, "Subject Key Identifier extension should be present");
		byte[] refExtValue = refCert.getExtensionValue("2.5.29.14");
		assertArrayEquals(refExtValue, extValue, "SKI should be identical to BouncyCastle's");

		// Verify Key Usage extension
		extValue = cert.getExtensionValue("2.5.29.15");
		assertNotNull(extValue, "Key Usage extension should be present");
		refExtValue = refCert.getExtensionValue("2.5.29.15");
		assertArrayEquals(refExtValue, extValue, "KU should be identical to BouncyCastle's");

		// Subject Alt Names extension
		extValue = cert.getExtensionValue("2.5.29.17");
		assertNotNull(extValue, "Subject Alt Names extension should be present");
		refExtValue = refCert.getExtensionValue("2.5.29.17");
		assertArrayEquals(refExtValue, extValue, "SAN should be identical to BouncyCastle's");

		// Verify Basic Constraints extension
		extValue = cert.getExtensionValue("2.5.29.19");
		assertNotNull(extValue, "Basic Constraints extension should be present");
		refExtValue = refCert.getExtensionValue("2.5.29.19");
		assertArrayEquals(refExtValue, extValue, "BC should be identical to BouncyCastle's");

		// Verify Extended Key Usage extension
		extValue = cert.getExtensionValue("2.5.29.37");
		assertNotNull(extValue, "Extended Key Usage extension should be present");
		refExtValue = refCert.getExtensionValue("2.5.29.37");
		assertArrayEquals(refExtValue, extValue, "EKU should be identical to BouncyCastle's");
	}

	@Test
	public void testCertificateSelfSignedEcdsa() throws Exception {
		String ipAddress = "127.0.0.1";
		String hostName = "jmac.dev";

		PemKeyCertificate result = CertUtil.certificateSelfSignedEcdsa(ipAddress, hostName, false);

		System.out.println(result.cert());
		System.out.println(result.privateKey());

		assertNotNull(result);
		assertTrue(result.cert().contains("-----BEGIN CERTIFICATE-----"));
		assertTrue(result.privateKey().contains("-----BEGIN PRIVATE KEY-----"));

		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		X509Certificate cert = (X509Certificate) cf.generateCertificate(
				new ByteArrayInputStream(result.cert().getBytes()));
		assertNotNull(cert);
		assertEquals(3, cert.getVersion());
		cert.checkValidity();

		// The certificate must be ECDSA (browser-compatible), not Ed25519, and use a P-256 key.
		assertEquals("SHA256WITHECDSA", cert.getSigAlgName().toUpperCase());
		assertEquals("EC", cert.getPublicKey().getAlgorithm());
		assertEquals("CN=" + hostName, cert.getSubjectX500Principal().getName());

		// It is self-signed and self-verifiable.
		cert.verify(cert.getPublicKey());

		// SAN must be present (browsers reject CN-only certs).
		assertNotNull(cert.getExtensionValue("2.5.29.17"), "SAN should be present");

		// ECDSA PEM keys are consumed directly via PemKeyCertOptions (no PKCS#12 detour needed).
		assertNotNull(CertUtil.pemKeyCertOptions(result));
	}

	@Test
	public void testCertificateSelfSignedEcdsaNoSAN() {
		assertThrows(IllegalArgumentException.class, () ->
				CertUtil.certificateSelfSignedEcdsa(null, null, false)
		);
	}

	@Test
	public void testEcdsaIdentityBindingVerifies() throws Exception {
		Signature.KeyPair id = Signature.KeyPair.random();
		PemKeyCertificate result = CertUtil.certificateSelfSignedEcdsa("127.0.0.1", "localhost", false, id.privateKey());

		System.out.println(Id.of(id.publicKey().bytes()));
		System.out.println(result.cert());
		System.out.println(result.privateKey());

		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		X509Certificate cert = (X509Certificate) cf.generateCertificate(
				new ByteArrayInputStream(result.cert().getBytes()));
		X509Certificate[] chain = {cert};

		// The cert is a browser-compatible ECDSA cert carrying the Boson identity binding in issuerAltName.
		assertEquals("EC", cert.getPublicKey().getAlgorithm());
		assertNotNull(cert.getIssuerAlternativeNames(), "issuerAltName should be present");
		String idBindingUri = cert.getIssuerAlternativeNames().stream()
				.filter(e -> e.get(1) instanceof String s && s.startsWith(CertUtil.ID_BINDING_URI_PREFIX))
				.map(e -> (String) e.get(1))
				.findFirst()
				.orElse(null);
		assertNotNull(idBindingUri, "binding URI should be present in issuerAltName");
		CertUtil.IdentityBinding binding = CertUtil.parseIdentityBinding(idBindingUri);
		assertNotNull(binding, "binding should be parseable");
		assertArrayEquals(id.publicKey().bytes(), binding.publicKey());
		Signature.PublicKey.fromBytes(binding.publicKey()).verify(id.publicKey().bytes(), binding.signature());

		// A trust manager pinning the correct identity accepts it.
		HybridTrustManager good = new HybridTrustManager("cn-ignored-for-binding", id.publicKey().bytes());
		assertDoesNotThrow(() -> good.checkServerTrusted(chain, "ECDHE_ECDSA"));

		// A trust manager pinning a different identity rejects it.
		Signature.KeyPair other = Signature.KeyPair.random();
		HybridTrustManager bad = new HybridTrustManager("cn-ignored-for-binding", other.publicKey().bytes());
		assertThrows(CertificateException.class, () -> bad.checkServerTrusted(chain, "ECDHE_ECDSA"));
	}

	@Test
	public void testLegacyEd25519PinStillVerifies() throws Exception {
		// Backward compatibility: self-signed Ed25519 identity certs (no binding extension) must still
		// validate through the legacy CN + raw-public-key pinning path.
		Signature.KeyPair kp = Signature.KeyPair.random();
		PemKeyCertificate result = CertUtil.certificateFromSignatureKey(kp.privateKey(), "127.0.0.1", "localhost", false);

		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		X509Certificate cert = (X509Certificate) cf.generateCertificate(
				new ByteArrayInputStream(result.cert().getBytes()));
		X509Certificate[] chain = {cert};

		assertNull(cert.getIssuerAlternativeNames(), "legacy cert has no issuerAltName binding");

		String expectedCn = Base58.encode(kp.publicKey().bytes());
		HybridTrustManager good = new HybridTrustManager(expectedCn, kp.publicKey().bytes());
		assertDoesNotThrow(() -> good.checkServerTrusted(chain, "ECDHE_ECDSA"));

		Signature.KeyPair other = Signature.KeyPair.random();
		HybridTrustManager bad = new HybridTrustManager(Base58.encode(other.publicKey().bytes()), other.publicKey().bytes());
		assertThrows(CertificateException.class, () -> bad.checkServerTrusted(chain, "ECDHE_ECDSA"));
	}
}