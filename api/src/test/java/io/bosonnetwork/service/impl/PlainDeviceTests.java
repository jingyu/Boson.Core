package io.bosonnetwork.service.impl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import io.bosonnetwork.Id;

public class PlainDeviceTests {
	@Test
	public void testBasicProperties() {
		Id userId = Id.random();
		Id deviceId = Id.random();
		PlainDevice device = new PlainDevice(deviceId, userId, "My Phone", "BosonApp");

		assertEquals(deviceId, device.getId());
		assertEquals(userId, device.getUserId());
		assertEquals("My Phone", device.getName());
		assertEquals("BosonApp", device.getApp());
		assertTrue(device.getCreated() > 0);
		assertEquals(device.getCreated(), device.getUpdated());
		assertEquals(device.getCreated(), device.getLastSeen());
		assertEquals("n/a", device.getLastAddress());
	}

	@Test
	public void testDefaultValues() {
		Id userId = Id.random();
		Id deviceId = Id.random();
		PlainDevice device = new PlainDevice(deviceId, userId);

		assertEquals(deviceId.toAbbrBase58String(), device.getName());
		assertEquals("unknown", device.getApp());
	}
}
