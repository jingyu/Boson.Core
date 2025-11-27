package io.bosonnetwork.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.bosonnetwork.Id;

public interface Clients {
	CompletableFuture<? extends ClientUser> getUser(Id userId);

	CompletableFuture<Boolean> existsUser(Id userId);

	CompletableFuture<List<? extends ClientDevice>> getDevices(Id userId);

	CompletableFuture<? extends ClientDevice> getDevice(Id deviceId);

	CompletableFuture<Boolean> existsDevice(Id deviceId);

	CompletableFuture<Boolean> existsDevice(Id userId, Id deviceId);

	// CompletableFuture<ClientDevice> addDevice(Id deviceId, Id userId, String name, String app);
}