package io.bosonnetwork.service.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import io.bosonnetwork.Id;

public class PlainServiceInfoTests {
	@Test
	public void testBasicProperties() {
		Id peerId = Id.random();
		Id nodeId = Id.random();
		PlainServiceInfo info = new PlainServiceInfo(peerId, 12456L, nodeId, "boson://host:port");

		assertEquals(peerId, info.getPeerId());
		assertEquals(12456L, info.getFingerprint());
		assertEquals(nodeId, info.getNodeId());
		assertEquals("boson://host:port", info.getEndpoint());
		assertFalse(info.hasExtra());
		assertNull(info.getExtraData());
		assertEquals(Map.of(), info.getExtra());
		assertEquals(peerId.toString(), info.getServiceType());
		assertEquals(peerId.toAbbrBase58String(), info.getServiceName());
	}

	@Test
	public void testCustomServiceIdAndName() {
		Id peerId = Id.random();
		Id nodeId = Id.random();
		PlainServiceInfo info = new PlainServiceInfo(peerId, 1L, nodeId, "e", "mySvc", "My Service");

		assertEquals("mySvc", info.getServiceType());
		assertEquals("My Service", info.getServiceName());
	}
}