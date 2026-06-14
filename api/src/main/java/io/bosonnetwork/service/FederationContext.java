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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.service.impl.AllowAllFederationContext;
import io.bosonnetwork.service.impl.DisabledFederationContext;
import io.bosonnetwork.service.impl.StaticFederationContext;
import io.bosonnetwork.web.CwtAuth;

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
	 * @return a {@link CompletableFuture} that completes with the {@link SuperNodeInfo} if found, or
	 *         {@link Optional#empty()} if the node cannot be found or federated; it completes
	 *         exceptionally only on an unexpected error
	 */
	CompletableFuture<Optional<SuperNodeInfo>> getNode(Id nodeId, boolean tryFederateIfNotExists);

	/**
	 * Retrieves a federated node by its ID.
	 * <p>
	 * This is a convenience method that calls {@link #getNode(Id, boolean)} with {@code federateIfNotExists} set to {@code false}.
	 *
	 * @param nodeId the unique identifier of the node to retrieve
	 * @return a {@link CompletableFuture} that completes with the {@link SuperNodeInfo} if found, or
	 *         {@link Optional#empty()} if the node is not part of the federation; it completes
	 *         exceptionally only on an unexpected error
	 */
	default CompletableFuture<Optional<SuperNodeInfo>> getNode(Id nodeId) {
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
	 * Retrieves the services hosted by a specific federated node for the given peer.
	 *
	 * @param peerId the unique identifier of the service peer
	 * @param nodeId the unique identifier of the federated node hosting the service
	 * @return a {@link CompletableFuture} that completes with the list of {@link ServiceInfo} if found,
	 *         an empty list if no services match, or completes exceptionally on error
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
	 * Retrieves the instance of {@link CwtAuth} used for handling
	 * web token authentication within the federation.
	 *
	 * @return the {@link CwtAuth} instance responsible for managing web token authentication, or
	 *         {@code null} if this context does not support web authentication (e.g.; the
	 *         {@link #disabled() disabled} context)
	 */
	@Nullable CwtAuth getWebAuthenticator();

	/**
	 * Returns a federation context that reports the federation feature as turned off - for use
	 * by services that do not federate. Look up methods complete with empty/{@code null} results
	 * (no node or service is ever found); {@link #getAuthenticator()} rejects every challenge; and
	 * {@link #getWebAuthenticator()} returns {@code null}.
	 *
	 * @return a disabled {@link FederationContext}
	 */
	static FederationContext disabled() {
		return new DisabledFederationContext();
	}

	/**
	 * Returns an "allow-all" federation context - intended for development, smoke tests, and
	 * bring-up where peer/service discovery is faked. Concretely:
	 * <ul>
	 *   <li>{@link #getNode(Id, boolean)} synthesizes a {@code SuperNodeInfo} for any requested id,
	 *       and {@link #existsNode(Id)} always returns {@code true};</li>
	 *   <li>{@link #getServices(Id, Id)} returns a single synthesized {@code ServiceInfo} for the
	 *       requested peer/node pair; the federation-aware overloads behave the same;</li>
	 *   <li>{@link #reportIncident(Id, Id, IncidentType, String) reportIncident} is a no-op;</li>
	 *   <li>{@link #getAuthenticator()} verifies any non-null nonce/signature against the id's key
	 *       and accepts the pre-authenticated mode;</li>
	 *   <li>{@link #getWebAuthenticator()} returns a {@link CwtAuth} backed by the same synthesized
	 *       provider; {@code nodeIdentity} must be non-null when this is called.</li>
	 * </ul>
	 *
	 * @param nodeIdentity the identity that will sign issued web tokens (required if
	 *                     {@link #getWebAuthenticator()} will be called)
	 * @return a permissive {@link FederationContext}
	 */
	static FederationContext allowAll(Identity nodeIdentity) {
		return new AllowAllFederationContext(nodeIdentity);
	}

	/**
	 * Returns an in-memory federation context whose node and service registries are populated
	 * imperatively at test/bring-up time
	 * ({@link io.bosonnetwork.service.impl.StaticFederationContext} exposes
	 * {@code addNode(...)}, {@code addService(...)}, etc.). {@link #getWebAuthenticator()} returns
	 * a real {@link CwtAuth} backed by the registry and requires a non-null {@code nodeIdentity}.
	 *
	 * @param nodeIdentity the identity that will sign issued web tokens (required if
	 *                     {@link #getWebAuthenticator()} will be called)
	 * @return an in-memory {@link FederationContext}
	 */
	static FederationContext staticContext(Identity nodeIdentity) {
		return new StaticFederationContext(nodeIdentity);
	}
}