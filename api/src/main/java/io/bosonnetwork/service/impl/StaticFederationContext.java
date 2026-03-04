package io.bosonnetwork.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import io.vertx.core.Future;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.service.FederatedNode;
import io.bosonnetwork.service.FederationAuthenticator;
import io.bosonnetwork.service.FederationContext;
import io.bosonnetwork.service.ServiceInfo;
import io.bosonnetwork.utils.Pair;
import io.bosonnetwork.vertx.VertxFuture;
import io.bosonnetwork.web.CompactWebTokenAuth;

/**
 * The StaticFederationContext class provides an implementation of the FederationContext interface.
 * It handles the registration and management of federated nodes and their associated services
 * within a static federation context.
 * <p>
 * This class is responsible for:
 * - Managing the lifecycle of nodes and their associated services within a federation.
 * - Providing mechanisms to add, remove, and query nodes and services.
 * - Authenticating nodes and services.
 * - Returning a web token authenticator for secure access.
 * - Local reporting of incidents related to nodes or services.
 * <p>
 * The class uses a thread-safe data structure to store the registry of nodes and their services.
 */
public class StaticFederationContext implements FederationContext {
	private final Identity nodeIdentity;
	private final Map<Id, Pair<FederatedNode, List<ServiceInfo>>> nodeServicesRegistry;

	public StaticFederationContext(Identity nodeIdentity, Map<Id, List<Id>> nodeServicesMap) {
		this.nodeIdentity = nodeIdentity;
		this.nodeServicesRegistry = new ConcurrentHashMap<>();

		if (nodeServicesMap != null && !nodeServicesMap.isEmpty()) {
			nodeServicesMap.forEach((nodeId, peerIds) -> {
				FederatedNode node = new PlainFederatedNode(nodeId);
				List<ServiceInfo> services = (peerIds == null || peerIds.isEmpty()) ? List.of() :
						peerIds.stream().map(id -> (ServiceInfo)(new PlainServiceInfo(id, nodeId))).toList();
				this.nodeServicesRegistry.put(nodeId, Pair.of(node, services));
			});
		}
	}

	public boolean addNode(Id nodeId) {
		nodeServicesRegistry.computeIfAbsent(nodeId, k -> Pair.of(new PlainFederatedNode(nodeId), List.of()));
		return true;
	}

	private FederatedNode _getNode(Id nodeId) {
		Pair<FederatedNode, List<ServiceInfo>> pair = nodeServicesRegistry.get(nodeId);
		return pair == null ? null : pair.a();
	}

	private boolean _existsNode(Id nodeId) {
		return nodeServicesRegistry.containsKey(nodeId);
	}

	public void removeNode(Id nodeId) {
		nodeServicesRegistry.remove(nodeId);
	}

	public boolean addService(Id nodeId, Id peerId) {
		List<ServiceInfo> existing = _getService(peerId, nodeId);
		if (!existing.isEmpty())
			return true;

		nodeServicesRegistry.compute(nodeId, (k, v) -> {
			if (v == null)
				return Pair.of(new PlainFederatedNode(nodeId), List.of(new PlainServiceInfo(peerId, nodeId)));

			List<ServiceInfo> devices = new ArrayList<>(v.b());
			devices.add(new PlainServiceInfo(peerId, nodeId));
			return Pair.of(v.a(), List.copyOf(devices));
		});

		return true;
	}

	private List<ServiceInfo> _getService( Id nodeId, Id peerId) {
		Pair<FederatedNode, List<ServiceInfo>> pair = nodeServicesRegistry.get(nodeId);
		return pair == null ? List.of() : pair.b().stream().filter(s -> s.getPeerId().equals(peerId)).toList();
	}

	private List<ServiceInfo> _getService(Id peerId) {
		return nodeServicesRegistry.values().stream()
				.flatMap(p -> p.b().stream())
				.filter(s -> s.getPeerId().equals(peerId))
				.toList();
	}

	private boolean _existsService(Id nodeId, Id peerId) {
		return !_getService(nodeId, peerId).isEmpty();
	}

	public void removeService(Id nodeId, Id peerId) {
		nodeServicesRegistry.computeIfPresent(nodeId, (k, v) -> {
			if (v.b().isEmpty())
				return v;

			List<ServiceInfo> services = new ArrayList<>(v.b());
			services.removeIf(s -> s.getPeerId().equals(peerId));
			return Pair.of(v.a(), List.copyOf(services));
		});
	}

	@Override
	public CompletableFuture<FederatedNode> getNode(Id nodeId, boolean federateIfNotExists) {
		FederatedNode node = _getNode(nodeId);
		return node != null ? VertxFuture.succeededFuture(node) :
				federateIfNotExists ? VertxFuture.failedFuture("Unsupported") : VertxFuture.succeededFuture(null);
	}

	@Override
	public CompletableFuture<Boolean> existsNode(Id nodeId) {
		return VertxFuture.succeededFuture(_existsNode(nodeId));
	}

	@Override
	public CompletableFuture<List<ServiceInfo>> getServices(Id peerId, Id nodeId) {
		return VertxFuture.succeededFuture(_getService(nodeId, peerId));
	}

	@Override
	public CompletableFuture<List<ServiceInfo>> getServices(Id peerId) {
		return VertxFuture.succeededFuture(_getService(peerId));
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
				if (!_existsNode(nodeId))
					return VertxFuture.succeededFuture(false);

				boolean valid = nonce == null || signature == null || nodeId.toSignatureKey().verify(nonce, signature);
				return VertxFuture.succeededFuture(valid);
			}

			@Override
			public CompletableFuture<Boolean> authenticatePeer(Id nodeId, Id peerId, byte[] nonce, byte[] signature) {
				if (!_existsService(nodeId, peerId))
					return VertxFuture.succeededFuture(false);

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
				return Future.succeededFuture(_getNode(subject));
			}

			@Override
			public Future<ServiceInfo> getAssociated(Id subject, Id associated) {
				return Future.succeededFuture(_getService(subject, associated).stream().findFirst().orElse(null));
			}
		});
	}
}