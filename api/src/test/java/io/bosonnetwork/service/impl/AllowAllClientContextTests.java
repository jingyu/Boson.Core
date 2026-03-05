package io.bosonnetwork.service.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.ExecutionException;
import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.service.ClientUser;

public class AllowAllClientContextTests {
	@Test
	public void testPermissiveBehavior() throws ExecutionException, InterruptedException {
		Identity nodeIdentity = new CryptoIdentity();
		AllowAllClientContext context = new AllowAllClientContext(nodeIdentity);

		assertTrue(context.existsUser(Id.random()).get());
		assertTrue(context.existsDevice(Id.random(), Id.random()).get());

        Id userId = Id.random();
		ClientUser user = context.getUser(userId).get();
		assertNotNull(user);
		assertEquals(userId, user.getId());
		assertTrue(user.verifyPassphrase("any"));

		assertTrue(context.getAuthenticator().authenticateUser(Id.random()).get());
        assertTrue(context.getAuthenticator().authenticateDevice(Id.random(), Id.random(), "127.0.0.1").get());
	}
}
