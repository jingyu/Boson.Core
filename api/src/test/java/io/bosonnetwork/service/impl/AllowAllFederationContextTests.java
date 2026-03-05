package io.bosonnetwork.service.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.ExecutionException;
import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.service.FederatedNode;
import io.bosonnetwork.service.ServiceInfo;

public class AllowAllFederationContextTests {
	@Test
	public void testPermissiveBehavior() throws ExecutionException, InterruptedException {
		Identity nodeIdentity = new CryptoIdentity();
		AllowAllFederationContext context = new AllowAllFederationContext(nodeIdentity);

		assertTrue(context.existsNode(Id.random()).get());
		
		Id nodeId = Id.random();
		FederatedNode node = context.getNode(nodeId, true).get();
		assertNotNull(node);
		assertEquals(nodeId, node.getId());

		Id peerId = Id.random();
		List<ServiceInfo> services = context.getServices(peerId, nodeId).get();
		assertFalse(services.isEmpty());
		assertEquals(1, services.size());
		ServiceInfo si = services.get(0);
		assertEquals(peerId, si.getPeerId());
		assertEquals(nodeId, si.getNodeId());
		assertEquals(0, si.getFingerprint());

		assertTrue(context.getAuthenticator().authenticateNode(Id.random()).get());
		assertTrue(context.getAuthenticator().authenticatePeer(Id.random(), Id.random()).get());
	}
}
