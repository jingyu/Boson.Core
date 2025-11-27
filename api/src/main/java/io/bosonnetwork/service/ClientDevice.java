package io.bosonnetwork.service;

import io.bosonnetwork.Id;

public interface ClientDevice {
	Id getId();

	Id getUserId();

	String getName();

	String getApp();

	long getCreated();

	long getUpdated();

	long getLastSeen();

	String getLastAddress();
}