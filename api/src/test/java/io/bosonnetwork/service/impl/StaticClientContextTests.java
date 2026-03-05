package io.bosonnetwork.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.ExecutionException;
import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.service.ClientUser;

public class StaticClientContextTests {
	private StaticClientContext context;
	private Identity nodeIdentity;

	@BeforeEach
	public void setUp() {
		nodeIdentity = new CryptoIdentity();
		context = new StaticClientContext(nodeIdentity);
	}

	@Test
	public void testAddAndGetUser() throws ExecutionException, InterruptedException {
		Id userId = Id.random();
		assertTrue(context.addUser(userId, "Alice", "pass"));
		assertFalse(context.addUser(userId, "Alice", "pass")); // repeat

		assertTrue(context.existsUser(userId).get());
		assertFalse(context.existsUser(Id.random()).get());

		ClientUser user = context.getUser(userId).get();
		assertNotNull(user);
		assertEquals("Alice", user.getName());
		assertTrue(user.verifyPassphrase("pass"));

		assertNull(context.getUser(Id.random()).get());
	}

	@Test
	public void testAddAndGetDevice() throws ExecutionException, InterruptedException {
		Id userId = Id.random();
		Id deviceId = Id.random();
		context.addUser(userId, "Bob", "pass");

		assertTrue(context.addDevice(userId, deviceId, "Phone", "App"));
		assertFalse(context.addDevice(userId, deviceId, "Phone", "App")); // repeat
				
		assertTrue(context.existsDevice(userId, deviceId).get());
		assertFalse(context.existsDevice(userId, Id.random()).get());
		assertFalse(context.existsDevice(Id.random(), deviceId).get());
	}

	@Test
	public void testRemoveUserAndDevice() throws ExecutionException, InterruptedException {
		Id userId = Id.random();
		Id deviceId = Id.random();
		context.addUser(userId, "Charlie", "pass");
		context.addDevice(userId, deviceId, "Phone", "App");

		assertTrue(context.removeDevice(userId, deviceId));
		assertFalse(context.removeDevice(userId, deviceId)); // repeat
		assertFalse(context.existsDevice(userId, deviceId).get());
		assertFalse(context.removeDevice(userId, Id.random()));

		assertTrue(context.removeUser(userId));
		assertFalse(context.removeUser(userId));  // repeat
		assertFalse(context.existsUser(userId).get());
		assertFalse(context.removeUser(Id.random()));
	}

	@Test
	public void testAuthentication() throws ExecutionException, InterruptedException {
		Id userId = Id.random();
		Id deviceId = Id.random();
		context.addUser(userId, "Dan", "pass");
		context.addDevice(userId, deviceId, "Phone", "App");

		assertTrue(context.getAuthenticator().authenticateUser(userId).get());
		assertFalse(context.getAuthenticator().authenticateUser(Id.random()).get());
		assertTrue(context.getAuthenticator().authenticateDevice(userId, deviceId, "localhost").get());
		assertFalse(context.getAuthenticator().authenticateDevice(userId, Id.random(), "localhost").get());
		assertFalse(context.getAuthenticator().authenticateDevice(Id.random(), deviceId, "localhost").get());
	}
}
