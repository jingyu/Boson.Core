package io.bosonnetwork.identifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.InvalidSignatureException;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.utils.Hex;

public class VerifiablePresentationTests {
	private static final long DAY = 24 * 60 * 60 * 1000;

	@Test
	void simpleVPTest() {
		var identity = new CryptoIdentity();

		var vp = new VerifiablePresentationBuilder(identity)
				.addCredential("profile", "BosonProfile", List.of("https://example.com/credentials/profile/v1"),
						"name", "Bob", "avatar", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==")
				.build();

		System.out.println(vp);
		System.out.println(vp.toPrettyString());
		System.out.println(Hex.encode(vp.toBytes()));

		assertEquals(3, vp.getContexts().size());
		assertEquals(DIDConstants.W3C_VC_CONTEXT, vp.getContexts().get(0));
		assertEquals(DIDConstants.BOSON_VC_CONTEXT, vp.getContexts().get(1));
		assertEquals(DIDConstants.W3C_ED25519_CONTEXT, vp.getContexts().get(2));

		assertEquals(identity.getId(), vp.getHolder());

		assertEquals(1, vp.getCredentials().size());
		assertNotNull(vp.getCredential("profile"));
		assertNotNull(vp.getCredential(vp.getHolder().toDIDString() + "#profile"));
		assertEquals(1, vp.getCredentials("BosonProfile").size());

		var credProfile = vp.getCredential("profile");
		assertTrue(credProfile.isGenuine());
		assertTrue(credProfile.isValid());
		assertTrue(credProfile.selfIssued());

		assertTrue(vp.isGenuine());
		assertDoesNotThrow(vp::validate);

		var json = vp.toString();
		var vp2 = VerifiablePresentation.parse(json);
		assertEquals(vp, vp2);
		assertEquals(vp.toString(), vp2.toString());

		var bytes = vp.toBytes();
		var vp3 = VerifiablePresentation.parse(bytes);
		assertEquals(vp, vp3);
		assertEquals(vp.toString(), vp3.toString());

		var vouch = vp.toVouch();

		System.out.println(vouch);
		System.out.println(vouch.toPrettyString());
		System.out.println(Hex.encode(vouch.toBytes()));

		assertEquals(1, vouch.getCredentials().size());

		assertInstanceOf(VerifiablePresentation.BosonVouch.class, vouch);
		assertSame(vp, ((VerifiablePresentation.BosonVouch) vouch).getVerifiablePresentation());
		assertSame(vp, VerifiablePresentation.fromVouch(vouch,
				Map.of("BosonProfile", List.of("https://example.com/credentials/profile/v1"))));

		assertTrue(vouch.isGenuine());
		assertDoesNotThrow(vouch::validate);

		var vouch2 = Vouch.parse(vouch.toBytes());
		assertEquals(vouch, vouch2); // Object equality
		assertEquals(vouch.toString(), vouch2.toString()); // String equality

		var vp4 = VerifiablePresentation.fromVouch(vouch2,
				Map.of("BosonProfile", List.of("https://example.com/credentials/profile/v1")));

		System.out.println(vp4);
		System.out.println(vp4.toPrettyString());
		System.out.println(Hex.encode(vp4.toBytes()));

		assertEquals(vp, vp4);
		assertEquals(vp.toString(), vp4.toString());

		assertEquals(vouch, vp4.toVouch());
		assertEquals(vouch.toString(), vp4.toVouch().toString());
	}

	@Test
	void complexVPTest() {
		var identity = new CryptoIdentity();

		var vpb = new VerifiablePresentationBuilder(identity)
				.id("testVP")
				.type("TestPresentation", "https://example.com/presentations/test/v1");
		
		vpb.addCredential().id("profile")
				.type("BosonProfile", "https://example.com/credentials/profile/v1")
				.type("Email", "https://example.com/credentials/email/v1")
				.name("John's Profile")
				.description("This is a test profile")
				.validFrom(new Date())
				.validUntil(new Date(System.currentTimeMillis() + DAY * 30))
				.claim("name", "John Doe")
				.claim("avatar", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==")
				.claim("email", "cV9dX@example.com")
				.claim("phone", "+1-123-456-7890")
				.claim("address", "123 Main St, Anytown, USA")
				.claim("city", "Anytown")
				.claim("state", "CA")
				.claim("zip", "12345")
				.claim("country", "USA")
				.build();

		vpb.addCredential("passport", "Passport", List.of("https://example.com/credentials/passport/v1"), "name", "John Doe", "number", "123456789");
		vpb.addCredential("driverLicense", "DriverLicense", List.of("https://example.com/credentials/driverLicense/v1"), "name", "John Doe", "number", "123456789", "expiration", System.currentTimeMillis() + DAY * 30);

		var vp = vpb.build();

		System.out.println(vp);
		System.out.println(vp.toPrettyString());
		System.out.println(Hex.encode(vp.toBytes()));

		assertEquals(4, vp.getContexts().size());
		assertEquals(DIDConstants.W3C_VC_CONTEXT, vp.getContexts().get(0));
		assertEquals(DIDConstants.BOSON_VC_CONTEXT, vp.getContexts().get(1));
		assertEquals(DIDConstants.W3C_ED25519_CONTEXT, vp.getContexts().get(2));
		assertEquals("https://example.com/presentations/test/v1", vp.getContexts().get(3));

		assertEquals(new DIDURL(identity.getId(), null, null, "testVP").toString(), vp.getId());
		assertEquals(2, vp.getTypes().size());
		assertEquals(DIDConstants.DEFAULT_VP_TYPE, vp.getTypes().get(0));
		assertEquals("TestPresentation", vp.getTypes().get(1));
		assertEquals(identity.getId(), vp.getHolder());

		assertEquals(3, vp.getCredentials().size());
		assertNotNull(vp.getCredential("profile"));
		assertNotNull(vp.getCredential(vp.getHolder().toDIDString() + "#profile"));
		assertEquals(1, vp.getCredentials("BosonProfile").size());

		for (var i = 0; i < 3; i++) {
			var cred = vp.getCredentials().get(i);
			assertTrue(cred.isGenuine());
			assertTrue(cred.isValid());
			assertTrue(cred.selfIssued());
		}

		assertTrue(vp.isGenuine());
		assertDoesNotThrow(vp::validate);

		var json = vp.toString();
		var vp2 = VerifiablePresentation.parse(json);
		assertEquals(vp, vp2);
		assertEquals(vp.toString(), vp2.toString());

		var bytes = vp.toBytes();
		var vp3 = VerifiablePresentation.parse(bytes);
		assertEquals(vp, vp3);
		assertEquals(vp.toString(), vp3.toString());

		var vouch = vp.toVouch();

		System.out.println(vouch);
		System.out.println(vouch.toPrettyString());
		System.out.println(Hex.encode(vouch.toBytes()));

		assertEquals(3, vouch.getCredentials().size());

		assertInstanceOf(VerifiablePresentation.BosonVouch.class, vouch);
		assertSame(vp, ((VerifiablePresentation.BosonVouch) vouch).getVerifiablePresentation());
		assertSame(vp, VerifiablePresentation.fromVouch(vouch,
				Map.of("TestPresentation", List.of("https://example.com/presentations/test/v1"),
						"BosonProfile", List.of("https://example.com/credentials/profile/v1"),
						"Passport", List.of("https://example.com/credentials/passport/v1"),
						"Email", List.of("https://example.com/credentials/email/v1"),
						"DriverLicense", List.of("https://example.com/credentials/driverLicense/v1"))));

		assertTrue(vouch.isGenuine());
		assertDoesNotThrow(vouch::validate);

		var vouch2 = Vouch.parse(vouch.toBytes());
		assertEquals(vouch, vouch2); // Object equality
		assertEquals(vouch.toString(), vouch2.toString()); // String equality

		var vp4 = VerifiablePresentation.fromVouch(vouch2,
				Map.of("TestPresentation", List.of("https://example.com/presentations/test/v1"),
						"BosonProfile", List.of("https://example.com/credentials/profile/v1"),
						"Passport", List.of("https://example.com/credentials/passport/v1"),
						"Email", List.of("https://example.com/credentials/email/v1"),
						"DriverLicense", List.of("https://example.com/credentials/driverLicense/v1")));

		System.out.println(vp4);
		System.out.println(vp4.toPrettyString());
		System.out.println(Hex.encode(vp4.toBytes()));

		assertEquals(vp, vp4);
		assertEquals(vp.toPrettyString(), vp4.toPrettyString());

		assertEquals(vouch, vp4.toVouch());
		assertEquals(vouch.toString(), vp4.toVouch().toString());
	}

	@Test
	void emptyVPTest() {
		var identity = new CryptoIdentity();

		var ex = assertThrows(IllegalStateException.class, () -> new VerifiablePresentationBuilder(identity).build());
		assertEquals("Credentials cannot be empty", ex.getMessage());
	}

	@Test
	void modifiedVPTest() {
		var identity = new CryptoIdentity();
		var avatar = new byte[128];
		for (int i = 0; i < avatar.length; i++)
			avatar[i] = (byte) i;

		var vp = new VerifiablePresentationBuilder(identity)
				.addCredential("profile", "BosonProfile", List.of("https://example.com/credentials/profile/v1"),
						"name", "Bob", "avatar", avatar)
				.build();

		assertTrue(vp.isGenuine());
		assertDoesNotThrow(vp::validate);

		System.out.println(vp);
		System.out.println(vp.toPrettyString());
		System.out.println(Hex.encode(vp.toBytes()));

		for (int i = 0; i < avatar.length; i++)
			avatar[i] = (byte) (i + 1);

		assertFalse(vp.isGenuine());
		assertThrows(InvalidSignatureException.class, vp::validate);
	}

	@Test
	void invalidSignatureTest() {
		var identity = new CryptoIdentity();

		var vp = new VerifiablePresentationBuilder(identity)
				.addCredential("profile", "BosonProfile", List.of("https://example.com/credentials/profile/v1"),
						"name", "Bob", "avatar", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==")
				.build();

		assertEquals(identity.getId(), vp.getHolder());
		assertTrue(vp.isGenuine());

		vp.getProof().getProofValue()[0] = (byte) (vp.getProof().getProofValue()[0] + 1);
		assertFalse(vp.isGenuine());
		assertThrows(InvalidSignatureException.class, vp::validate);
	}
}