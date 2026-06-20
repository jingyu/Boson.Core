package io.bosonnetwork.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;

public class PlainUserTests {
	@Test
	public void testBasicProperties() {
		Id id = Id.random();
		PlainUser user = new PlainUser(id, "Test User");

		assertEquals(id, user.getId());
		assertEquals("Test User", user.getName().orElseThrow());
		assertFalse(user.getAvatar().isPresent());
		assertFalse(user.getEmail().isPresent());
		assertTrue(user.getBio().isEmpty());
		assertTrue(user.getCreatedAt() > 0);
		assertEquals(user.getCreatedAt(), user.getUpdatedAt());
		assertEquals("Free", user.getPlanName());
	}

	@Test
	public void testPassphraseVerification() {
		Id id = Id.random();
		PlainUser user = new PlainUser(id, "Test User");

		assertTrue(user.verifyPassphrase("secret"));
		assertFalse(user.verifyPassphrase("wrongpassword"));
	}

	@Test
	public void testDefaultName() {
		Id id = Id.random();
		PlainUser user = new PlainUser(id);
		assertTrue(user.getName().isPresent());
		assertEquals(id.toAbbrBase58String(), user.getName().get());
	}
}