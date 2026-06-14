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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import io.vertx.core.Future;
import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.service.FederationAuthenticator;
import io.bosonnetwork.service.FederationContext;
import io.bosonnetwork.service.Principal;
import io.bosonnetwork.service.ServiceInfo;
import io.bosonnetwork.service.SuperNodeInfo;
import io.bosonnetwork.utils.Variable;
import io.bosonnetwork.vertx.ContextualFuture;
import io.bosonnetwork.web.ClientProvider;
import io.bosonnetwork.web.CwtAuth;
import io.bosonnetwork.web.CwtAuthOptions;

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
	private final Map<Id, SuperNodeAndServices> nodeServicesRegistry;

	private record SuperNodeAndServices(SuperNodeInfo superNodeInfo, List<ServiceInfo> services) {}

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
	public boolean addNode(Id nodeId, String host, int port, @Nullable String apiEndpoint) {
		Objects.requireNonNull(nodeId);
		Objects.requireNonNull(host);
		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port");

		if (existsNodeSync(nodeId))
			return false;

		nodeServicesRegistry.computeIfAbsent(nodeId, k ->
				new SuperNodeAndServices(new PlainSuperNodeInfo(nodeId, host, port, apiEndpoint), List.of()));
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

	/**
	 * Retrieves a federated node from the registry synchronously based on the specified node identifier.
	 * If the node is not found in the registry, this method returns an empty {@link Optional}.
	 *
	 * @param nodeId the unique identifier of the node to retrieve; cannot be null
	 * @return an {@link Optional} containing the {@link SuperNodeInfo} associated with the specified
	 *         node ID, or an empty {@code Optional} if no such node exists
	 * @throws NullPointerException if nodeId is null
	 */
	public Optional<SuperNodeInfo> getNodeSync(Id nodeId) {
		Objects.requireNonNull(nodeId);
		SuperNodeAndServices sns = nodeServicesRegistry.get(nodeId);
		return sns == null ? Optional.empty() : Optional.of(sns.superNodeInfo);
	}

	/**
	 * Checks if a node with the specified identifier exists in the federation context registry.
	 *
	 * @param nodeId the unique identifier of the node to check for existence; cannot be null
	 * @return true if the node exists in the registry, false otherwise
	 * @throws NullPointerException if nodeId is null
	 */
	public boolean existsNodeSync(Id nodeId) {
		Objects.requireNonNull(nodeId);
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
	 * @param serviceType the unique type identifier of the service
	 * @param serviceName the name of the service
	 * @return true if the service was successfully added, false if the service already exists for the given peer and node
	 * @throws IllegalArgumentException if the specified node does not exist
	 * @throws NullPointerException if nodeId or peerId is null
	 * @throws IllegalStateException if the node registry is in an invalid state
	 */
	public boolean addService(Id nodeId, Id peerId, long fingerprint, String endpoint, @Nullable String serviceType, @Nullable String serviceName) {
		Objects.requireNonNull(nodeId);
		Objects.requireNonNull(peerId);
		if (!existsNodeSync(nodeId))
			throw new IllegalArgumentException("Node does not exist");

		if (existsServiceSync(peerId, fingerprint, nodeId))
			return false;

		nodeServicesRegistry.compute(nodeId, (k, v) -> {
			if (v == null)
				throw new IllegalStateException("Node does not exist");

			ServiceInfo newService = new PlainServiceInfo(peerId, fingerprint, nodeId, endpoint, serviceType, serviceName);
			if (v.services.isEmpty()) {
				return new SuperNodeAndServices(v.superNodeInfo, List.of(newService));
			} else {
				List<ServiceInfo> services = new ArrayList<>(v.services);
				services.add(newService);
				return new SuperNodeAndServices(v.superNodeInfo, List.copyOf(services));
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

	/**
	 * Retrieves a {@link ServiceInfo} object associated with the specified peer, fingerprint,
	 * and node synchronously. This method searches for a service in the registry identified
	 * by the given parameters and returns the corresponding service information if found.
	 *
	 * @param peerId the unique identifier of the peer providing the service; cannot be null
	 * @param fingerprint the fingerprint of the service to ensure uniqueness
	 * @param nodeId the unique identifier of the node where the service is registered; cannot be null
	 * @return an {@link Optional} containing the {@link ServiceInfo} matching the given peer,
	 *         fingerprint, and node, or an empty {@code Optional} if no such service exists
	 * @throws NullPointerException if peerId or nodeId is null
	 */
	public Optional<ServiceInfo> getServiceSync(Id peerId, long fingerprint, Id nodeId) {
		Objects.requireNonNull(nodeId);
		Objects.requireNonNull(peerId);

		SuperNodeAndServices sns = nodeServicesRegistry.get(nodeId);
		return sns == null ? Optional.empty() : sns.services.stream()
				.filter(s -> s.getPeerId().equals(peerId) && s.getFingerprint() == fingerprint)
				.findFirst();
	}

	/**
	 * Checks if a service exists synchronously for the given peer, fingerprint, and node identifier.
	 *
	 * @param peerId The identifier of the peer. Must not be null.
	 * @param fingerprint The fingerprint associated with the service.
	 * @param nodeId The identifier of the node. Must not be null.
	 * @return {@code true} if the service exists; {@code false} otherwise.
	 */
	public boolean existsServiceSync(Id peerId, long fingerprint, Id nodeId) {
		Objects.requireNonNull(peerId);
		Objects.requireNonNull(nodeId);
		return getServiceSync(peerId, fingerprint, nodeId).isPresent();
	}

	/**
	 * Retrieves the list of services associated with a specified peer ID under a given node ID.
	 * This method performs the search synchronously.
	 *
	 * @param peerId the unique identifier of the peer whose services are to be retrieved; must not be null
	 * @param nodeId the unique identifier of the node where the peer's services are registered; must not be null
	 * @return a list of {@code ServiceInfo} objects corresponding to the services associated with the specified peer ID,
	 * or an empty list if no matching services are found
	 */
	public List<ServiceInfo> getServicesSync(Id peerId, Id nodeId) {
		Objects.requireNonNull(peerId);
		Objects.requireNonNull(nodeId);
		SuperNodeAndServices sns = nodeServicesRegistry.get(nodeId);
		return sns == null ? List.of() : sns.services.stream()
				.filter(s -> s.getPeerId().equals(peerId))
				.toList();
	}

	/**
	 * Determines if a service exists synchronously for the specified peer and node identifiers.
	 *
	 * @param peerId the unique identifier of the peer; must not be null.
	 * @param nodeId the unique identifier of the node; must not be null.
	 * @return true if at least one service exists for the specified peer and node identifiers, false otherwise.
	 */
	public boolean existsServiceSync(Id peerId, Id nodeId) {
		Objects.requireNonNull(peerId);
		Objects.requireNonNull(nodeId);
		return !getServicesSync(peerId, nodeId).isEmpty();
	}

	/**
	 * Retrieves a list of ServiceInfo objects associated with the specified peer ID.
	 *
	 * @param peerId the unique identifier of the peer whose services are to be retrieved; must not be null
	 * @return a list of ServiceInfo objects that belong to the given peer ID
	 */
	public List<ServiceInfo> getServicesSync(Id peerId) {
		Objects.requireNonNull(peerId);
		return nodeServicesRegistry.values().stream()
				.flatMap(sns -> sns.services.stream())
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
			if (v.services.isEmpty())
				return v;

			List<ServiceInfo> services = new ArrayList<>(v.services);
			boolean rm = services.removeIf(s -> s.getPeerId().equals(peerId) && s.getFingerprint() == fingerprint);
			if (rm) {
				removed.set(true);
				return new SuperNodeAndServices(v.superNodeInfo, List.copyOf(services));
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
			if (v.services.isEmpty())
				return v;

			List<ServiceInfo> services = new ArrayList<>(v.services);
			boolean rm = services.removeIf(s -> s.getPeerId().equals(peerId));
			if (rm) {
				removed.set(true);
				return new SuperNodeAndServices(v.superNodeInfo, List.copyOf(services));
			} else {
				// no change
				return v;
			}
		});

		return removed.get();
	}

	@Override
	public CompletableFuture<Optional<SuperNodeInfo>> getNode(Id nodeId, boolean tryFederateIfNotExists) {
		return ContextualFuture.succeededFuture(getNodeSync(nodeId));
	}

	@Override
	public CompletableFuture<Boolean> existsNode(Id nodeId) {
		return ContextualFuture.succeededFuture(existsNodeSync(nodeId));
	}

	@Override
	public CompletableFuture<List<ServiceInfo>> getServices(Id peerId, Id nodeId) {
		return ContextualFuture.succeededFuture(getServicesSync(peerId, nodeId));
	}

	@Override
	public CompletableFuture<List<ServiceInfo>> getServices(Id peerId, boolean tryFederateIfNotExists) {
		return ContextualFuture.succeededFuture(getServicesSync(peerId));
	}

	@Override
	public CompletableFuture<Void> reportIncident(Id nodeId, Id peerId, IncidentType incident, String details) {
		return ContextualFuture.succeededFuture();
	}

	@Override
	public FederationAuthenticator getAuthenticator() {
		return new FederationAuthenticator() {
			@Override
			public CompletableFuture<Boolean> authenticateNode(Id nodeId, byte @Nullable [] nonce, byte @Nullable [] signature) {
				if (!existsNodeSync(nodeId))
					return ContextualFuture.succeededFuture(false);

				boolean valid = (nonce == null && signature == null) ||
						(nonce != null && signature != null && nodeId.toSignatureKey().verify(nonce, signature));
				return ContextualFuture.succeededFuture(valid);
			}

			@Override
			public CompletableFuture<Boolean> authenticatePeer(Id nodeId, Id peerId, byte @Nullable [] nonce, byte @Nullable [] signature) {
				if (!existsServiceSync(peerId, nodeId))
					return ContextualFuture.succeededFuture(false);

				boolean valid = (nonce == null && signature == null) ||
						(nonce != null && signature != null && peerId.toSignatureKey().verify(nonce, signature));
				return ContextualFuture.succeededFuture(valid);
			}
		};
	}

	@Override
	public CwtAuth getWebAuthenticator() {
		if (nodeIdentity == null)
			throw new IllegalStateException("Node identity is not set");

		CwtAuthOptions options = new CwtAuthOptions()
				.setIdentity(nodeIdentity)
				.setClientProvider(new ClientProvider() {
					@Override
					public Future<Optional<Principal>> getUser(Id nodeId) {
						Optional<Principal> principal = getNodeSync(nodeId).map(Principal.class::cast);
						return Future.succeededFuture(principal);
					}

					@Override
					public Future<Optional<Principal>> getClient(Id nodeId, Id peerId) {
						Optional<Principal> principal = getServicesSync(peerId, nodeId)
								.stream()
								.findFirst()
								.map(Principal.class::cast);
						return Future.succeededFuture(principal);
					}
				});

		return CwtAuth.create(options);
	}
}