package io.bosonnetwork.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.crypto.PasswordHash.Algorithm;
import io.bosonnetwork.crypto.PasswordHash.Salt;

public class PasswordHashTests {
	@Test
	void testHashInteractive() {
		var password = "password";
		var hash = PasswordHash.hashInteractive(password);
		var result = PasswordHash.verify(hash, password);
		assertTrue(result);

		result = PasswordHash.verify(hash, "Password");
		assertFalse(result);

		result = PasswordHash.needsRehashForInteractive(hash);
		assertFalse(result);
		result = PasswordHash.needsRehashForModerate(hash);
		assertTrue(result);
		result = PasswordHash.needsRehashForSensitive(hash);
		assertTrue(result);

		System.out.println(hash);
	}

	@Test
	void testHashModerate() {
		var password = "password";
		var hash = PasswordHash.hashModerate(password);
		var result = PasswordHash.verify(hash, password);
		assertTrue(result);

		result = PasswordHash.verify(hash, "Password");
		assertFalse(result);

		result = PasswordHash.needsRehashForInteractive(hash);
		assertTrue(result);
		result = PasswordHash.needsRehashForModerate(hash);
		assertFalse(result);
		result = PasswordHash.needsRehashForSensitive(hash);
		assertTrue(result);

		System.out.println(hash);
	}

	@Test
	void testHashSensitive() {
		var password = "password";
		var hash = PasswordHash.hashSensitive(password);
		var result = PasswordHash.verify(hash, password);
		assertTrue(result);

		result = PasswordHash.verify(hash, "Password");
		assertFalse(result);

		result = PasswordHash.needsRehashForInteractive(hash);
		assertTrue(result);
		result = PasswordHash.needsRehashForModerate(hash);
		assertTrue(result);
		result = PasswordHash.needsRehashForSensitive(hash);
		assertFalse(result);

		System.out.println(hash);
	}

	@Test
	void testKeyDeriveInteractive() {
		var password = "secret";

		var salt = Salt.random();
		var key = PasswordHash.hashInteractive(password, 32, salt, Algorithm.ARGON2ID13);
		assertEquals(32, key.length);

		var salt2 = Salt.fromBytes(salt.bytes());
		assertEquals(salt, salt2);

		var key2 = PasswordHash.hashInteractive(password, 32, salt2, Algorithm.DEFAULT);
		assertEquals(32, key2.length);
		assertArrayEquals(key, key2);
	}

	@Test
	void testKeyDeriveModerate() {
		var password = "secret";

		var salt = Salt.random();
		var key = PasswordHash.hashModerate(password, 32, salt, Algorithm.ARGON2ID13);
		assertEquals(32, key.length);

		var salt2 = Salt.fromBytes(salt.bytes());
		assertEquals(salt, salt2);

		var key2 = PasswordHash.hashModerate(password, 32, salt2, Algorithm.DEFAULT);
		assertEquals(32, key2.length);
		assertArrayEquals(key, key2);
	}

	@Test
	void testKeyDeriveSensitive() {
		var password = "secret";

		var salt = Salt.random();
		var key = PasswordHash.hashSensitive(password, 32, salt, Algorithm.ARGON2ID13);
		assertEquals(32, key.length);

		var salt2 = Salt.fromBytes(salt.bytes());
		assertEquals(salt, salt2);

		var key2 = PasswordHash.hashSensitive(password, 32, salt2, Algorithm.DEFAULT);
		assertEquals(32, key2.length);
		assertArrayEquals(key, key2);
	}
}
