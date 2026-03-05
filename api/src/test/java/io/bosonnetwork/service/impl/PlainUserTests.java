package io.bosonnetwork.service.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import io.bosonnetwork.Id;

public class PlainUserTests {
	@Test
	public void testBasicProperties() {
		Id id = Id.random();
		PlainUser user = new PlainUser(id, "Test User", "password123");

		assertEquals(id, user.getId());
		assertEquals("Test User", user.getName());
		assertNull(user.getAvatar());
		assertNull(user.getEmail());
		assertNull(user.getBio());
		assertTrue(user.getCreated() > 0);
		assertEquals(user.getCreated(), user.getUpdated());
		assertFalse(user.isAnnounce());
		assertEquals(0, user.getLastAnnounced());
		assertEquals("Free", user.getPlanName());
	}

	@Test
	public void testPassphraseVerification() {
		Id id = Id.random();
		PlainUser user = new PlainUser(id, "Test User", "password123");

		assertTrue(user.verifyPassphrase("password123"));
		assertFalse(user.verifyPassphrase("wrongpassword"));
	}

	@Test
	public void testEmptyPassphraseVerification() {
		Id id = Id.random();
		PlainUser user = new PlainUser(id); // no passphrase

		assertTrue(user.verifyPassphrase("anything"));
		assertTrue(user.verifyPassphrase(null));
	}

	@Test
	public void testDefaultName() {
		Id id = Id.random();
		PlainUser user = new PlainUser(id);
		assertEquals(id.toAbbrBase58String(), user.getName());
	}
}
