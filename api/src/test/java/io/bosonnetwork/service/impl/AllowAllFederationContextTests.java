package io.bosonnetwork.service.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.ExecutionException;
import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.service.Admission;
import io.bosonnetwork.service.FederatedService;
import io.bosonnetwork.service.Resolution;

public class AllowAllFederationContextTests {
	@Test
	public void everyPeerResolvesAndEveryNodeIsOpen() throws ExecutionException, InterruptedException {
		AllowAllFederationContext context = new AllowAllFederationContext(new CryptoIdentity());

		Id peerId = Id.random();
		Resolution r = context.resolve(peerId).get();
		assertTrue(r.allowed());
		assertEquals(Admission.Reason.OPEN, r.admission().reason());
		FederatedService service = r.findService().orElseThrow();
		assertEquals(peerId, service.peerId());
		assertEquals(Id.zero(), service.nodeId());
		assertEquals(AllowAllFederationContext.PLACEHOLDER_ENDPOINT, service.endpoint());

		assertEquals(Admission.Reason.OPEN, context.admit(Id.random()).get().reason());
		assertTrue(context.describe(Id.random()).get().isEmpty());
	}
}
