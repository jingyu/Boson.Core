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

package io.bosonnetwork.identifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.InvalidSignatureException;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.utils.Hex;

public class CardTests {
	@Test
	void simpleCardTest() {
		var identity = new CryptoIdentity();

		var card = new CardBuilder(identity)
				.addCredential("profile", "BosonProfile", "name", "John Doe",
						"avatar", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==")
				.addService("homeNode", "BosonHomeNode", Id.random().toString())
				.build();

		assertEquals(identity.getId(), card.getId());
		assertEquals(1, card.getCredentials().size());
		assertEquals(1, card.getServices().size());

		assertNotNull(card.getCredential("profile"));
		assertNotNull(card.getService("homeNode"));

		assertNotNull(card.getProfileCredential());
		assertNotNull(card.getHomeNodeService());

		assertTrue(card.getProfileCredential().isGenuine());
		assertTrue(card.getProfileCredential().isValid());
		assertTrue(card.getProfileCredential().selfIssued());

		assertTrue(card.isGenuine());

		System.out.println(card);
		System.out.println(card.toPrettyString());
		System.out.println(Hex.encode(card.toBytes()));

		var json = card.toString();
		var card2 = Card.parse(json);
		assertEquals(card, card2);
		assertEquals(card.toString(), card2.toString());

		var bytes = card.toBytes();
		var card3 = Card.parse(bytes);
		assertEquals(card, card3);
		assertEquals(card.toString(), card3.toString());
	}

	@Test
	void complexCardTest() {
		var identity = new CryptoIdentity();
		long DAY = 24 * 60 * 60 * 1000;

		var cb = new CardBuilder(identity);
		cb.addCredential().id("profile")
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

		cb.addCredential("passport", "Passport", "name", "John Doe", "number", "123456789");
		cb.addCredential("driverLicense", "DriverLicense", "name", "John Doe", "number", "123456789", "expiration", System.currentTimeMillis() + DAY * 30);

		cb.addService("homeNode", "BosonHomeNode", Id.random().toString(), "sig", "F5r4bSbLamnvpDEiFgfuspszfMMMmQAhBdlhS1ZiliRdc4i-3aXZZ7mzYdkkpffpm3EsfwyDAcV_mwPiKf8cDA");
		cb.addService("messaging", "BMS", Id.random().toString());
		cb.addService("bcr", "CredentialRepo", "https://example.com/bcr", "src", "BosonCards", "token", "123456789");

		var card = cb.build();

		assertEquals(identity.getId(), card.getId());

		assertNotNull(card.getCredential("profile"));
		assertNotNull(card.getCredential("passport"));
		assertNotNull(card.getCredential("driverLicense"));
		assertNotNull(card.getService("homeNode"));
		assertNotNull(card.getService("messaging"));
		assertNotNull(card.getService("bcr"));

		var creds = card.getCredentials();
		assertEquals(3, creds.size());
		assertEquals("profile", creds.get(0).getId());
		assertEquals("passport", creds.get(1).getId());
		assertEquals("driverLicense", creds.get(2).getId());

		var services = card.getServices();
		assertEquals(3, services.size());
		assertEquals("homeNode", services.get(0).getId());
		assertEquals("messaging", services.get(1).getId());
		assertEquals("bcr", services.get(2).getId());

		assertNotNull(card.getProfileCredential());
		assertTrue(card.getProfileCredential().isGenuine());
		assertTrue(card.getProfileCredential().isValid());
		assertTrue(card.getProfileCredential().selfIssued());

		assertNotNull(card.getHomeNodeService());

		assertTrue(card.isGenuine());

		System.out.println(card);
		System.out.println(card.toPrettyString());
		System.out.println(Hex.encode(card.toBytes()));

		var json = card.toString();
		var card2 = Card.parse(json);
		assertEquals(card, card2);
		assertEquals(card.toString(), card2.toString());

		var bytes = card.toBytes();
		var card3 = Card.parse(bytes);
		assertEquals(card, card3);
		assertEquals(card.toString(), card3.toString());
	}

	@Test
	void emptyCardTest() {
		var identity = new CryptoIdentity();
		var card = new CardBuilder(identity).build();

		assertEquals(0, card.getCredentials().size());
		assertEquals(0, card.getServices().size());
		assertTrue(card.isGenuine());

		System.out.println(card);
		System.out.println(card.toPrettyString());
		System.out.println(Hex.encode(card.toBytes()));

		var json = card.toString();
		var card2 = Card.parse(json);
		assertEquals(card, card2);
		assertEquals(card.toString(), card2.toString());

		var bytes = card.toBytes();
		var cards3 = Card.parse(bytes);
		assertEquals(card, cards3);
		assertEquals(card.toString(), cards3.toString());
	}

	@Test
	void modifiedCardTest() {
		var identity = new CryptoIdentity();
		var avatar = new byte[128];
		for (int i = 0; i < avatar.length; i++)
			avatar[i] = (byte) i;

		var card = new CardBuilder(identity)
				.addCredential("profile", "BosonProfile", "name", "John Doe", "avatar", avatar)
				.addService("test", "TestService", Id.random().toString())
				.build();

		assertTrue(card.isGenuine());
		assertDoesNotThrow(card::validate);

		System.out.println(card);
		System.out.println(card.toPrettyString());
		System.out.println(Hex.encode(card.toBytes()));

		for (int i = 0; i < avatar.length; i++)
			avatar[i] = (byte) (i + 1);

		assertFalse(card.isGenuine());
		assertThrows(InvalidSignatureException.class, card::validate);
	}

	@Test
	void invalidSignatureTest() {
		var identity = new CryptoIdentity();

		var card = new CardBuilder(identity)
				.addCredential("profile", "BosonProfile", "name", "John Doe",
						"avatar", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==")
				.addService("homeNode", "BosonHomeNode", Id.random().toString())
				.build();

		assertEquals(identity.getId(), card.getId());
		assertTrue(card.isGenuine());

		card.getSignature()[0] = (byte) (card.getSignature()[0] + 1);
		assertFalse(card.isGenuine());
		assertThrows(InvalidSignatureException.class, card::validate);
	}
}