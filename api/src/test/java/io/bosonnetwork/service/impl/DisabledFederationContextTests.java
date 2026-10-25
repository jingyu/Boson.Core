package io.bosonnetwork.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.service.Admission;
import io.bosonnetwork.service.FederationContext;
import io.bosonnetwork.service.Resolution;

@SuppressWarnings("removal")
public class DisabledFederationContextTests {
	@Test
	public void testNoOpBehavior() throws ExecutionException, InterruptedException {
		DisabledFederationContext context = new DisabledFederationContext();

		assertFalse(context.existsNode(Id.random()).get());
		assertTrue(context.getNode(Id.random(), false).get().isEmpty());
		assertTrue(context.getServices(Id.random(), Id.random()).get().isEmpty());
		assertTrue(context.getServices(Id.random()).get().isEmpty());
		assertFalse(context.getAuthenticator().authenticateNode(Id.random()).get());
		assertFalse(context.getAuthenticator().authenticatePeer(Id.random(), Id.random()).get());
	}

	@Test
	public void everythingIsRefusedAsDisabled() throws ExecutionException, InterruptedException {
		DisabledFederationContext context = new DisabledFederationContext();

		Resolution r = context.resolve(Id.random()).get();
		assertNull(r.service());
		assertEquals(Admission.Reason.DISABLED, r.admission().reason());
		assertFalse(r.allowed());

		assertEquals(Admission.Reason.DISABLED, context.admit(Id.random()).get().reason());
		assertTrue(context.describe(Id.random()).get().isEmpty());
		context.report(Id.random(), Id.random(), FederationContext.Outcome.ERROR, null);
		context.onChange(e -> {}).close();
	}
}
