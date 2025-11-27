package io.bosonnetwork.service;

import java.util.concurrent.CompletableFuture;

import io.bosonnetwork.Id;

public interface FederationAuthenticator {
	CompletableFuture<Boolean> authenticateNode(Id nodeId, byte[] nonce, byte[] signature);

	CompletableFuture<Boolean> authenticatePeer(Id nodeId, Id peerId, byte[] nonce, byte[] signature);
}