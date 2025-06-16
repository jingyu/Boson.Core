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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.BeforeValidPeriodException;
import io.bosonnetwork.ExpiredException;
import io.bosonnetwork.Id;
import io.bosonnetwork.InvalidSignatureException;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.utils.Hex;

public class CredentialTests {
	private static final long DAY = 24 * 60 * 60 * 1000;

	@Test
	void simpleTest() {
		var issuer = new CryptoIdentity();

		var cred = new CredentialBuilder(issuer)
				.id("profile")
				.claim("name", "John Doe")
				.claim("email", "cV9dX@example.com")
				.build();

		System.out.println(cred);
		System.out.println(cred.toPrettyString());
		System.out.println(Hex.encode(cred.toBytes()));

		assertEquals("profile", cred.getId());
		assertEquals(0, cred.getTypes().size());
		assertEquals(issuer.getId(), cred.getIssuer());
		assertEquals(issuer.getId(), cred.getSubject().getId());
		assertEquals(2, cred.getSubject().getClaims().size());

		assertTrue(cred.selfIssued());
		assertTrue(cred.isValid());
		assertTrue(cred.isGenuine());
		assertDoesNotThrow(cred::validate);

		var json = cred.toString();
		var cred2 = Credential.parse(json);
		assertEquals(cred, cred2);
		assertEquals(cred.toString(), cred2.toString());

		var bytes = cred.toBytes();
		var cred3 = Credential.parse(bytes);
		assertEquals(cred, cred3);
		assertEquals(cred.toString(), cred3.toString());
	}

	@Test
	void complexTest() {
		var issuer = new CryptoIdentity();
		var subject = Id.random();
		var now = System.currentTimeMillis();

		var cred = new CredentialBuilder(issuer)
				.id("profile")
				.type("Profile", "Test")
				.name("John's Profile")
				.description("This is a test profile")
				.validFrom(new Date(now - 15 * DAY))
				.validUntil(new Date(now + 15 * DAY))
				.subject(subject)
				.claim("name", "John Doe")
				.claim("email", "cV9dX@example.com")
				.claim("phone", "+1-123-456-7890")
				.claim("address", "123 Main St, Anytown, USA")
				.claim("city", "Anytown")
				.claim("state", "CA")
				.claim("zip", "12345")
				.claim("country", "USA")
				.build();

		System.out.println(cred);
		System.out.println(cred.toPrettyString());
		System.out.println(Hex.encode(cred.toBytes()));

		assertEquals("profile", cred.getId());
		assertEquals(2, cred.getTypes().size());
		assertEquals("Profile", cred.getTypes().get(0));
		assertEquals("Test", cred.getTypes().get(1));
		assertEquals("John's Profile", cred.getName());
		assertEquals("This is a test profile", cred.getDescription());
		assertEquals(issuer.getId(), cred.getIssuer());
		assertEquals(subject, cred.getSubject().getId());
		assertEquals(8, cred.getSubject().getClaims().size());

		assertFalse(cred.selfIssued());
		assertTrue(cred.isValid());
		assertTrue(cred.isGenuine());
		assertDoesNotThrow(cred::validate);

		var json = cred.toString();
		var cred2 = Credential.parse(json);
		assertEquals(cred, cred2);
		assertEquals(cred.toString(), cred2.toString());

		var bytes = cred.toBytes();
		var cred3 = Credential.parse(bytes);
		assertEquals(cred, cred3);
		assertEquals(cred.toString(), cred3.toString());
	}

	@Test
	void beforeValidPeriodTest() {
		var issuer = new CryptoIdentity();
		var subject = Id.random();
		var now = System.currentTimeMillis();

		var cred = new CredentialBuilder(issuer)
				.id("profile")
				.type("Profile")
				.validFrom(new Date(now + 15 * DAY))
				.subject(subject)
				.claim("name", "John Doe")
				.claim("passport", "123456789")
				.claim("credit", 9600)
				.claim("avatar", "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAUAAAAFCAYAAACNbyblAAAAHElEQVQI12P4//8/w38GIAXDIBKE0DHxgljNBAAO9TXL0Y4OHwAAAABJRU5ErkJggg==")
				.build();

		System.out.println(cred);
		System.out.println(cred.toPrettyString());
		System.out.println(Hex.encode(cred.toBytes()));

		assertEquals(issuer.getId(), cred.getIssuer());
		assertEquals(subject, cred.getSubject().getId());

		assertFalse(cred.selfIssued());
		assertFalse(cred.isValid());
		assertTrue(cred.isGenuine());
		assertThrows(BeforeValidPeriodException.class, cred::validate);

		var json = cred.toString();
		var cred2 = Credential.parse(json);
		assertEquals(cred, cred2);
		assertEquals(cred.toString(), cred2.toString());

		var bytes = cred.toBytes();
		var cred3 = Credential.parse(bytes);
		assertEquals(cred, cred3);
		assertEquals(cred.toString(), cred3.toString());
	}

	@Test
	void expiredTest() {
		var issuer = new CryptoIdentity();
		var now = System.currentTimeMillis();

		var cred = new CredentialBuilder(issuer)
				.id("emailCredential")
				.type("Email")
				.validFrom(new Date(now - 15 * DAY))
				.validUntil(new Date(now - DAY))
				.claim("name", "John Doe")
				.claim("email", "cV9dX@example.com")
				.build();

		System.out.println(cred);
		System.out.println(cred.toPrettyString());
		System.out.println(Hex.encode(cred.toBytes()));

		assertEquals(issuer.getId(), cred.getIssuer());
		assertEquals(issuer.getId(), cred.getSubject().getId());

		assertTrue(cred.selfIssued());
		assertFalse(cred.isValid());
		assertTrue(cred.isGenuine());
		assertThrows(ExpiredException.class, cred::validate);

		var json = cred.toString();
		var cred2 = Credential.parse(json);
		assertEquals(cred, cred2);
		assertEquals(cred.toString(), cred2.toString());

		var bytes = cred.toBytes();
		var cred3 = Credential.parse(bytes);
		assertEquals(cred, cred3);
		assertEquals(cred.toString(), cred3.toString());
	}

	@Test
	void invalidSignatureTest() {
		var issuer = new CryptoIdentity();
		var now = System.currentTimeMillis();
		var data = new byte[128];
		for (int i = 0; i < data.length; i++)
			data[i] = (byte) i;

		var cred = new CredentialBuilder(issuer)
				.id("testCredential")
				.type("Binary")
				.claim("name", "John Doe")
				.claim("data", data)
				.build();

		System.out.println(cred);
		System.out.println(cred.toPrettyString());
		System.out.println(Hex.encode(cred.toBytes()));

		assertEquals(issuer.getId(), cred.getIssuer());
		assertEquals(issuer.getId(), cred.getSubject().getId());

		assertTrue(cred.selfIssued());
		assertTrue(cred.isValid());
		assertTrue(cred.isGenuine());
		assertDoesNotThrow(cred::validate);

		for (int i = 0; i < data.length; i++)
			data[i] = (byte) (i + 1);

		assertTrue(cred.selfIssued());
		assertTrue(cred.isValid());
		assertFalse(cred.isGenuine());
		assertThrows(InvalidSignatureException.class, cred::validate);
	}
}