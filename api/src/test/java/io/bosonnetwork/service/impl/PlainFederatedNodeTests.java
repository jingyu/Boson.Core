package io.bosonnetwork.service.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import io.bosonnetwork.Id;

public class PlainFederatedNodeTests {
	@Test
	public void testBasicProperties() {
		Id id = Id.random();
		PlainFederatedNode node = new PlainFederatedNode(id, "127.0.0.1", 8080);

		assertEquals(id, node.getId());
		assertEquals("127.0.0.1", node.getHost());
		assertEquals(8080, node.getPort());
		assertEquals("http://127.0.0.1:8080", node.getApiEndpoint());
		assertNull(node.getSoftware());
		assertNull(node.getVersion());
		assertNull(node.getName());
		assertNull(node.getLogo());
		assertNull(node.getWebsite());
		assertNull(node.getContact());
		assertNull(node.getDescription());
		assertTrue(node.isTrusted());
		assertEquals(1000, node.getReputation());
		assertTrue(node.getCreated() > 0);
		assertEquals(node.getCreated(), node.getUpdated());
	}

	@Test
	public void testCustomApiEndpoint() {
		Id id = Id.random();
		PlainFederatedNode node = new PlainFederatedNode(id, "127.0.0.1", 8080, "https://api.example.com");
		assertEquals("https://api.example.com", node.getApiEndpoint());
	}

	@Test
	public void testInvalidPort() {
		Id id = Id.random();
		assertThrows(IllegalArgumentException.class, () -> new PlainFederatedNode(id, "127.0.0.1", 0));
		assertThrows(IllegalArgumentException.class, () -> new PlainFederatedNode(id, "127.0.0.1", 70000));
	}
}
