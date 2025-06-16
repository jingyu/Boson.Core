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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.BeforeValidPeriodException;
import io.bosonnetwork.ExpiredException;
import io.bosonnetwork.Id;
import io.bosonnetwork.InvalidSignatureException;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.utils.Hex;

public class VerifiableCredentialTests {
	private static final long DAY = 24 * 60 * 60 * 1000;

	@Test
	void simpleVCTest() {
		var identity = new CryptoIdentity();

		var vc = new VerifiableCredentialBuilder(identity)
				.id("test")
				.type("Passport", "https://example.com/credentials/passport/v1")
				.claim("name", "John Doe")
				.claim("passport", "123456789")
				.build();

		System.out.println(vc);
		System.out.println(vc.toPrettyString());
		System.out.println(Hex.encode(vc.toBytes()));

		var canonicalId = new DIDURL(identity.getId(), null, null, "test");
		assertEquals(canonicalId.toString(), vc.getId());
		assertEquals(2, vc.getTypes().size());
		assertEquals(DIDConstants.DEFAULT_VC_TYPE, vc.getTypes().get(0));
		assertEquals("Passport", vc.getTypes().get(1));
		assertEquals(4, vc.getContexts().size());
		assertEquals(DIDConstants.W3C_VC_CONTEXT, vc.getContexts().get(0));
		assertEquals(DIDConstants.BOSON_VC_CONTEXT, vc.getContexts().get(1));
		assertEquals(DIDConstants.W3C_ED25519_CONTEXT, vc.getContexts().get(2));
		assertEquals("https://example.com/credentials/passport/v1", vc.getContexts().get(3));
		assertEquals(identity.getId(), vc.getIssuer());
		assertEquals(identity.getId(), vc.getSubject().getId());
		assertEquals("John Doe", vc.getSubject().getClaims().get("name"));
		assertEquals("123456789", vc.getSubject().getClaims().get("passport"));

		assertTrue(vc.selfIssued());
		assertTrue(vc.isValid());
		assertTrue(vc.isGenuine());
		assertDoesNotThrow(vc::validate);

		var json = vc.toString();
		var vc2 = VerifiableCredential.parse(json);
		assertEquals(vc, vc2);
		assertEquals(vc.toString(), vc2.toString());

		var bytes = vc.toBytes();
		var vc3 = VerifiableCredential.parse(bytes);
		assertEquals(vc, vc3);
		assertEquals(vc.toString(), vc3.toString());

		var bc = vc.toCredential();

		System.out.println(bc);
		System.out.println(bc.toPrettyString());
		System.out.println(Hex.encode(bc.toBytes()));

		assertInstanceOf(VerifiableCredential.BosonCredential.class, bc);
		assertSame(vc, ((VerifiableCredential.BosonCredential) bc).getVerifiableCredential());
		assertSame(vc, VerifiableCredential.fromCredential(bc, Map.of()));

		assertEquals(canonicalId.getFragment(), bc.getId());
		assertEquals(1, bc.getTypes().size());
		assertEquals("Passport", bc.getTypes().get(0));

		assertEquals(identity.getId(), bc.getIssuer());
		assertEquals(identity.getId(), bc.getSubject().getId());
		assertEquals("John Doe", bc.getSubject().getClaims().get("name"));
		assertEquals("123456789", bc.getSubject().getClaims().get("passport"));

		assertTrue(bc.selfIssued());
		assertTrue(bc.isValid());
		assertTrue(bc.isGenuine());
		assertDoesNotThrow(bc::validate);

		var bc2 = Credential.parse(bc.toBytes());
		assertEquals(bc, bc2); // Object equality
		assertEquals(bc.toString(), bc2.toString()); // String equality

		var vc4 = VerifiableCredential.fromCredential(bc2, Map.of("Passport", List.of("https://example.com/credentials/passport/v1")));

		System.out.println(vc4);
		System.out.println(vc4.toPrettyString());
		System.out.println(Hex.encode(vc4.toBytes()));

		assertEquals(vc, vc4);
		assertEquals(vc.toString(), vc4.toString());

		assertEquals(bc, vc4.toCredential());
		assertEquals(bc.toString(), vc4.toCredential().toString());
	}

	@Test
	void complexVCTest() {
		var identity = new CryptoIdentity();
		var subject = Id.random();

		var vc = new VerifiableCredentialBuilder(identity)
				.id("fullProfile")
				.type("Profile", "https://example.com/credentials/profile/v1")
				.type("Passport", "https://example.com/credentials/passport/v1")
				.type("Email", "https://example.com/credentials/email/v1")
				.name("John Doe's Profile")
				.description("This is a test profile")
				.subject(subject)
				.validFrom(new Date(System.currentTimeMillis() - DAY))
				.validUntil(new Date(System.currentTimeMillis() + DAY * 30))
				.claim("name", "John Doe")
				.claim("avatar", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==")
				.claim("email", "cV9dX@example.com")
				.claim("passport", "123456789")
				.claim("phone", "+1-123-456-7890")
				.claim("address", "123 Main St, Anytown, USA")
				.claim("city", "Anytown")
				.claim("state", "CA")
				.claim("zip", "12345")
				.claim("country", "USA")
				.claim("credit", 9600)
				.build();

		System.out.println(vc);
		System.out.println(vc.toPrettyString());
		System.out.println(Hex.encode(vc.toBytes()));

		var canonicalId = new DIDURL(subject, null, null, "fullProfile");
		assertEquals(canonicalId.toString(), vc.getId());
		assertEquals(4, vc.getTypes().size());
		assertEquals(DIDConstants.DEFAULT_VC_TYPE, vc.getTypes().get(0));
		assertEquals("Profile", vc.getTypes().get(1));
		assertEquals("Passport", vc.getTypes().get(2));
		assertEquals("Email", vc.getTypes().get(3));
		assertEquals(6, vc.getContexts().size());
		assertEquals(DIDConstants.W3C_VC_CONTEXT, vc.getContexts().get(0));
		assertEquals(DIDConstants.BOSON_VC_CONTEXT, vc.getContexts().get(1));
		assertEquals(DIDConstants.W3C_ED25519_CONTEXT, vc.getContexts().get(2));
		assertEquals("https://example.com/credentials/profile/v1", vc.getContexts().get(3));
		assertEquals("https://example.com/credentials/passport/v1", vc.getContexts().get(4));
		assertEquals("https://example.com/credentials/email/v1", vc.getContexts().get(5));

		assertEquals("John Doe's Profile", vc.getName());
		assertEquals("This is a test profile", vc.getDescription());

		assertEquals(identity.getId(), vc.getIssuer());
		assertEquals(subject, vc.getSubject().getId());

		assertEquals(11, vc.getSubject().getClaims().size());
		assertEquals("John Doe", vc.getSubject().getClaims().get("name"));
		assertEquals("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==", vc.getSubject().getClaims().get("avatar"));
		assertEquals("cV9dX@example.com", vc.getSubject().getClaims().get("email"));
		assertEquals("123456789", vc.getSubject().getClaims().get("passport"));
		assertEquals("+1-123-456-7890", vc.getSubject().getClaims().get("phone"));
		assertEquals("123 Main St, Anytown, USA", vc.getSubject().getClaims().get("address"));
		assertEquals("Anytown", vc.getSubject().getClaims().get("city"));
		assertEquals("CA", vc.getSubject().getClaims().get("state"));
		assertEquals("12345", vc.getSubject().getClaims().get("zip"));
		assertEquals("USA", vc.getSubject().getClaims().get("country"));
		assertEquals(9600, vc.getSubject().getClaims().get("credit"));

		assertEquals("John Doe", vc.getSubject().getClaims().get("name"));
		assertEquals("123456789", vc.getSubject().getClaims().get("passport"));

		assertFalse(vc.selfIssued());
		assertTrue(vc.isValid());
		assertTrue(vc.isGenuine());
		assertDoesNotThrow(vc::validate);

		var json = vc.toString();
		var vc2 = VerifiableCredential.parse(json);
		assertEquals(vc, vc2);
		assertEquals(vc.toString(), vc2.toString());

		var bytes = vc.toBytes();
		var vc3 = VerifiableCredential.parse(bytes);
		assertEquals(vc, vc3);
		assertEquals(vc.toString(), vc3.toString());

		var bc = vc.toCredential();

		System.out.println(bc);
		System.out.println(bc.toPrettyString());
		System.out.println(Hex.encode(bc.toBytes()));

		assertInstanceOf(VerifiableCredential.BosonCredential.class, bc);
		assertSame(vc, ((VerifiableCredential.BosonCredential) bc).getVerifiableCredential());
		assertSame(vc, VerifiableCredential.fromCredential(bc, Map.of()));

		assertEquals(canonicalId.getFragment(), bc.getId());
		assertEquals(3, bc.getTypes().size());
		assertEquals("Profile", bc.getTypes().get(0));
		assertEquals("Passport", bc.getTypes().get(1));
		assertEquals("Email", bc.getTypes().get(2));
		assertEquals(identity.getId(), bc.getIssuer());
		assertEquals(subject, bc.getSubject().getId());
		assertEquals(11, bc.getSubject().getClaims().size());

		assertFalse(bc.selfIssued());
		assertTrue(bc.isValid());
		assertTrue(bc.isGenuine());
		assertDoesNotThrow(bc::validate);

		var bc2 = Credential.parse(bc.toBytes());
		assertEquals(bc, bc2); // Object equality
		assertEquals(bc.toString(), bc2.toString()); // String equality

		var vc4 = VerifiableCredential.fromCredential(bc2,
				Map.of("Passport", List.of("https://example.com/credentials/passport/v1"),
						"Email", List.of("https://example.com/credentials/email/v1"),
						"Profile", List.of("https://example.com/credentials/profile/v1")));

		System.out.println(vc4);
		System.out.println(vc4.toPrettyString());
		System.out.println(Hex.encode(vc4.toBytes()));

		assertEquals(vc, vc4);
		assertEquals(vc.toString(), vc4.toString());

		assertEquals(bc, vc4.toCredential());
		assertEquals(bc.toString(), vc4.toCredential().toString());
	}

	@Test
	void beforeValidPeriodTest() {
		var issuer = new CryptoIdentity();
		var subject = Id.random();
		var now = System.currentTimeMillis();

		var vc = new VerifiableCredentialBuilder(issuer)
				.id("profile")
				.type("Profile", "https://example.com/credentials/profile/v1")
				.validFrom(new Date(now + 15 * DAY))
				.subject(subject)
				.claim("name", "John Doe")
				.claim("passport", "123456789")
				.claim("credit", 9600)
				.claim("avatar", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==")
				.build();

		System.out.println(vc);
		System.out.println(vc.toPrettyString());
		System.out.println(Hex.encode(vc.toBytes()));

		assertEquals(issuer.getId(), vc.getIssuer());
		assertEquals(subject, vc.getSubject().getId());

		assertFalse(vc.selfIssued());
		assertFalse(vc.isValid());
		assertTrue(vc.isGenuine());
		assertThrows(BeforeValidPeriodException.class, vc::validate);

		var json = vc.toString();
		var vc2 = VerifiableCredential.parse(json);
		assertEquals(vc, vc2);
		assertEquals(vc.toString(), vc2.toString());

		var bytes = vc.toBytes();
		var vc3 = VerifiableCredential.parse(bytes);
		assertEquals(vc, vc3);
		assertEquals(vc.toString(), vc3.toString());
	}

	@Test
	void expiredTest() {
		var issuer = new CryptoIdentity();
		var now = System.currentTimeMillis();

		var vc = new VerifiableCredentialBuilder(issuer)
				.id("emailCredential")
				.type("Email", "https://example.com/credentials/email/v1")
				.validFrom(new Date(now - 15 * DAY))
				.validUntil(new Date(now - DAY))
				.claim("name", "John Doe")
				.claim("email", "cV9dX@example.com")
				.build();

		System.out.println(vc);
		System.out.println(vc.toPrettyString());
		System.out.println(Hex.encode(vc.toBytes()));

		assertEquals(issuer.getId(), vc.getIssuer());
		assertEquals(issuer.getId(), vc.getSubject().getId());

		assertTrue(vc.selfIssued());
		assertFalse(vc.isValid());
		assertTrue(vc.isGenuine());
		assertThrows(ExpiredException.class, vc::validate);

		var json = vc.toString();
		var vc2 = VerifiableCredential.parse(json);
		assertEquals(vc, vc2);
		assertEquals(vc.toString(), vc2.toString());

		var bytes = vc.toBytes();
		var vc3 = VerifiableCredential.parse(bytes);
		assertEquals(vc, vc3);
		assertEquals(vc.toString(), vc3.toString());
	}

	@Test
	void invalidSignatureTest() {
		var issuer = new CryptoIdentity();
		var now = System.currentTimeMillis();
		var data = new byte[128];
		for (int i = 0; i < data.length; i++)
			data[i] = (byte) i;

		var vc = new VerifiableCredentialBuilder(issuer)
				.id("testCredential")
				.type("Binary")
				.claim("name", "John Doe")
				.claim("data", data)
				.build();

		System.out.println(vc);
		System.out.println(vc.toPrettyString());
		System.out.println(Hex.encode(vc.toBytes()));

		assertEquals(issuer.getId(), vc.getIssuer());
		assertEquals(issuer.getId(), vc.getSubject().getId());

		assertTrue(vc.selfIssued());
		assertTrue(vc.isValid());
		assertTrue(vc.isGenuine());
		assertDoesNotThrow(vc::validate);

		for (int i = 0; i < data.length; i++)
			data[i] = (byte) (i + 1);

		assertTrue(vc.selfIssued());
		assertTrue(vc.isValid());
		assertFalse(vc.isGenuine());
		assertThrows(InvalidSignatureException.class, vc::validate);
	}
}