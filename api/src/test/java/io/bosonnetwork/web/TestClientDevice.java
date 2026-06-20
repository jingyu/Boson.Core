package io.bosonnetwork.web;

import io.bosonnetwork.Id;
import io.bosonnetwork.service.impl.PlainDevice;

public class TestClientDevice extends PlainDevice {
	public TestClientDevice(Id id, Id userId, String name, String app) {
		super(id, userId, name, app);
	}
}