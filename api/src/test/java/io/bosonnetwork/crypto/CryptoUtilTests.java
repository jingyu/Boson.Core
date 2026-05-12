package io.bosonnetwork.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.utils.Base58;

public class CryptoUtilTests {
	@BeforeAll
	public static void setup() {
		CertUtilBouncyCastle.init();
	}

	@Test
	public void testCertificateFromSignatureKeyBCWithIP() throws Exception {
		Signature.KeyPair kp = Signature.KeyPair.random();
		String ipAddress = "127.0.0.1";

		CertUtilBouncyCastle.PemCertificateAndKey result = CertUtilBouncyCastle.certificateFromSignatureKey(kp.privateKey(), ipAddress, null, false);

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

		CertUtilBouncyCastle.PemCertificateAndKey result = CertUtilBouncyCastle.certificateFromSignatureKey(kp.privateKey(), null, hostName, true);

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

		CertUtilBouncyCastle.PemCertificateAndKey result = CertUtilBouncyCastle.certificateFromSignatureKey(kp.privateKey(), ipAddress, hostName, true);

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

		assertThrows(CertUtilBouncyCastle.KeyConvertException.class, () ->
				CertUtilBouncyCastle.certificateFromSignatureKey(kp.privateKey(), null, null, false)
		);
	}

	@Test
	public void testCertificateFromSignatureKey() throws Exception {
		Signature.KeyPair kp = Signature.KeyPair.random();
		String ipAddress = "127.0.0.1";
		String hostName = "localhost";

		CryptoUtil.PemCertificateAndKey result = CryptoUtil.certificateFromSignatureKey(kp.privateKey(), ipAddress, hostName, true);

		assertNotNull(result);
		assertNotNull(result.cert());
		assertNotNull(result.privateKey());

		System.out.println("Simple Implementation Result:");
		System.out.println(result.cert());
		System.out.println(result.privateKey());

		assertTrue(result.cert().contains("-----BEGIN CERTIFICATE-----"));
		assertTrue(result.privateKey().contains("-----BEGIN PRIVATE KEY-----"));

		// Compare with reference implementation
		CertUtilBouncyCastle.PemCertificateAndKey ref = CertUtilBouncyCastle.certificateFromSignatureKey(kp.privateKey(), ipAddress, hostName, true);

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
}