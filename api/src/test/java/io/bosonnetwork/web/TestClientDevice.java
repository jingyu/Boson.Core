package io.bosonnetwork.web;

import io.bosonnetwork.Id;
import io.bosonnetwork.service.ClientDevice;

public class TestClientDevice implements ClientDevice {
	private final Id id;
	private final Id userId;
	private final String name;
	private final String app;
	private final long created;
	private final long updated;

	public TestClientDevice(Id id, Id userId, String name, String app) {
		this.id = id;
		this.userId = userId;
		this.name = name;
		this.app = app;
		this.created = System.currentTimeMillis();
		this.updated = created;
	}

	@Override
	public Id getId() {
		return id;
	}

	@Override
	public Id getUserId() {
		return userId;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public String getApp() {
		return app;
	}

	@Override
	public long getCreated() {
		return created;
	}

	@Override
	public long getUpdated() {
		return updated;
	}

	@Override
	public long getLastSeen() {
		return 0;
	}

	@Override
	public String getLastAddress() {
		return null;
	}
}