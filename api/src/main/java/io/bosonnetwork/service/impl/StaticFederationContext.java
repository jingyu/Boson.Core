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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.vertx.core.Future;
import io.vertx.ext.web.handler.HttpException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.service.Admission;
import io.bosonnetwork.service.FederatedNodeDescriptor;
import io.bosonnetwork.service.FederatedService;
import io.bosonnetwork.service.FederationContext;
import io.bosonnetwork.service.FederationEvent;
import io.bosonnetwork.service.Principal;
import io.bosonnetwork.service.Resolution;
import io.bosonnetwork.utils.Variable;
import io.bosonnetwork.vertx.ContextualFuture;
import io.bosonnetwork.web.ClientProvider;
import io.bosonnetwork.web.CwtAuth;
import io.bosonnetwork.web.CwtAuthOptions;

/**
 * An in-memory {@link FederationContext}, populated imperatively: for tests and bring-up.
 * <p>
 * It knows the nodes it is given, each with an {@link Admission.Reason admission reason} - a node added
 * is {@link Admission.Reason#ACTIVE ACTIVE} until {@link #setAdmission(Id, Admission.Reason) told
 * otherwise} - the services of those nodes, and their descriptors. A node it doesn't know is refused
 * with {@link Admission.Reason#NO_RELATIONSHIP NO_RELATIONSHIP}, as on a node that federates only by
 * agreement; a peer it doesn't know is {@link Admission.Reason#UNKNOWN_PEER UNKNOWN_PEER}. Nothing it
 * answers may be cached ({@code maxAge} zero), and every change of admission is sent to the listeners.
 * The outcomes services report are kept, for tests to {@linkplain #getReports() read}. Thread-safe.
 */
public class StaticFederationContext implements FederationContext {
	private final Identity nodeIdentity;
	private final @Nullable Id servicePeerId;
	private final Map<Id, NodeEntry> nodes;
	private final Map<Id, FederatedNodeDescriptor> descriptors;
	private final List<Consumer<FederationEvent>> listeners;
	private final List<Report> reports;

	private static final Logger log = LoggerFactory.getLogger(StaticFederationContext.class);

	private record NodeEntry(Admission.Reason reason, List<FederatedService> services) {
		NodeEntry withReason(Admission.Reason r) {
			return new NodeEntry(r, services);
		}

		NodeEntry withServices(List<FederatedService> s) {
			return new NodeEntry(reason, List.copyOf(s));
		}
	}

	/**
	 * An outcome a service reported.
	 *
	 * @param nodeId  the other node
	 * @param peerId  the other node's service
	 * @param outcome the outcome
	 * @param detail  the detail, or {@code null}
	 */
	public record Report(Id nodeId, Id peerId, Outcome outcome, @Nullable String detail) {}

	/**
	 * Creates a context whose web authenticator accepts tokens for any audience.
	 *
	 * @param nodeIdentity the identity of the local node
	 */
	public StaticFederationContext(Identity nodeIdentity) {
		this(nodeIdentity, null);
	}

	/**
	 * Creates a context for one service, whose web authenticator accepts only tokens addressed to it.
	 *
	 * @param nodeIdentity  the identity of the local node
	 * @param servicePeerId the peer id of the service, or {@code null} to accept tokens for any audience
	 */
	public StaticFederationContext(Identity nodeIdentity, @Nullable Id servicePeerId) {
		this.nodeIdentity = Objects.requireNonNull(nodeIdentity, "nodeIdentity");
		this.servicePeerId = servicePeerId;
		this.nodes = new ConcurrentHashMap<>();
		this.descriptors = new ConcurrentHashMap<>();
		this.listeners = new CopyOnWriteArrayList<>();
		this.reports = new CopyOnWriteArrayList<>();
	}

	// ---- Population ----------------------------------------------------------------------------------

	/**
	 * Adds a node, {@link Admission.Reason#ACTIVE ACTIVE}. The host, port and API endpoint are checked and
	 * otherwise unused: where a node's services are reached is their own announcements.
	 *
	 * @param nodeId      the node's id
	 * @param host        the node's host
	 * @param port        the node's port, from 1 to 65535
	 * @param apiEndpoint the node's API endpoint, or {@code null}
	 * @return {@code true} if the node was added, {@code false} if it was already known
	 */
	public boolean addNode(Id nodeId, String host, int port, @Nullable String apiEndpoint) {
		Objects.requireNonNull(nodeId);
		Objects.requireNonNull(host);
		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port");

		Variable<Boolean> added = Variable.of(false);
		nodes.computeIfAbsent(nodeId, k -> {
			added.set(true);
			return new NodeEntry(Admission.Reason.ACTIVE, List.of());
		});

		if (added.get())
			fire(nodeId, Admission.Reason.ACTIVE);
		return added.get();
	}

	/**
	 * Adds a node, {@link Admission.Reason#ACTIVE ACTIVE}, with no API endpoint.
	 *
	 * @param nodeId the node's id
	 * @param host   the node's host
	 * @param port   the node's port, from 1 to 65535
	 * @return {@code true} if the node was added, {@code false} if it was already known
	 */
	public boolean addNode(Id nodeId, String host, int port) {
		return addNode(nodeId, host, port, null);
	}

	/**
	 * Sets a known node's admission, and tells the listeners if it changed.
	 *
	 * @param nodeId the node's id
	 * @param reason its new admission reason; not one about a peer
	 * @throws IllegalArgumentException if the node is not known, or the reason is about a peer
	 */
	public void setAdmission(Id nodeId, Admission.Reason reason) {
		Objects.requireNonNull(nodeId);
		Objects.requireNonNull(reason);
		if (reason == Admission.Reason.UNKNOWN_PEER || reason == Admission.Reason.UNVERIFIED_PEER)
			throw new IllegalArgumentException(reason + " is about a peer, not a node");

		Variable<Boolean> changed = Variable.of(false);
		NodeEntry entry = nodes.computeIfPresent(nodeId, (k, v) -> {
			if (v.reason == reason)
				return v;
			changed.set(true);
			return v.withReason(reason);
		});
		if (entry == null)
			throw new IllegalArgumentException("Node does not exist");

		if (changed.get())
			fire(nodeId, reason);
	}

	/**
	 * Tells whether a node is known.
	 *
	 * @param nodeId the node's id
	 * @return {@code true} if it is known
	 */
	public boolean existsNodeSync(Id nodeId) {
		Objects.requireNonNull(nodeId);
		return nodes.containsKey(nodeId);
	}

	/**
	 * Removes a node, its services and its descriptor, and tells the listeners that it is no longer
	 * admitted.
	 *
	 * @param nodeId the node's id
	 * @return {@code true} if the node was removed, {@code false} if it was not known
	 */
	public boolean removeNode(Id nodeId) {
		Objects.requireNonNull(nodeId);
		descriptors.remove(nodeId);
		boolean removed = nodes.remove(nodeId) != null;
		if (removed)
			fire(nodeId, Admission.Reason.NO_RELATIONSHIP);
		return removed;
	}

	/**
	 * Adds a service to a known node.
	 *
	 * @param service the service; its node must be known
	 * @return {@code true} if the service was added, {@code false} if the node already had a service with
	 *         that peer id and fingerprint
	 * @throws IllegalArgumentException if the node is not known
	 */
	public boolean addService(FederatedService service) {
		Objects.requireNonNull(service);
		Variable<Boolean> added = Variable.of(false);
		NodeEntry entry = nodes.computeIfPresent(service.nodeId(), (k, v) -> {
			boolean exists = v.services.stream().anyMatch(s ->
					s.peerId().equals(service.peerId()) && s.fingerprint() == service.fingerprint());
			if (exists)
				return v;

			List<FederatedService> services = new ArrayList<>(v.services);
			services.add(service);
			added.set(true);
			return v.withServices(services);
		});
		if (entry == null)
			throw new IllegalArgumentException("Node does not exist");

		return added.get();
	}

	/**
	 * Adds a service to a known node.
	 *
	 * @param nodeId      the node's id
	 * @param peerId      the service's peer id
	 * @param fingerprint the fingerprint of its announcement
	 * @param endpoint    its endpoint
	 * @param serviceType its type, or {@code null}
	 * @param serviceName its name, or {@code null}
	 * @return {@code true} if the service was added, {@code false} if the node already had a service with
	 *         that peer id and fingerprint
	 * @throws IllegalArgumentException if the node is not known
	 */
	public boolean addService(Id nodeId, Id peerId, long fingerprint, String endpoint,
							  @Nullable String serviceType, @Nullable String serviceName) {
		Objects.requireNonNull(nodeId);
		Objects.requireNonNull(peerId);
		return addService(new FederatedService(peerId, nodeId, serviceType, serviceName, endpoint, Map.of(),
				fingerprint, 0));
	}

	/**
	 * Adds a service, with no type or name, to a known node.
	 *
	 * @param nodeId      the node's id
	 * @param peerId      the service's peer id
	 * @param fingerprint the fingerprint of its announcement
	 * @param endpoint    its endpoint
	 * @return {@code true} if the service was added, {@code false} if the node already had a service with
	 *         that peer id and fingerprint
	 * @throws IllegalArgumentException if the node is not known
	 */
	public boolean addService(Id nodeId, Id peerId, long fingerprint, String endpoint) {
		return addService(nodeId, peerId, fingerprint, endpoint, null, null);
	}

	/**
	 * Returns a node's service with the given peer id and fingerprint.
	 *
	 * @param peerId      the service's peer id
	 * @param fingerprint the fingerprint of its announcement
	 * @param nodeId      the node's id
	 * @return an {@link Optional} with the service, or empty
	 */
	public Optional<FederatedService> getServiceSync(Id peerId, long fingerprint, Id nodeId) {
		Objects.requireNonNull(nodeId);
		Objects.requireNonNull(peerId);

		NodeEntry entry = nodes.get(nodeId);
		return entry == null ? Optional.empty() : entry.services.stream()
				.filter(s -> s.peerId().equals(peerId) && s.fingerprint() == fingerprint)
				.findFirst();
	}

	/**
	 * Tells whether a node has a service with the given peer id and fingerprint.
	 *
	 * @param peerId      the service's peer id
	 * @param fingerprint the fingerprint of its announcement
	 * @param nodeId      the node's id
	 * @return {@code true} if it has
	 */
	public boolean existsServiceSync(Id peerId, long fingerprint, Id nodeId) {
		return getServiceSync(peerId, fingerprint, nodeId).isPresent();
	}

	/**
	 * Returns a node's services with the given peer id.
	 *
	 * @param peerId the service's peer id
	 * @param nodeId the node's id
	 * @return the services, possibly empty
	 */
	public List<FederatedService> getServicesSync(Id peerId, Id nodeId) {
		Objects.requireNonNull(peerId);
		Objects.requireNonNull(nodeId);
		NodeEntry entry = nodes.get(nodeId);
		return entry == null ? List.of() : entry.services.stream()
				.filter(s -> s.peerId().equals(peerId))
				.collect(Collectors.toUnmodifiableList());
	}

	/**
	 * Tells whether a node has a service with the given peer id.
	 *
	 * @param peerId the service's peer id
	 * @param nodeId the node's id
	 * @return {@code true} if it has
	 */
	public boolean existsServiceSync(Id peerId, Id nodeId) {
		return !getServicesSync(peerId, nodeId).isEmpty();
	}

	/**
	 * Returns every known service with the given peer id, of any node.
	 *
	 * @param peerId the service's peer id
	 * @return the services, possibly empty
	 */
	public List<FederatedService> getServicesSync(Id peerId) {
		Objects.requireNonNull(peerId);
		return nodes.values().stream()
				.flatMap(e -> e.services.stream())
				.filter(s -> s.peerId().equals(peerId))
				.collect(Collectors.toUnmodifiableList());
	}

	/**
	 * Removes a node's service with the given peer id and fingerprint.
	 *
	 * @param peerId      the service's peer id
	 * @param fingerprint the fingerprint of its announcement
	 * @param nodeId      the node's id
	 * @return {@code true} if a service was removed
	 */
	public boolean removeService(Id peerId, long fingerprint, Id nodeId) {
		return removeServicesIf(nodeId, s -> s.peerId().equals(peerId) && s.fingerprint() == fingerprint);
	}

	/**
	 * Removes a node's services with the given peer id.
	 *
	 * @param peerId the service's peer id
	 * @param nodeId the node's id
	 * @return {@code true} if at least one service was removed
	 */
	public boolean removeServices(Id peerId, Id nodeId) {
		return removeServicesIf(nodeId, s -> s.peerId().equals(peerId));
	}

	private boolean removeServicesIf(Id nodeId, Predicate<FederatedService> filter) {
		Objects.requireNonNull(nodeId);
		Variable<Boolean> removed = Variable.of(false);
		nodes.computeIfPresent(nodeId, (k, v) -> {
			List<FederatedService> services = new ArrayList<>(v.services);
			if (!services.removeIf(filter))
				return v;

			removed.set(true);
			return v.withServices(services);
		});

		return removed.get();
	}

	/**
	 * Sets the descriptor of a node, known or not, replacing any it had.
	 *
	 * @param descriptor the node's descriptor
	 */
	public void putDescriptor(FederatedNodeDescriptor descriptor) {
		Objects.requireNonNull(descriptor);
		descriptors.put(descriptor.getNodeId(), descriptor);
	}

	/**
	 * Returns the outcomes services have reported, oldest first.
	 *
	 * @return an unmodifiable snapshot
	 */
	public List<Report> getReports() {
		return List.copyOf(reports);
	}

	// ---- FederationContext ---------------------------------------------------------------------------

	private static Admission admission(Admission.Reason reason) {
		return new Admission(reason, Duration.ZERO);
	}

	private Admission.Reason reasonOf(Id nodeId) {
		NodeEntry entry = nodes.get(nodeId);
		return entry == null ? Admission.Reason.NO_RELATIONSHIP : entry.reason;
	}

	private Resolution resolveSync(Id peerId) {
		// A peer belongs to one node; if it was registered under several, the newest announcement wins.
		Optional<FederatedService> service = nodes.values().stream()
				.flatMap(e -> e.services.stream())
				.filter(s -> s.peerId().equals(peerId))
				.max(Comparator.comparingInt(FederatedService::sequenceNumber)
						.thenComparingLong(FederatedService::fingerprint));

		return service.map(s -> new Resolution(s, admission(reasonOf(s.nodeId()))))
				.orElseGet(() -> new Resolution(null, admission(Admission.Reason.UNKNOWN_PEER)));
	}

	@Override
	public CompletableFuture<Resolution> resolve(Id peerId) {
		Objects.requireNonNull(peerId);
		return ContextualFuture.succeededFuture(resolveSync(peerId));
	}

	@Override
	public CompletableFuture<Admission> admit(Id nodeId) {
		Objects.requireNonNull(nodeId);
		return ContextualFuture.succeededFuture(admission(reasonOf(nodeId)));
	}

	@Override
	public CompletableFuture<Optional<FederatedNodeDescriptor>> describe(Id nodeId) {
		Objects.requireNonNull(nodeId);
		return ContextualFuture.succeededFuture(Optional.ofNullable(descriptors.get(nodeId)));
	}

	@Override
	public void report(Id nodeId, Id peerId, Outcome outcome, @Nullable String detail) {
		reports.add(new Report(nodeId, peerId, outcome, detail));
	}

	@Override
	public Subscription onChange(Consumer<FederationEvent> listener) {
		Objects.requireNonNull(listener);
		listeners.add(listener);
		return () -> listeners.remove(listener);
	}

	private void fire(Id nodeId, Admission.Reason reason) {
		FederationEvent event = new FederationEvent(nodeId, admission(reason));
		for (Consumer<FederationEvent> listener : listeners) {
			try {
				listener.accept(event);
			} catch (RuntimeException e) {
				log.error("Federation event listener failed on {}", event, e);
			}
		}
	}

	@Override
	public CwtAuth getWebAuthenticator() {
		CwtAuthOptions options = new CwtAuthOptions()
				.setIdentity(nodeIdentity)
				.setExpectedAudience(servicePeerId)
				.setClientProvider(new ClientProvider() {
					// A federation token always names the calling service as its client.
					@Override
					public Future<Optional<Principal>> getUser(Id nodeId) {
						return Future.succeededFuture(Optional.empty());
					}

					@Override
					public Future<Optional<Principal>> getClient(Id nodeId, Id peerId) {
						// The calling service must be the token subject's, and its node admitted: refused, as a
						// host refuses it, with 403.
						Resolution r = resolveSync(peerId);
						FederatedService service = r.service();
						if (service == null || !service.nodeId().equals(nodeId))
							return Future.succeededFuture(Optional.empty());
						if (!r.allowed())
							return Future.failedFuture(new HttpException(403, "Not admitted: " + r.admission().reason()));
						return Future.succeededFuture(Optional.of(service));
					}
				});

		return CwtAuth.create(options);
	}
}
