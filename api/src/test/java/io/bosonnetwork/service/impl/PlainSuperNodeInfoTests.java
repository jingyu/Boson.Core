package io.bosonnetwork.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;

public class PlainSuperNodeInfoTests {
	@Test
	public void testBasicProperties() {
		Id id = Id.random();
		PlainSuperNodeInfo node = new PlainSuperNodeInfo(id, "127.0.0.1", 8080);

		assertEquals(id, node.getId());
		assertEquals(List.of("127.0.0.1:8080"), node.getAddresses());
		assertEquals("http://127.0.0.1:8080", node.getApiEndpoint());
		assertFalse(node.getSoftware().isPresent());
		assertFalse(node.getVersion().isPresent());
		assertFalse(node.getName().isPresent());
		assertFalse(node.getLogo().isPresent());
		assertFalse(node.getWebsite().isPresent());
		assertFalse(node.getContact().isPresent());
		assertFalse(node.getDescription().isPresent());
		assertTrue(node.isFederated());
		assertEquals(1000, node.getReputation());
		assertTrue(node.getCreatedAt() > 0);
		assertEquals(node.getCreatedAt(), node.getUpdatedAt());
	}

	@Test
	public void testCustomApiEndpoint() {
		Id id = Id.random();
		PlainSuperNodeInfo node = new PlainSuperNodeInfo(id, List.of("127.0.0.1:8080"), "https://api.example.com");
		assertEquals("https://api.example.com", node.getApiEndpoint());
	}

	@Test
	public void testInvalidPort() {
		Id id = Id.random();
		assertThrows(IllegalArgumentException.class, () -> new PlainSuperNodeInfo(id, "127.0.0.1", 0));
		assertThrows(IllegalArgumentException.class, () -> new PlainSuperNodeInfo(id, "127.0.0.1", 70000));
	}
}