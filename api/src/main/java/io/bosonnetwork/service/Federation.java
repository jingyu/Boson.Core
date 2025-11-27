package io.bosonnetwork.service;

import java.util.concurrent.CompletableFuture;

import io.bosonnetwork.Id;

public interface Federation {
	public CompletableFuture<? extends FederatedNode> getNode(Id nodeId, boolean federateIfNotExists);

	default CompletableFuture<? extends FederatedNode> getNode(Id nodeId) {
		return getNode(nodeId, false);
	}

	public CompletableFuture<Boolean> existsNode(Id nodeId);



	// public CompletableFuture<Void> addNode(FederatedNode node);

	// public CompletableFuture<Void> updateNode(FederatedNode node);

	// public CompletableFuture<Boolean> removeNode(Id nodeId);

	// public CompletableFuture<List<FederatedService>> getAllServices(Id nodeId);

	public CompletableFuture<? extends FederatedService> getService(Id peerId);
}