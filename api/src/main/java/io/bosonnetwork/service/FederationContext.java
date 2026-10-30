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

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.Node;
import io.bosonnetwork.service.impl.AllowAllFederationContext;
import io.bosonnetwork.service.impl.DisabledFederationContext;
import io.bosonnetwork.service.impl.OpenFederationContext;
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
 *   <li><b>By node</b> - {@link #describe(Id)}: the node's signed {@link FederatedNodeDescriptor}, which lists
 *       every service it runs; {@link #resolve(Id, String)} resolves one of them.</li>
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
	 * Resolves a peer this service is about to reach: finds its announcement, verifies that it carries its
	 * node's attestation, and tells whether traffic to it may go - whether its node is admitted here, and
	 * whether it can be expected to accept this node's traffic.
	 * <p>
	 * It never waits on another node; what it can't establish from what is known it reports as a reason.
	 * A host may look into the other node, or propose a relationship to it, in the background: it then
	 * answers {@link Admission.Reason#PENDING PENDING}, and tells the outcome through
	 * {@link #onChange(Consumer)}.
	 *
	 * @param peerId the peer to resolve
	 * @return a {@link CompletableFuture} that completes with the resolution; it completes exceptionally
	 *         only on an unexpected error
	 */
	CompletableFuture<Resolution> resolve(Id peerId);

	/**
	 * Finds a peer, verified as {@link #resolve(Id)} verifies it, and tells only this node's own admission
	 * of its node: nothing about whether the other node takes this one's traffic, and nothing looked into
	 * or proposed in the background. For a service whose requests to other nodes ask nothing of them, such
	 * as fetching content anyone may fetch, which decides for itself what of the admission applies.
	 * <p>
	 * A context with nothing to start in the background answers as {@link #resolve(Id)} does.
	 *
	 * @param peerId the peer to find
	 * @return a {@link CompletableFuture} that completes with the resolution; it completes exceptionally
	 *         only on an unexpected error
	 */
	default CompletableFuture<Resolution> find(Id peerId) {
		return resolve(peerId);
	}

	/**
	 * Resolves a node's service of a type, for a caller that knows the node but not the service's peer
	 * id: the peer id from the node's {@linkplain #describe(Id) descriptor}, then the peer
	 * {@linkplain #resolve(Id) resolved} as any other, so that its own attested announcement still decides
	 * where it is and whose it is.
	 * <p>
	 * Unlike {@link #resolve(Id)}, it may wait on the other node, for its descriptor.
	 *
	 * @param nodeId      the node
	 * @param serviceType the service type id ({@code io.bosonnetwork.photonmessaging}, ...)
	 * @return a {@link CompletableFuture} that completes with the resolution:
	 *         {@link Admission.Reason#UNKNOWN_PEER UNKNOWN_PEER} if the node or its descriptor can't be
	 *         found, or the descriptor lists no such service;
	 *         {@link Admission.Reason#NOT_FEDERATING NOT_FEDERATING} if the node doesn't federate it; it
	 *         completes exceptionally only on an unexpected error
	 */
	default CompletableFuture<Resolution> resolve(Id nodeId, String serviceType) {
		return describe(nodeId).thenCompose(descriptor -> {
			FederatedNodeDescriptor.Service listed = null;
			if (descriptor.isPresent()) {
				for (FederatedNodeDescriptor.Service s : descriptor.get().getServices(serviceType)) {
					if (listed == null || (s.isFederation() && !listed.isFederation()))
						listed = s;
				}
			}
			if (listed == null)
				return CompletableFuture.completedFuture(new Resolution(null,
						new Admission(Admission.Reason.UNKNOWN_PEER, Duration.ofMinutes(1))));

			boolean federated = listed.isFederation();
			return resolve(listed.getPeerId()).thenApply(r -> {
				FederatedService service = r.service();
				// The descriptor named a peer that belongs to another node: no service of this one.
				if (service != null && !service.nodeId().equals(nodeId))
					return new Resolution(null, new Admission(Admission.Reason.UNVERIFIED_PEER, Duration.ofMinutes(1)));
				if (service != null && !federated && r.admission().decision() != Admission.Decision.DENY)
					return new Resolution(service, new Admission(Admission.Reason.NOT_FEDERATING, r.admission().maxAge()));
				return r;
			});
		});
	}

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
	CompletableFuture<Optional<FederatedNodeDescriptor>> describe(Id nodeId);

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
	 * calling service's announcement must be attested by that node, and the node must be admitted. A host
	 * may also verify a node it doesn't know before admitting it, and answer {@code 503} with
	 * {@code Retry-After} while it can't. A token that doesn't verify fails with {@code 401}; a node not
	 * admitted, with {@code 403}.
	 * The authenticated user's principal is the calling {@link FederatedService}, with the
	 * {@link Role#FEDERATION federation} role.
	 *
	 * @return the authenticator
	 */
	CwtAuth getWebAuthenticator();

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
	 * Returns a context that federates openly through a node's DHT, with no host to decide: a peer
	 * resolves to the service its attested announcements show, and every node is admitted with
	 * {@link Admission.Reason#OPEN OPEN}. For tests, development, and services run without a Director.
	 *
	 * @param node          the local node, whose DHT is searched
	 * @param servicePeerId the peer id of the service the context is for: the audience its web
	 *                      authenticator expects, or {@code null} to accept tokens for any audience
	 * @return an open {@link FederationContext}
	 */
	static FederationContext open(Node node, @Nullable Id servicePeerId) {
		return new OpenFederationContext(node, servicePeerId);
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
