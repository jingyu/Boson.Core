package io.bosonnetwork.service.impl;

import io.bosonnetwork.Id;
import io.bosonnetwork.service.ClientDevice;

/**
 * An implementation of the {@link ClientDevice} interface that represents a basic client device
 * with minimal information and default attribute values.
 */
public class PlainDevice implements ClientDevice {
	private final Id id;
	private final Id userId;
	private final long ts;

	PlainDevice(Id id, Id userId) {
		this.id = id;
		this.userId = userId;
		this.ts = System.currentTimeMillis();
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
		return "PlainDevice";
	}

	@Override
	public String getApp() {
		return "Unknown";
	}

	@Override
	public long getCreated() {
		return ts;
	}

	@Override
	public long getUpdated() {
		return ts;
	}

	@Override
	public long getLastSeen() {
		return ts;
	}

	@Override
	public String getLastAddress() {
		return "n/a";
	}
}