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
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.service.impl.AllowAllFederationContext;
import io.bosonnetwork.service.impl.DisabledFederationContext;
import io.bosonnetwork.service.impl.StaticFederationContext;
import io.bosonnetwork.web.CwtAuth;

/**
 * What a service needs from federation: where another node's services are, whether their node is
 * admitted, and a way to authenticate the requests they send.
 * <p>
 * Each service gets its own context from its host, scoped to its own type and peer id: admission may
 * differ by service type, and the web authenticator accepts only tokens addressed to the service.
 *
 * <h2>The two lookups</h2>
 * <ul>
 *   <li><b>By peer id</b> - {@link #resolve(Id)}: the service's announcement in the DHT, verified to
 *       carry its node's attestation, and the admission of that node. Nearly all traffic needs only this:
 *       a caller always knows the peer it is about to talk to.</li>
 *   <li><b>By node</b> - {@link #describe(Id)}: the node's signed {@link NodeDescriptor}, which lists
 *       every service it runs.</li>
 * </ul>
 * A refusal always comes with its reason ({@link Admission.Reason}), so a service never has to drop
 * traffic without being able to say why.
 *
 * <h2>Lifecycle and feedback</h2>
 * Results may be cached as long as their {@link Admission#maxAge()} says, and must be dropped when
 * {@link #onChange(Consumer)} reports that a node's admission has changed. Services {@link #report} the
 * outcome of what they exchanged with other nodes, which the host uses to track their health.
 *
 * <h2>Outbound requests</h2>
 * The context holds no service key and signs nothing. {@link FederatedService#trustManager()} and
 * {@link FederatedService#accessTokens(Identity, Id)} give a service what it needs to call another
 * one with its own identity.
 *
 * <h2>Version 1</h2>
 * The methods that return {@link SuperNodeInfo} and {@link ServiceInfo}, {@link #reportIncident} and
 * {@link #getAuthenticator()} are the first version of this interface. They are deprecated and will be
 * removed once every service has moved to the methods above.
 */
public interface FederationContext {
	/**
	 * The outcome of an exchange with another node's service, as {@linkplain #report reported} by the
	 * service that made or received it.
	 */
	enum Outcome {
		/** The exchange succeeded. */
		SUCCESS,
		/** The other side did not answer in time. */
		TIMEOUT,
		/** The other side could not be reached. */
		UNREACHABLE,
		/** The other side refused the request: authentication, admission, or a limit. */
		REFUSED,
		/** The other side sent a request this service could not accept as well formed. */
		MALFORMED_REQUEST,
		/** The other side answered with a response this service could not accept as well formed. */
		MALFORMED_RESPONSE,
		/** The other side failed to handle the request. */
		ERROR
	}

	/**
	 * A registered {@linkplain #onChange(Consumer) listener}; closing it removes the listener.
	 */
	interface Subscription extends AutoCloseable {
		/**
		 * Removes the listener. Idempotent.
		 */
		@Override
		void close();
	}

	/**
	 * Resolves a peer: finds its announcement, verifies that it carries its node's attestation, and tells
	 * whether that node is admitted for this service.
	 * <p>
	 * It never starts a federation handshake and never waits on another node; what it can't establish
	 * from what is known it reports as a reason.
	 *
	 * @param peerId the peer to resolve
	 * @return a {@link CompletableFuture} that completes with the resolution; it completes exceptionally
	 *         only on an unexpected error
	 */
	CompletableFuture<Resolution> resolve(Id peerId);

	/**
	 * Tells whether a node is admitted for this service.
	 *
	 * @param nodeId the node
	 * @return a {@link CompletableFuture} that completes with the admission; it completes exceptionally
	 *         only on an unexpected error
	 */
	CompletableFuture<Admission> admit(Id nodeId);

	/**
	 * Returns a node's signed descriptor: what it runs and what it speaks.
	 *
	 * @param nodeId the node
	 * @return a {@link CompletableFuture} that completes with the verified descriptor, or with
	 *         {@link Optional#empty()} if the node or its descriptor can't be reached or verified; it
	 *         completes exceptionally only on an unexpected error
	 */
	CompletableFuture<Optional<NodeDescriptor>> describe(Id nodeId);

	/**
	 * Reports the outcome of an exchange with another node's service. Never blocks and never fails.
	 *
	 * @param nodeId  the other node
	 * @param peerId  the other node's service
	 * @param outcome the outcome
	 * @param detail  what happened, for the operator, or {@code null}
	 */
	void report(Id nodeId, Id peerId, Outcome outcome, @Nullable String detail);

	/**
	 * Registers a listener for changes in nodes' admission. The listener is called on an arbitrary
	 * thread and must neither block nor throw; a service that runs on an event loop should hand the event
	 * to it.
	 *
	 * @param listener the listener
	 * @return the subscription; close it to remove the listener
	 */
	Subscription onChange(Consumer<FederationEvent> listener);

	/**
	 * Returns the authenticator of requests sent by other nodes' services.
	 * <p>
	 * It accepts a self-issued token whose issuer is the calling service, whose client id is the calling
	 * service's peer id, whose subject is that service's node, and whose audience is this service; the
	 * calling service must {@linkplain #resolve(Id) resolve} to that node, and the node must be admitted.
	 * The authenticated user's principal is the calling {@link FederatedService}, with the
	 * {@link Role#FEDERATION federation} role.
	 *
	 * @return the authenticator
	 */
	CwtAuth getWebAuthenticator();

	// ---- Version 1 ------------------------------------------------------------------------------------

	/**
	 * Represents the type of incident that can occur within the federation.
	 *
	 * @deprecated use {@link Outcome} with {@link #report(Id, Id, Outcome, String)}
	 */
	@Deprecated(forRemoval = true)
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
	 *         {@link Optional#empty()} if the node cannot be found or federated
	 * @deprecated use {@link #admit(Id)} and {@link #describe(Id)}
	 */
	@Deprecated(forRemoval = true)
	@SuppressWarnings("removal")
	CompletableFuture<Optional<SuperNodeInfo>> getNode(Id nodeId, boolean tryFederateIfNotExists);

	/**
	 * Retrieves a federated node by its ID, without trying to federate.
	 *
	 * @param nodeId the unique identifier of the node to retrieve
	 * @return a {@link CompletableFuture} that completes with the {@link SuperNodeInfo} if found, or
	 *         {@link Optional#empty()} if the node is not part of the federation
	 * @deprecated use {@link #admit(Id)} and {@link #describe(Id)}
	 */
	@Deprecated(forRemoval = true)
	@SuppressWarnings("removal")
	default CompletableFuture<Optional<SuperNodeInfo>> getNode(Id nodeId) {
		return getNode(nodeId, false);
	}

	/**
	 * Checks if a node with the specified ID exists in the federation.
	 *
	 * @param nodeId the unique identifier of the node to check
	 * @return a {@link CompletableFuture} that completes with {@code true} if the node exists
	 * @deprecated use {@link #admit(Id)}
	 */
	@Deprecated(forRemoval = true)
	CompletableFuture<Boolean> existsNode(Id nodeId);

	/**
	 * Retrieves the services hosted by a specific federated node for the given peer.
	 *
	 * @param peerId the unique identifier of the service peer
	 * @param nodeId the unique identifier of the federated node hosting the service
	 * @return a {@link CompletableFuture} that completes with the list of {@link ServiceInfo}
	 * @deprecated use {@link #resolve(Id)}
	 */
	@Deprecated(forRemoval = true)
	@SuppressWarnings("removal")
	CompletableFuture<List<ServiceInfo>> getServices(Id peerId, Id nodeId);

	/**
	 * Retrieves the services associated with a peer, trying to federate with its node if asked.
	 *
	 * @param peerId                 the unique identifier of the peer whose services are to be queried
	 * @param tryFederateIfNotExists whether to attempt federating with the node hosting the peer
	 * @return a {@link CompletableFuture} that completes with the list of {@link ServiceInfo}
	 * @deprecated use {@link #resolve(Id)}, which never starts a handshake
	 */
	@Deprecated(forRemoval = true)
	@SuppressWarnings("removal")
	CompletableFuture<List<ServiceInfo>> getServices(Id peerId, boolean tryFederateIfNotExists);

	/**
	 * Retrieves the services associated with a peer, without trying to federate.
	 *
	 * @param peerId the unique identifier of the peer whose services are to be queried
	 * @return a {@link CompletableFuture} that completes with the list of {@link ServiceInfo}
	 * @deprecated use {@link #resolve(Id)}
	 */
	@Deprecated(forRemoval = true)
	@SuppressWarnings("removal")
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
	 * @return a {@link CompletableFuture} that completes when the incident has been reported
	 * @deprecated use {@link #report(Id, Id, Outcome, String)}
	 */
	@Deprecated(forRemoval = true)
	@SuppressWarnings("removal")
	CompletableFuture<Void> reportIncident(Id nodeId, Id peerId, IncidentType incident, String details);

	/**
	 * Retrieves the {@link FederationAuthenticator} of this federation.
	 *
	 * @return the authenticator
	 * @deprecated use {@link #getWebAuthenticator()} for requests, and {@link #resolve(Id)} or
	 *             {@link #admit(Id)} for admission
	 */
	@Deprecated(forRemoval = true)
	@SuppressWarnings("removal")
	FederationAuthenticator getAuthenticator();

	// ---- In-memory contexts ---------------------------------------------------------------------------

	/**
	 * Returns a context for a host where federation is turned off: every peer and node is refused with
	 * {@link Admission.Reason#DISABLED DISABLED}, no descriptor is found, and the web authenticator
	 * accepts no token.
	 *
	 * @return a disabled {@link FederationContext}
	 */
	static FederationContext disabled() {
		return new DisabledFederationContext();
	}

	/**
	 * Returns an "allow-all" context, for development, smoke tests and bring-up where discovery is faked:
	 * every peer resolves to a synthesized service of the node {@link Id#zero()} unless the token says
	 * otherwise, every node is admitted with {@link Admission.Reason#OPEN OPEN}, no descriptor is found,
	 * reports are dropped, and the web authenticator accepts any well-formed federation token.
	 *
	 * @param nodeIdentity the identity of the local node (required if {@link #getWebAuthenticator()} will
	 *                     be called)
	 * @return a permissive {@link FederationContext}
	 */
	static FederationContext allowAll(Identity nodeIdentity) {
		return new AllowAllFederationContext(nodeIdentity);
	}

	/**
	 * Returns an in-memory context whose nodes, services and descriptors are populated imperatively at
	 * test or bring-up time ({@link StaticFederationContext} exposes {@code addNode(...)},
	 * {@code addService(...)}, {@code setAdmission(...)}, ...). Its web authenticator accepts tokens for
	 * any audience.
	 *
	 * @param nodeIdentity the identity of the local node
	 * @return an in-memory {@link FederationContext}
	 */
	static FederationContext staticContext(Identity nodeIdentity) {
		return new StaticFederationContext(nodeIdentity);
	}

	/**
	 * Returns an in-memory context like {@link #staticContext(Identity)}, whose web authenticator accepts
	 * only tokens addressed to the given service.
	 *
	 * @param nodeIdentity  the identity of the local node
	 * @param servicePeerId the peer id of the service the context is for
	 * @return an in-memory {@link FederationContext}
	 */
	static FederationContext staticContext(Identity nodeIdentity, Id servicePeerId) {
		return new StaticFederationContext(nodeIdentity, servicePeerId);
	}
}
