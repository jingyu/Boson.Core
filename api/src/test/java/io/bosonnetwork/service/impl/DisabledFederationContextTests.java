package io.bosonnetwork.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;

public class DisabledFederationContextTests {
	@Test
	public void testNoOpBehavior() throws ExecutionException, InterruptedException {
		DisabledFederationContext context = new DisabledFederationContext();

		assertFalse(context.existsNode(Id.random()).get());
		assertNull(context.getNode(Id.random(), false).get());
		assertTrue(context.getServices(Id.random(), Id.random()).get().isEmpty());
		assertTrue(context.getServices(Id.random()).get().isEmpty());
		assertFalse(context.getAuthenticator().authenticateNode(Id.random()).get());
		assertFalse(context.getAuthenticator().authenticatePeer(Id.random(), Id.random()).get());
	}
}