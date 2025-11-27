package io.bosonnetwork.service;

import java.util.concurrent.CompletableFuture;

import io.bosonnetwork.Id;

public interface ClientAuthenticator {
	CompletableFuture<Boolean> authenticateUser(Id userId, byte[] nonce, byte[] signature);

	CompletableFuture<Boolean> authenticateDevice(Id userId, Id deviceId, byte[] nonce, byte[] signature);
}