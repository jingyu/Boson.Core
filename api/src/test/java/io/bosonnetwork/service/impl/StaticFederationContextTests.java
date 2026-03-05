package io.bosonnetwork.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.service.FederatedNode;
import io.bosonnetwork.service.ServiceInfo;

public class StaticFederationContextTests {
	private StaticFederationContext context;
	private Identity nodeIdentity;

	@BeforeEach
	public void setUp() {
		nodeIdentity = new CryptoIdentity();
		context = new StaticFederationContext(nodeIdentity);
	}

	@Test
	public void testAddAndGetNode() throws ExecutionException, InterruptedException {
		Id nodeId = Id.random();
		assertTrue(context.addNode(nodeId, "localhost", 8080));
		assertFalse(context.addNode(nodeId, "localhost", 8080)); // repeat

		assertTrue(context.existsNode(nodeId).get());
		assertFalse(context.existsNode(Id.random()).get());

		FederatedNode node = context.getNode(nodeId, true).get();
		assertNotNull(node);
		assertEquals(nodeId, node.getId());
		assertEquals("localhost", node.getHost());

		assertNull(context.getNode(Id.random(), true).get());
	}

	@Test
	public void testAddAndGetService() throws ExecutionException, InterruptedException {
		Id nodeId = Id.random();
		Id peerId = Id.random();
		context.addNode(nodeId, "localhost", 8080);
		
		assertTrue(context.addService(nodeId, peerId, 123L, "https://svc/1"));
		assertFalse(context.addService(nodeId, peerId, 123L, "https://svc/1")); // repeat

		List<ServiceInfo> services = context.getServices(peerId, nodeId).get();
		assertEquals(1, services.size());
		assertEquals("https://svc/1", services.get(0).getEndpoint());

		assertTrue(context.addService(nodeId, peerId, 456L, "https://svc/2")); 
		assertFalse(context.addService(nodeId, peerId, 456L, "https://svc/2")); // repeat

		assertTrue(context.addService(nodeId, Id.random(), 0, "https://another.svc"));

		Id anotherNodeId = Id.random();
		assertTrue(context.addNode(anotherNodeId, "localhost", 8088));
		assertTrue(context.addService(anotherNodeId, peerId, 789L, "https://another.svc"));

		services = context.getServices(peerId, nodeId).get();
		assertEquals(2, services.size());
		for (ServiceInfo si : services) {
			assertEquals(peerId, si.getPeerId());
			assertEquals(nodeId, si.getNodeId());
			assertTrue(Set.of(123L, 456L).contains(si.getFingerprint()));
		}

		services = context.getServices(peerId).get();
		assertEquals(3, services.size());
	}

	@Test
	public void testRemoveNodeAndService() throws ExecutionException, InterruptedException {
		Id nodeId = Id.random();
		Id peerId = Id.random();
		context.addNode(nodeId, "localhost", 8080);
		context.addService(nodeId, peerId, 1L, "http://svc/1");
		context.addService(nodeId, peerId, 2L, "http://svc/2");
		context.addService(nodeId, peerId, 3L, "http://svc/3");
		context.addService(nodeId, peerId, 4L, "http://svc/4");
		context.addService(nodeId, peerId, 5L, "http://svc/5");
		context.addService(nodeId, Id.random(), 0L, "http://svc");

		List<ServiceInfo> services = context.getServices(peerId, nodeId).get();
		assertEquals(5, services.size());

		assertFalse(context.removeServices(Id.random(), nodeId));
		assertFalse(context.removeServices(peerId, Id.random()));
		assertFalse(context.removeService(peerId, 123L, nodeId));

		assertTrue(context.removeService(peerId, 3L, nodeId));
		services = context.getServices(peerId, nodeId).get();
		assertEquals(4, services.size());

		assertFalse(context.removeService(peerId, 3L, nodeId)); // repeat

		assertTrue(context.removeServices(peerId, nodeId));
		services = context.getServices(peerId, nodeId).get();
		assertTrue(services.isEmpty());

		assertFalse(context.removeServices(peerId, nodeId)); // repeat

		assertTrue(context.removeNode(nodeId));
		assertFalse(context.removeNode(nodeId)); // repeat
		assertFalse(context.existsNode(nodeId).get());

		assertFalse(context.removeNode(Id.random()));
	}

	@Test
	public void testAuthentication() throws ExecutionException, InterruptedException {
		Id nodeId = Id.random();
		Id peerId = Id.random();
		context.addNode(nodeId, "localhost", 8080);
		context.addService(nodeId, peerId, 1L, "http://svc/1");

		assertTrue(context.getAuthenticator().authenticateNode(nodeId).get());
		assertFalse(context.getAuthenticator().authenticateNode(Id.random()).get());
		assertTrue(context.getAuthenticator().authenticatePeer(nodeId, peerId).get());
		assertFalse(context.getAuthenticator().authenticatePeer(nodeId, Id.random()).get());
		assertFalse(context.getAuthenticator().authenticatePeer(Id.random(), peerId).get());
	}
}