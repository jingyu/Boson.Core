package io.bosonnetwork.identifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.InvalidSignatureException;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.utils.Hex;

public class VouchTests {
	@Test
	void simpleVouchTest() {
		var identity = new CryptoIdentity();

		var vouch = new VouchBuilder(identity)
				.addCredential("profile", "BosonProfile", "name", "John Doe", "email", "cV9dX@example.com")
				.build();

		assertNull(vouch.getId());
		assertEquals(identity.getId(), vouch.getHolder());
		assertEquals(1, vouch.getCredentials().size());
		assertEquals(1, vouch.getCredentials("BosonProfile").size());
		assertNotNull(vouch.getCredential("profile"));

		for (var cred : vouch.getCredentials()) {
			assertTrue(cred.isGenuine());
			assertTrue(cred.isValid());
			assertTrue(cred.selfIssued());
		}

		assertTrue(vouch.isGenuine());

		System.out.println(vouch);
		System.out.println(vouch.toPrettyString());
		System.out.println(Hex.encode(vouch.toBytes()));

		var json = vouch.toString();
		var vouch2 = Vouch.parse(json);
		assertEquals(vouch, vouch2);
		assertEquals(vouch.toString(), vouch2.toString());

		var bytes = vouch.toBytes();
		var vouch3 = Vouch.parse(bytes);
		assertEquals(vouch, vouch3);
		assertEquals(vouch.toString(), vouch3.toString());
	}

	@Test
	void complexVouchTest() {
		var identity = new CryptoIdentity();
		long DAY = 24 * 60 * 60 * 1000;

		var vb = new VouchBuilder(identity)
				.id("testVouch")
				.type("BosonVouch", "TestVouch");

		vb.addCredential().id("profile")
				.type("BosonProfile", "TestProfile")
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

		vb.addCredential("passport", "Passport", "name", "John Doe", "number", "123456789");
		vb.addCredential("driverLicense", "DriverLicense", "name", "John Doe", "number", "123456789", "expiration", System.currentTimeMillis() + DAY * 30);
		var vouch = vb.build();

		assertEquals("testVouch", vouch.getId());
		assertEquals(2, vouch.getTypes().size());
		assertEquals("BosonVouch", vouch.getTypes().get(0));
		assertEquals("TestVouch", vouch.getTypes().get(1));
		assertEquals(identity.getId(), vouch.getHolder());

		assertNotNull(vouch.getCredential("profile"));
		assertNotNull(vouch.getCredential("passport"));
		assertNotNull(vouch.getCredential("driverLicense"));

		assertEquals(1, vouch.getCredentials("BosonProfile").size());

		var creds = vouch.getCredentials();
		assertEquals(3, creds.size());
		assertEquals("profile", creds.get(0).getId());
		assertEquals("passport", creds.get(1).getId());
		assertEquals("driverLicense", creds.get(2).getId());

		assertTrue(vouch.isGenuine());

		System.out.println(vouch);
		System.out.println(vouch.toPrettyString());
		System.out.println(Hex.encode(vouch.toBytes()));

		var json = vouch.toString();
		var vouch2 = Vouch.parse(json);
		assertEquals(vouch, vouch2);
		assertEquals(vouch.toString(), vouch2.toString());

		var bytes = vouch.toBytes();
		var vouch3 = Vouch.parse(bytes);
		assertEquals(vouch, vouch3);
		assertEquals(vouch.toString(), vouch3.toString());
	}

	@Test
	void emptyVouchTest() {
		var identity = new CryptoIdentity();

		var ex = assertThrows(IllegalStateException.class, () -> new VouchBuilder(identity).build());
		assertEquals("Credentials cannot be empty", ex.getMessage());
	}

	@Test
	void modifiedVouchTest() {
		var identity = new CryptoIdentity();
		var avatar = new byte[128];
		for (int i = 0; i < avatar.length; i++)
			avatar[i] = (byte) i;

		var vouch = new VouchBuilder(identity)
				.addCredential("profile", "BosonProfile", "name", "John Doe", "avatar", avatar)
				.build();

		assertTrue(vouch.isGenuine());
		assertDoesNotThrow(vouch::validate);

		System.out.println(vouch);
		System.out.println(vouch.toPrettyString());
		System.out.println(Hex.encode(vouch.toBytes()));

		for (int i = 0; i < avatar.length; i++)
			avatar[i] = (byte) (i + 1);

		assertFalse(vouch.isGenuine());
		assertThrows(InvalidSignatureException.class, vouch::validate);
	}

	@Test
	void invalidSignatureTest() {
		var identity = new CryptoIdentity();

		var vouch = new VouchBuilder(identity)
				.addCredential("profile", "BosonProfile", "name", "John Doe",
						"avatar", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==")
				.build();

		assertEquals(identity.getId(), vouch.getHolder());
		assertTrue(vouch.isGenuine());

		vouch.getSignature()[0] = (byte) (vouch.getSignature()[0] + 1);
		assertFalse(vouch.isGenuine());
		assertThrows(InvalidSignatureException.class, vouch::validate);
	}
}