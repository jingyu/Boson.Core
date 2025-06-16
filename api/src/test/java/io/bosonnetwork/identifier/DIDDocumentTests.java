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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.InvalidSignatureException;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.utils.Hex;

public class DIDDocumentTests {
	private static final long DAY = 24 * 60 * 60 * 1000;

	@Test
	void simpleDocumentTest() {
		var identity = new CryptoIdentity();

		var doc = new DIDDocumentBuilder(identity)
				.addCredential("profile", "BosonProfile", List.of("https://example.com/credentials/profile/v1"),
						"name", "Bob", "avatar", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==")
				.addService("homeNode", "BosonHomeNode", Id.random().toString(), "sig", "F5r4bSbLamnvpDEiFgfuspszfMMMmQAhBdlhS1ZiliRdc4i-3aXZZ7mzYdkkpffpm3EsfwyDAcV_mwPiKf8cDA")
				.build();

		System.out.println(doc);
		System.out.println(doc.toPrettyString());
		System.out.println(Hex.encode(doc.toBytes()));

		assertEquals(3, doc.getContexts().size());
		assertEquals(DIDConstants.W3C_DID_CONTEXT, doc.getContexts().get(0));
		assertEquals(DIDConstants.BOSON_DID_CONTEXT, doc.getContexts().get(1));
		assertEquals(DIDConstants.W3C_ED25519_CONTEXT, doc.getContexts().get(2));

		assertEquals(identity.getId(), doc.getId());

		assertEquals(1, doc.getVerificationMethods().size());
		assertNotNull(doc.getVerificationMethod("#default"));
		assertNotNull(doc.getVerificationMethod(doc.getId().toDIDString() + "#default"));
		assertEquals(1, doc.getVerificationMethods(VerificationMethod.Type.Ed25519VerificationKey2020).size());

		assertEquals(1, doc.getAuthentications().size());
		assertNotNull(doc.getAuthentication("#default"));
		assertNotNull(doc.getAuthentication(doc.getId().toDIDString() + "#default"));

		assertEquals(1, doc.getAssertions().size());
		assertNotNull(doc.getAssertion("#default"));
		assertNotNull(doc.getAssertion(doc.getId().toDIDString() + "#default"));

		assertEquals(1, doc.getCredentials().size());
		assertNotNull(doc.getCredential("profile"));
		assertNotNull(doc.getCredential(doc.getId().toDIDString() + "#profile"));
		assertEquals(1, doc.getCredentials("BosonProfile").size());

		var credProfile = doc.getCredential("profile");
		assertTrue(credProfile.isGenuine());
		assertTrue(credProfile.isValid());
		assertTrue(credProfile.selfIssued());

		assertEquals(1, doc.getServices().size());
		assertNotNull(doc.getService("homeNode"));
		assertNotNull(doc.getService(doc.getId().toDIDString() + "#homeNode"));
		assertEquals(1, doc.getServices("BosonHomeNode").size());

		assertTrue(doc.isGenuine());
		assertDoesNotThrow(doc::validate);

		var json = doc.toString();
		var doc2 = DIDDocument.parse(json);
		assertEquals(doc, doc2);
		assertEquals(doc.toString(), doc2.toString());

		var bytes = doc.toBytes();
		var doc3 = DIDDocument.parse(bytes);
		assertEquals(doc, doc3);
		assertEquals(doc.toString(), doc3.toString());

		var card = doc.toCard();

		System.out.println(card);
		System.out.println(card.toPrettyString());
		System.out.println(Hex.encode(card.toBytes()));

		assertEquals(1, card.getCredentials().size());
		assertEquals(1, card.getServices().size());

		assertInstanceOf(DIDDocument.BosonCard.class, card);
		assertSame(doc, ((DIDDocument.BosonCard) card).getDocument());
		assertSame(doc, DIDDocument.fromCard(card, List.of(),
				Map.of("BosonProfile", List.of("https://example.com/credentials/profile/v1"))));

		assertTrue(card.isGenuine());
		assertDoesNotThrow(card::validate);

		var card2 = Card.parse(card.toBytes());
		assertEquals(card, card2); // Object equality
		assertEquals(card.toString(), card2.toString()); // String equality

		var doc4 = DIDDocument.fromCard(card2, List.of(),
				Map.of("BosonProfile", List.of("https://example.com/credentials/profile/v1")));

		System.out.println(doc4);
		System.out.println(doc4.toPrettyString());
		System.out.println(Hex.encode(doc4.toBytes()));

		assertEquals(doc, doc4);
		assertEquals(doc.toString(), doc4.toString());

		assertEquals(card, doc4.toCard());
		assertEquals(card.toString(), doc4.toCard().toString());
	}

	@Test
	void complexDocumentTest() {
		var identity = new CryptoIdentity();

		var db = new DIDDocumentBuilder(identity);
		db.addCredential().id("profile")
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

		db.addCredential("passport", "Passport", List.of("https://example.com/credentials/passport/v1"), "name", "John Doe", "number", "123456789");
		db.addCredential("driverLicense", "DriverLicense", List.of("https://example.com/credentials/driverLicense/v1"), "name", "John Doe", "number", "123456789", "expiration", System.currentTimeMillis() + DAY * 30);

		db.addService("homeNode", "BosonHomeNode", Id.random().toString(), "sig", "F5r4bSbLamnvpDEiFgfuspszfMMMmQAhBdlhS1ZiliRdc4i-3aXZZ7mzYdkkpffpm3EsfwyDAcV_mwPiKf8cDA");
		db.addService("messaging", "BMS", Id.random().toString());
		db.addService("bcr", "CredentialRepo", "https://example.com/bcr", "src", "BosonCards", "token", "123456789");

		var doc = db.build();

		System.out.println(doc);
		System.out.println(doc.toPrettyString());
		System.out.println(Hex.encode(doc.toBytes()));

		assertEquals(3, doc.getContexts().size());
		assertEquals(DIDConstants.W3C_DID_CONTEXT, doc.getContexts().get(0));
		assertEquals(DIDConstants.BOSON_DID_CONTEXT, doc.getContexts().get(1));
		assertEquals(DIDConstants.W3C_ED25519_CONTEXT, doc.getContexts().get(2));

		assertEquals(identity.getId(), doc.getId());

		assertEquals(1, doc.getVerificationMethods().size());
		assertNotNull(doc.getVerificationMethod("#default"));
		assertNotNull(doc.getVerificationMethod(doc.getId().toDIDString() + "#default"));
		assertEquals(1, doc.getVerificationMethods(VerificationMethod.Type.Ed25519VerificationKey2020).size());

		assertEquals(1, doc.getAuthentications().size());
		assertNotNull(doc.getAuthentication("#default"));
		assertNotNull(doc.getAuthentication(doc.getId().toDIDString() + "#default"));

		assertEquals(1, doc.getAssertions().size());
		assertNotNull(doc.getAssertion("#default"));
		assertNotNull(doc.getAssertion(doc.getId().toDIDString() + "#default"));

		assertEquals(3, doc.getCredentials().size());
		assertNotNull(doc.getCredential("profile"));
		assertNotNull(doc.getCredential(doc.getId().toDIDString() + "#profile"));
		assertEquals(1, doc.getCredentials("BosonProfile").size());

		for (var i = 0; i < 3; i++) {
			var cred = doc.getCredentials().get(i);
			assertTrue(cred.isGenuine());
			assertTrue(cred.isValid());
			assertTrue(cred.selfIssued());
		}

		assertEquals(3, doc.getServices().size());
		assertNotNull(doc.getService("homeNode"));
		assertNotNull(doc.getService(doc.getId().toDIDString() + "#homeNode"));
		assertEquals(1, doc.getServices("BosonHomeNode").size());

		assertNotNull(doc.getService("#messaging"));
		assertEquals(1, doc.getServices("CredentialRepo").size());

		assertTrue(doc.isGenuine());
		assertDoesNotThrow(doc::validate);

		var json = doc.toString();
		var doc2 = DIDDocument.parse(json);
		assertEquals(doc, doc2);
		assertEquals(doc.toString(), doc2.toString());

		var bytes = doc.toBytes();
		var doc3 = DIDDocument.parse(bytes);
		assertEquals(doc, doc3);
		assertEquals(doc.toString(), doc3.toString());

		var card = doc.toCard();

		System.out.println(card);
		System.out.println(card.toPrettyString());
		System.out.println(Hex.encode(card.toBytes()));

		assertEquals(3, card.getCredentials().size());
		assertEquals(3, card.getServices().size());

		assertInstanceOf(DIDDocument.BosonCard.class, card);
		assertSame(doc, ((DIDDocument.BosonCard) card).getDocument());
		assertSame(doc, DIDDocument.fromCard(card, List.of(),
				Map.of("BosonProfile", List.of("https://example.com/credentials/profile/v1"),
						"Passport", List.of("https://example.com/credentials/passport/v1"),
						"Email", List.of("https://example.com/credentials/email/v1"),
						"DriverLicense", List.of("https://example.com/credentials/driverLicense/v1"))));

		assertTrue(card.isGenuine());
		assertDoesNotThrow(card::validate);

		var card2 = Card.parse(card.toBytes());
		assertEquals(card, card2); // Object equality
		assertEquals(card.toString(), card2.toString()); // String equality

		var doc4 = DIDDocument.fromCard(card2, List.of(),
				Map.of("BosonProfile", List.of("https://example.com/credentials/profile/v1"),
						"Passport", List.of("https://example.com/credentials/passport/v1"),
						"Email", List.of("https://example.com/credentials/email/v1"),
						"DriverLicense", List.of("https://example.com/credentials/driverLicense/v1")));

		System.out.println(doc4);
		System.out.println(doc4.toPrettyString());
		System.out.println(Hex.encode(doc4.toBytes()));

		assertEquals(doc, doc4);
		assertEquals(doc.toPrettyString(), doc4.toPrettyString());

		assertEquals(card, doc4.toCard());
		assertEquals(card.toString(), doc4.toCard().toString());
	}

	@Test
	void emptyDocTest() {
		var identity = new CryptoIdentity();
		var doc = new DIDDocumentBuilder(identity).build();

		System.out.println(doc);
		System.out.println(doc.toPrettyString());
		System.out.println(Hex.encode(doc.toBytes()));

		assertEquals(1, doc.getVerificationMethods().size());
		assertEquals(1, doc.getAuthentications().size());
		assertEquals(1, doc.getAssertions().size());
		assertEquals(0, doc.getCredentials().size());
		assertEquals(0, doc.getServices().size());
		assertTrue(doc.isGenuine());

		var json = doc.toString();
		var doc2 = DIDDocument.parse(json);
		assertEquals(doc, doc2);
		assertEquals(doc.toString(), doc2.toString());

		var bytes = doc.toBytes();
		var doc3 = DIDDocument.parse(bytes);
		assertEquals(doc, doc3);
		assertEquals(doc.toString(), doc3.toString());

		var card = doc.toCard();

		System.out.println(card);
		System.out.println(card.toPrettyString());
		System.out.println(Hex.encode(card.toBytes()));

		assertEquals(0, card.getCredentials().size());
		assertEquals(0, card.getServices().size());
		assertTrue(card.isGenuine());
		assertDoesNotThrow(card::validate);

		assertInstanceOf(DIDDocument.BosonCard.class, card);
		assertSame(doc, ((DIDDocument.BosonCard) card).getDocument());
		assertSame(doc, DIDDocument.fromCard(card, null, null));

		var card2 = Card.parse(card.toBytes());
		assertEquals(card, card2); // Object equality
		assertEquals(card.toString(), card2.toString()); // String equality

		var doc4 = DIDDocument.fromCard(card2,null, null);

		System.out.println(doc4);
		System.out.println(doc4.toPrettyString());
		System.out.println(Hex.encode(doc4.toBytes()));

		assertEquals(doc, doc4);
		assertEquals(doc.toPrettyString(), doc4.toPrettyString());

		assertEquals(card, doc4.toCard());
		assertEquals(card.toString(), doc4.toCard().toString());
	}

	@Test
	void modifiedDocTest() {
		var identity = new CryptoIdentity();
		var avatar = new byte[128];
		for (int i = 0; i < avatar.length; i++)
			avatar[i] = (byte) i;

		var doc = new DIDDocumentBuilder(identity)
				.addCredential("profile", "BosonProfile", List.of("https://example.com/credentials/profile/v1"),
						"name", "Bob", "avatar", avatar)
				.addService("homeNode", "BosonHomeNode", Id.random().toString(), "sig", "F5r4bSbLamnvpDEiFgfuspszfMMMmQAhBdlhS1ZiliRdc4i-3aXZZ7mzYdkkpffpm3EsfwyDAcV_mwPiKf8cDA")
				.build();

		assertTrue(doc.isGenuine());
		assertDoesNotThrow(doc::validate);

		System.out.println(doc);
		System.out.println(doc.toPrettyString());
		System.out.println(Hex.encode(doc.toBytes()));

		for (int i = 0; i < avatar.length; i++)
			avatar[i] = (byte) (i + 1);

		assertFalse(doc.isGenuine());
		assertThrows(InvalidSignatureException.class, doc::validate);
	}

	@Test
	void invalidSignatureTest() {
		var identity = new CryptoIdentity();

		var doc = new DIDDocumentBuilder(identity)
				.addCredential("profile", "BosonProfile", List.of("https://example.com/credentials/profile/v1"),
						"name", "Bob", "avatar", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==")
				.addService("homeNode", "BosonHomeNode", Id.random().toString(), "sig", "F5r4bSbLamnvpDEiFgfuspszfMMMmQAhBdlhS1ZiliRdc4i-3aXZZ7mzYdkkpffpm3EsfwyDAcV_mwPiKf8cDA")
				.build();

		assertEquals(identity.getId(), doc.getId());
		assertTrue(doc.isGenuine());

		doc.getProof().getProofValue()[0] = (byte) (doc.getProof().getProofValue()[0] + 1);
		assertFalse(doc.isGenuine());
		assertThrows(InvalidSignatureException.class, doc::validate);
	}
}