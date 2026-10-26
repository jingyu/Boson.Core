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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.vertx.core.Future;
import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.LookupOption;
import io.bosonnetwork.Node;
import io.bosonnetwork.service.Admission;
import io.bosonnetwork.service.FederatedService;
import io.bosonnetwork.service.FederationContext;
import io.bosonnetwork.service.FederationEvent;
import io.bosonnetwork.service.NodeDescriptor;
import io.bosonnetwork.service.Principal;
import io.bosonnetwork.service.Resolution;
import io.bosonnetwork.web.ClientProvider;
import io.bosonnetwork.web.CwtAuth;
import io.bosonnetwork.web.CwtAuthOptions;

/**
 * A {@link FederationContext} that federates openly through a node's DHT, without a host to decide:
 * for tests, development, and services run without a Director.
 * <p>
 * A peer resolves to the service its announcements in the DHT show - only announcements that carry a
 * node's attestation count ({@link FederatedService#fromAnnouncements}) - and every node is admitted
 * with {@link Admission.Reason#OPEN OPEN}. No descriptor is found, reports are dropped, and no admission
 * ever changes. Nothing is cached: each resolution is a DHT lookup.
 */
public class OpenFederationContext implements FederationContext {
	// How many announcements of one peer to collect: enough to see a stale one beside the current one.
	private static final int PEER_LOOKUP_COUNT = 4;
	private static final Admission OPEN = new Admission(Admission.Reason.OPEN, Duration.ZERO);

	private final Node node;
	private final @Nullable Id servicePeerId;

	/**
	 * Creates the context.
	 *
	 * @param node          the local node, whose DHT is searched and whose identity signs nothing here
	 * @param servicePeerId the peer id of the service the context is for: the audience of the tokens its
	 *                      web authenticator accepts, or {@code null} to accept tokens for any audience
	 */
	public OpenFederationContext(Node node, @Nullable Id servicePeerId) {
		this.node = Objects.requireNonNull(node, "node");
		this.servicePeerId = servicePeerId;
	}

	@Override
	public CompletableFuture<Resolution> resolve(Id peerId) {
		Objects.requireNonNull(peerId);
		return node.findPeer(peerId, -1, PEER_LOOKUP_COUNT, LookupOption.ARBITRARY)
				.exceptionally(e -> List.of())
				.thenApply(announcements -> FederatedService.fromAnnouncements(announcements)
						.map(service -> new Resolution(service, OPEN))
						.orElseGet(() -> new Resolution(null, new Admission(announcements.isEmpty() ?
								Admission.Reason.UNKNOWN_PEER : Admission.Reason.UNVERIFIED_PEER, Duration.ZERO))));
	}

	@Override
	public CompletableFuture<Admission> admit(Id nodeId) {
		Objects.requireNonNull(nodeId);
		return CompletableFuture.completedFuture(OPEN);
	}

	@Override
	public CompletableFuture<Optional<NodeDescriptor>> describe(Id nodeId) {
		Objects.requireNonNull(nodeId);
		return CompletableFuture.completedFuture(Optional.empty());
	}

	@Override
	public void report(Id nodeId, Id peerId, Outcome outcome, @Nullable String detail) {
		// dropped
	}

	@Override
	public Subscription onChange(Consumer<FederationEvent> listener) {
		Objects.requireNonNull(listener);
		// No admission ever changes.
		return () -> {};
	}

	@Override
	public CwtAuth getWebAuthenticator() {
		return CwtAuth.create(new CwtAuthOptions()
				.setIdentity(node)
				.setExpectedAudience(servicePeerId)
				.setClientProvider(new ClientProvider() {
					// A federation token always names the calling service as its client.
					@Override
					public Future<Optional<Principal>> getUser(Id userId) {
						return Future.succeededFuture(Optional.empty());
					}

					@Override
					public Future<Optional<Principal>> getClient(Id nodeId, Id peerId) {
						return Future.fromCompletionStage(resolve(peerId)).map(r -> {
							FederatedService service = r.service();
							if (service == null || !service.nodeId().equals(nodeId))
								return Optional.empty();
							return Optional.of(service);
						});
					}
				}));
	}
}
