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

package io.bosonnetwork.service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.service.impl.AllowAllFederationContext;
import io.bosonnetwork.service.impl.DisabledFederationContext;
import io.bosonnetwork.service.impl.StaticFederationContext;
import io.bosonnetwork.web.CompactWebTokenAuth;

/**
 * Federation manager (read-only) interface for the Boson Super Node services.
 * <p>
 * This interface provides methods to interact with and query information about other nodes
 * within the federation, including retrieving node details, checking existence, and finding
 * specific services hosted by federated nodes.
 */
public interface FederationContext {
	/**
	 * Represents the type of incident that can occur within the federation.
	 * This enumeration is used to classify and report issues related to
	 * federated nodes or services.
	 */
	enum IncidentType {
		/** Indicates a complete failure or unavailability of a service. */
		SERVICE_OUTAGE,
		/** Indicates a service encountering an operation failure or error. */
		SERVICE_ERROR,
		/** Indicates a poorly formed or invalid request received. */
		MALFORMED_REQUEST,
		/** Indicates an invalid or improperly constructed response sent. */
		MALFORMED_RESPONSE
	}

	/**
	 * Retrieves a federated node by its ID.
	 *
	 * @param nodeId                 the unique identifier of the node to retrieve
	 * @param tryFederateIfNotExists if {@code true}, attempts to add the node to the federation if it is not already known
	 * @return a {@link CompletableFuture} that completes with the {@link FederatedNode} object if found,
	 *         or completes exceptionally/with null if the node cannot be found or federated
	 */
	CompletableFuture<FederatedNode> getNode(Id nodeId, boolean tryFederateIfNotExists);

	/**
	 * Retrieves a federated node by its ID.
	 * <p>
	 * This is a convenience method that calls {@link #getNode(Id, boolean)} with {@code federateIfNotExists} set to {@code false}.
	 *
	 * @param nodeId the unique identifier of the node to retrieve
	 * @return a {@link CompletableFuture} that completes with the {@link FederatedNode} object if found,
	 *         or completes exceptionally/with null if the node is not part of the federation
	 */
	default CompletableFuture<FederatedNode> getNode(Id nodeId) {
		return getNode(nodeId, false);
	}

	/**
	 * Checks if a node with the specified ID exists in the federation.
	 *
	 * @param nodeId the unique identifier of the node to check
	 * @return a {@link CompletableFuture} that completes with {@code true} if the node exists,
	 *         or {@code false} otherwise
	 */
	CompletableFuture<Boolean> existsNode(Id nodeId);

	/**
	 * Retrieves information about a specific service hosted by a federated node.
	 *
	 * @param nodeId the unique identifier of the node hosting the service
	 * @param peerId the unique identifier of the service peer
	 * @return a {@link CompletableFuture} that completes with the list of {@link ServiceInfo} if found,
	 *         or completes exceptionally/with null if the service cannot be located
	 */
	CompletableFuture<List<ServiceInfo>> getServices(Id peerId, Id nodeId);

	/**
	 * Retrieves a list of services associated with a specific peer identified by its ID.
	 * If the peer does not exist, this method attempts to look up and create a federation
	 * with the super node that hosts the peer, if specified by the caller.
	 *
	 * @param peerId                 the unique identifier of the peer whose services are to be queried
	 * @param tryFederateIfNotExists a flag indicating whether to attempt federating with the super node
	 *                               hosting the peer if the peer does not already exist
	 * @return a {@link CompletableFuture} that completes with a list of {@link ServiceInfo} objects
	 *         representing the services associated with the specified peer, or completes exceptionally
	 *         if an error occurs while retrieving the services
	 */
	CompletableFuture<List<ServiceInfo>> getServices(Id peerId, boolean tryFederateIfNotExists);

	/**
	 * Retrieves a list of services associated with a specific peer identified by its ID.
	 *
	 * @param peerId the unique identifier of the peer whose services are to be queried
	 * @return a {@link CompletableFuture} that completes with a list of {@link ServiceInfo} objects
	 *         representing the services associated with the specified peer, or completes exceptionally
	 *         if an error occurs while retrieving the services
	 */
	default CompletableFuture<List<ServiceInfo>> getServices(Id peerId) {
		return getServices(peerId, false);
	}

	/**
	 * Reports an incident associated with a specific federated node and peer.
	 *
	 * @param nodeId   the unique identifier of the federated node where the incident occurred
	 * @param peerId   the unique identifier of the peer involved in the incident
	 * @param incident the type of incident being reported
	 * @param details  a detailed description of the incident
	 * @return a {@link CompletableFuture} that completes when the incident has been reported successfully,
	 *         or completes exceptionally if an error occurs during the reporting process
	 */
	CompletableFuture<Void> reportIncident(Id nodeId, Id peerId, IncidentType incident, String details);

	/**
	 * Retrieves the instance of {@link FederationAuthenticator} associated with this federation.
	 *
	 * @return the {@link FederationAuthenticator} responsible for managing authentication
	 *         within the federation context.
	 */
	FederationAuthenticator getAuthenticator();

	/**
	 * Retrieves the instance of {@link CompactWebTokenAuth} used for handling
	 * web token authentication within the federation.
	 *
	 * @return the {@link CompactWebTokenAuth} instance responsible for managing
	 *         web token authentication.
	 */
	CompactWebTokenAuth getWebTokenAuthenticator();

	/**
	 * Creates and returns a disabled instance of FederationContext.
	 * This method is used to obtain a context object that represents
	 * a disabled federation state.
	 *
	 * @return a disabled FederationContext instance
	 */
	static FederationContext disabled() {
		return new DisabledFederationContext();
	}

	/**
	 * Creates and returns a federation context that allows all operations without requiring
	 * web token authentication. This method is intended for use in scenarios where open access
	 * is permitted, and web token authentication is bypassed.
	 *
	 * @return a {@link FederationContext} instance that allows all operations while bypassing
	 *         web token authentication
	 */
	static FederationContext allowAll() {
		return new AllowAllFederationContext(null);
	}

	/**
	 * Creates and returns a {@link FederationContext} that allows all operations
	 * without requiring web token authentication. This method is intended for use
	 * in scenarios where unrestricted access is permitted, bypassing authentication mechanisms.
	 *
	 * @param nodeIdentity the {@link Identity} representing the node's identity in the federation context
	 * @return a {@link FederationContext} instance that allows all operations while bypassing web token authentication
	 */
	static FederationContext allowAll(Identity nodeIdentity) {
		return new AllowAllFederationContext(nodeIdentity);
	}

	/**
	 * Creates and returns a static {@link FederationContext} instance that bypasses
	 * web token authentication. This method is suitable for scenarios requiring static
	 * federation configuration without the need for token-based authentication mechanisms.
	 *
	 * @return a {@link FederationContext} instance configured to bypass web token authentication
	 */
	static FederationContext staticContext() {
		return new StaticFederationContext(null);
	}

	/**
	 * Creates and returns a static {@link FederationContext} instance that is based
	 * on a specific node identity. This method is suitable for scenarios requiring
	 * static federation configuration for a particular node without the need for
	 * token-based authentication mechanisms.
	 *
	 * @param nodeIdentity the {@link Identity} representing the identity of the node
	 *                     within the federation context.
	 * @return a {@link FederationContext} instance configured to use the specified
	 *         node identity and operate with a static federation configuration.
	 */
	static FederationContext staticContext(Identity nodeIdentity) {
		return new StaticFederationContext(nodeIdentity);
	}
}