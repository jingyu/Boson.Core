package io.bosonnetwork.service.impl;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.vertx.core.Future;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.service.FederatedNode;
import io.bosonnetwork.service.FederationAuthenticator;
import io.bosonnetwork.service.FederationContext;
import io.bosonnetwork.service.ServiceInfo;
import io.bosonnetwork.vertx.VertxFuture;
import io.bosonnetwork.web.CompactWebTokenAuth;

/**
 * An implementation of the FederationContext interface that allows all federated interactions
 * without any restrictions. This context is permissive and assumes that all nodes, services,
 * and peers are valid and accessible.
 *
 * This class is primarily used in scenarios where no sophisticated authorization or validation
 * logic is required, and the environment is trusted or mock-based.
 */
public class AllowAllFederationContext implements FederationContext {
	private final Identity nodeIdentity;

	public AllowAllFederationContext(Identity nodeIdentity) {
		this.nodeIdentity = nodeIdentity;
	}

	@Override
	public CompletableFuture<FederatedNode> getNode(Id nodeId, boolean federateIfNotExists) {
		return VertxFuture.succeededFuture(new PlainFederatedNode(nodeId));
	}

	@Override
	public CompletableFuture<Boolean> existsNode(Id nodeId) {
		return VertxFuture.succeededFuture(true);
	}

	@Override
	public CompletableFuture<List<ServiceInfo>> getServices(Id peerId, Id nodeId) {
		return VertxFuture.succeededFuture(List.of(new PlainServiceInfo(peerId, nodeId)));
	}

	@Override
	public CompletableFuture<List<ServiceInfo>> getServices(Id peerId) {
		return VertxFuture.succeededFuture(List.of());
	}

	@Override
	public CompletableFuture<Void> reportIncident(Id nodeId, Id peerId, IncidentType incident, String details) {
		return VertxFuture.succeededFuture();
	}

	@Override
	public FederationAuthenticator getAuthenticator() {
		return new FederationAuthenticator() {
			@Override
			public CompletableFuture<Boolean> authenticateNode(Id nodeId, byte[] nonce, byte[] signature) {
				boolean valid = nonce == null || signature == null || nodeId.toSignatureKey().verify(nonce, signature);
				return VertxFuture.succeededFuture(valid);
			}

			@Override
			public CompletableFuture<Boolean> authenticatePeer(Id nodeId, Id peerId, byte[] nonce, byte[] signature) {
				boolean valid = nonce == null || signature == null || peerId.toSignatureKey().verify(nonce, signature);
				return VertxFuture.succeededFuture(valid);
			}
		};
	}

	@Override
	public CompactWebTokenAuth getWebTokenAuthenticator() {
		if (nodeIdentity == null)
			throw new IllegalStateException("Node identity is not set");

		return CompactWebTokenAuth.create(nodeIdentity, new CompactWebTokenAuth.UserRepository() {
			@Override
			public Future<FederatedNode> getSubject(Id subject) {
				return Future.succeededFuture(new PlainFederatedNode(subject));
			}

			@Override
			public Future<ServiceInfo> getAssociated(Id subject, Id associated) {
				return Future.succeededFuture(new PlainServiceInfo(associated, subject));
			}
		});
	}
}