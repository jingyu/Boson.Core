/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import io.bosonnetwork.utils.Variable;
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

	/**
	 * Constructs a new instance of the StaticFederationContext class with the specified node identity.
	 *
	 * @param nodeIdentity The {@link Identity} associated with this client context.
	 *                     This identity is used for cryptographic operations and
	 *                     represents the unique identifier of the node.
	 */
	public StaticFederationContext(Identity nodeIdentity) {
		this.nodeIdentity = nodeIdentity;
		this.nodeServicesRegistry = new ConcurrentHashMap<>();
	}

	/**
	 * Adds a new node to the federation context registry with the specified details.
	 *
	 * @param nodeId the unique identifier of the node to be added; cannot be null
	 * @param host the hostname or IP address of the node; cannot be null
	 * @param port the port number on which the node is accessible; must be in the range 1 to 65535
	 * @param apiEndpoint the API endpoint URL for the node; can be null
	 * @return true if the node was successfully added, false if a node with the specified ID already exists in the registry
	 * @throws NullPointerException if nodeId or host is null
	 * @throws IllegalArgumentException if the port number is invalid (not between 1 and 65535, inclusive)
	 */
	public boolean addNode(Id nodeId, String host, int port, String apiEndpoint) {
		Objects.requireNonNull(nodeId);
		Objects.requireNonNull(host);
		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port");

		if (_existsNode(nodeId))
			return false;

		nodeServicesRegistry.computeIfAbsent(nodeId, k ->
				Pair.of(new PlainFederatedNode(nodeId, host, port, apiEndpoint), List.of()));
		return true;
	}


	/**
	 * Adds a new node to the federation context registry with the specified details.
	 *
	 * @param nodeId the unique identifier of the node to be added; cannot be null
	 * @param host the hostname or IP address of the node; cannot be null
	 * @param port the port number on which the node is accessible; must be in the range 1 to 65535
	 * @return true if the node was successfully added, false if a node with the specified ID already exists in the registry
	 * @throws NullPointerException if nodeId or host is null
	 * @throws IllegalArgumentException if the port number is invalid (not between 1 and 65535, inclusive)
	 */
	public boolean addNode(Id nodeId, String host, int port) {
		return addNode(nodeId, host, port, null);
	}

	private FederatedNode _getNode(Id nodeId) {
		return nodeServicesRegistry.getOrDefault(nodeId, Pair.empty()).a();
	}

	private boolean _existsNode(Id nodeId) {
		return nodeServicesRegistry.containsKey(nodeId);
	}

	/**
	 * Removes the node with the specified identifier from the federation context registry.
	 *
	 * @param nodeId the unique identifier of the node to be removed; cannot be null
	 * @return true if the node was successfully removed, false if no node with the specified ID exists in the registry
	 * @throws NullPointerException if nodeId is null
	 */
	public boolean removeNode(Id nodeId) {
		Objects.requireNonNull(nodeId);
		return nodeServicesRegistry.remove(nodeId) != null;
	}

	/**
	 * Adds a service to the specified node in the federation context registry.
	 *
	 * @param nodeId the unique identifier of the node to which the service is to be added
	 * @param peerId the unique identifier of the peer providing the service
	 * @param fingerprint the fingerprint of the service to ensure uniqueness
	 * @param endpoint the endpoint URL address of the service
	 * @param serviceId the unique identifier of the service
	 * @param serviceName the name of the service
	 * @return true if the service was successfully added, false if the service already exists for the given peer and node
	 * @throws IllegalArgumentException if the specified node does not exist
	 * @throws NullPointerException if nodeId or peerId is null
	 * @throws IllegalStateException if the node registry is in an invalid state
	 */
	public boolean addService(Id nodeId, Id peerId, long fingerprint, String endpoint, String serviceId, String serviceName) {
		Objects.requireNonNull(nodeId);
		Objects.requireNonNull(peerId);
		if (!_existsNode(nodeId))
			throw new IllegalArgumentException("Node does not exist");

		if (_existsService(peerId, fingerprint, nodeId))
			return false;

		nodeServicesRegistry.compute(nodeId, (k, v) -> {
			if (v == null)
				throw new IllegalStateException("Node does not exist");

			ServiceInfo newService = new PlainServiceInfo(peerId, fingerprint, nodeId, endpoint, serviceId, serviceName);
			if (v.b().isEmpty()) {
				return Pair.of(v.a(), List.of(newService));
			} else {
				List<ServiceInfo> services = new ArrayList<>(v.b());
				services.add(newService);
				return Pair.of(v.a(), List.copyOf(services));
			}
		});

		return true;
	}

	/**
	 * Adds a service to the specified node in the federation context registry with default values for optional parameters.
	 *
	 * @param nodeId the unique identifier of the node to which the service is to be added; cannot be null
	 * @param peerId the unique identifier of the peer providing the service; cannot be null
	 * @param fingerprint the fingerprint of the service to ensure uniqueness
	 * @param endpoint the endpoint URL address of the service; can be null
	 * @return true if the service was successfully added, false if the service already exists for the given peer and node
	 * @throws IllegalArgumentException if the specified node does not exist
	 * @throws NullPointerException if nodeId or peerId is null
	 * @throws IllegalStateException if the node registry is in an invalid state
	 */
	public boolean addService(Id nodeId, Id peerId, long fingerprint, String endpoint) {
		return addService(nodeId, peerId, fingerprint, endpoint, null, null);
	}

	private ServiceInfo _getService(Id peerId, long fingerprint, Id nodeId) {
		Pair<FederatedNode, List<ServiceInfo>> pair = nodeServicesRegistry.get(nodeId);
		return pair == null ? null : pair.b().stream()
				.filter(s -> s.getPeerId().equals(peerId) && s.getFingerprint() == fingerprint)
				.findFirst()
				.orElse(null);
	}

	private boolean _existsService(Id peerId, long fingerprint, Id nodeId) {
		return _getService(peerId, fingerprint, nodeId) != null;
	}

	private List<ServiceInfo> _getService(Id peerId, Id nodeId) {
		Pair<FederatedNode, List<ServiceInfo>> pair = nodeServicesRegistry.get(nodeId);
		return pair == null ? List.of() : pair.b().stream()
				.filter(s -> s.getPeerId().equals(peerId))
				.toList();
	}

	private boolean _existsService(Id peerId, Id nodeId) {
		return !_getService(peerId, nodeId).isEmpty();
	}

	private List<ServiceInfo> _getService(Id peerId) {
		return nodeServicesRegistry.values().stream()
				.flatMap(p -> p.b().stream())
				.filter(s -> s.getPeerId().equals(peerId))
				.toList();
	}

	/**
	 * Removes the specified service associated with a peer and node in the federation context registry.
	 *
	 * @param peerId the unique identifier of the peer providing the service; cannot be null
	 * @param fingerprint the fingerprint of the service to ensure uniqueness
	 * @param nodeId the unique identifier of the node from which the service is to be removed; cannot be null
	 * @return true if the service was successfully removed, false if no matching service exists for the given peer and node
	 * @throws NullPointerException if peerId or nodeId is null
	 */
	public boolean removeService(Id peerId, long fingerprint, Id nodeId) {
		Variable<Boolean> removed = Variable.of(false);
		nodeServicesRegistry.computeIfPresent(nodeId, (k, v) -> {
			if (v.b().isEmpty())
				return v;

			List<ServiceInfo> services = new ArrayList<>(v.b());
			boolean rm = services.removeIf(s -> s.getPeerId().equals(peerId) && s.getFingerprint() == fingerprint);
			if (rm) {
				removed.set(true);
				return Pair.of(v.a(), List.copyOf(services));
			} else {
				// no change
				return v;
			}
		});

		return removed.get();
	}

	/**
	 * Removes all services associated with a specific peer and node in the federation context registry.
	 *
	 * @param peerId the unique identifier of the peer whose services are to be removed; cannot be null
	 * @param nodeId the unique identifier of the node from which services are to be removed; cannot be null
	 * @return true if at least one service was successfully removed, false if no services matching the peer and node exist
	 * @throws NullPointerException if peerId or nodeId is null
	 */
	public boolean removeServices(Id peerId, Id nodeId) {
		Variable<Boolean> removed = Variable.of(false);
		nodeServicesRegistry.computeIfPresent(nodeId, (k, v) -> {
			if (v.b().isEmpty())
				return v;

			List<ServiceInfo> services = new ArrayList<>(v.b());
			boolean rm = services.removeIf(s -> s.getPeerId().equals(peerId));
			if (rm) {
				removed.set(true);
				return Pair.of(v.a(), List.copyOf(services));
			} else {
				// no change
				return v;
			}
		});

		return removed.get();
	}

	@Override
	public CompletableFuture<FederatedNode> getNode(Id nodeId, boolean federateIfNotExists) {
		return VertxFuture.succeededFuture(_getNode(nodeId));
	}

	@Override
	public CompletableFuture<Boolean> existsNode(Id nodeId) {
		return VertxFuture.succeededFuture(_existsNode(nodeId));
	}

	@Override
	public CompletableFuture<List<ServiceInfo>> getServices(Id peerId, Id nodeId) {
		return VertxFuture.succeededFuture(_getService(peerId, nodeId));
	}

	@Override
	public CompletableFuture<List<ServiceInfo>> getServices(Id peerId) {
		throw new UnsupportedOperationException("getServices is not supported");
		// return VertxFuture.succeededFuture(_getService(peerId));
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
				if (!_existsService(peerId, nodeId))
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
				return Future.succeededFuture(_getService(associated, subject).stream().findFirst().orElse(null));
			}
		});
	}
}