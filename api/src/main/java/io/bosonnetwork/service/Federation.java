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
import io.bosonnetwork.web.CompactWebTokenAuth;

/**
 * Federation manager (read-only) interface for the Boson Super Node services.
 * <p>
 * This interface provides methods to interact with and query information about other nodes
 * within the federation, including retrieving node details, checking existence, and finding
 * specific services hosted by federated nodes.
 */
public interface Federation {
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
	 * @param nodeId              the unique identifier of the node to retrieve
	 * @param federateIfNotExists if {@code true}, attempts to add the node to the federation if it is not already known
	 * @return a {@link CompletableFuture} that completes with the {@link FederatedNode} object if found,
	 *         or completes exceptionally/with null if the node cannot be found or federated
	 */
	CompletableFuture<? extends FederatedNode> getNode(Id nodeId, boolean federateIfNotExists);

	/**
	 * Retrieves a federated node by its ID.
	 * <p>
	 * This is a convenience method that calls {@link #getNode(Id, boolean)} with {@code federateIfNotExists} set to {@code false}.
	 *
	 * @param nodeId the unique identifier of the node to retrieve
	 * @return a {@link CompletableFuture} that completes with the {@link FederatedNode} object if found,
	 *         or completes exceptionally/with null if the node is not part of the federation
	 */
	default CompletableFuture<? extends FederatedNode> getNode(Id nodeId) {
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
	CompletableFuture<List<? extends ServiceInfo>> getServices(Id peerId, Id nodeId);

	/**
	 * Retrieves a list of services associated with a specific peer identified by its ID.
	 *
	 * @param peerId the unique identifier of the peer whose services are to be queried
	 * @return a {@link CompletableFuture} that completes with a list of {@link ServiceInfo} objects
	 *         representing the services associated with the specified peer, or completes exceptionally
	 *         if an error occurs while retrieving the services
	 */
	CompletableFuture<List<? extends ServiceInfo>> getServices(Id peerId);

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
}