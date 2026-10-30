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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.vertx.core.Future;
import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.service.Admission;
import io.bosonnetwork.service.FederatedNodeDescriptor;
import io.bosonnetwork.service.FederatedService;
import io.bosonnetwork.service.FederationContext;
import io.bosonnetwork.service.FederationEvent;
import io.bosonnetwork.service.Principal;
import io.bosonnetwork.service.Resolution;
import io.bosonnetwork.vertx.ContextualFuture;
import io.bosonnetwork.web.ClientProvider;
import io.bosonnetwork.web.CwtAuth;
import io.bosonnetwork.web.CwtAuthOptions;

/**
 * A {@link FederationContext} that admits everything, for development, smoke tests and bring-up where
 * discovery is faked.
 * <p>
 * Every node is admitted with {@link Admission.Reason#OPEN OPEN}. Every peer resolves to a synthesized
 * service at a placeholder endpoint ({@value #PLACEHOLDER_ENDPOINT}, not an address); since no node is
 * known for it, its node is {@link Id#zero()}. The web authenticator accepts any well-formed federation
 * token, and its principal is a service of the token's subject. No descriptor is found, reports are
 * dropped, and no admission ever changes.
 */
public class AllowAllFederationContext implements FederationContext {
	/** The endpoint of every synthesized service: a placeholder, not an address. */
	public static final String PLACEHOLDER_ENDPOINT = "boson://localhost:65532";

	private static final Admission OPEN = new Admission(Admission.Reason.OPEN, Duration.ZERO);

	private final Identity nodeIdentity;

	/**
	 * Creates the context.
	 *
	 * @param nodeIdentity the identity of the local node, used by the web authenticator
	 */
	public AllowAllFederationContext(Identity nodeIdentity) {
		this.nodeIdentity = nodeIdentity;
	}

	private static FederatedService service(Id peerId, Id nodeId) {
		return new FederatedService(peerId, nodeId, null, null, PLACEHOLDER_ENDPOINT, Map.of(), 0, 0);
	}

	@Override
	public CompletableFuture<Resolution> resolve(Id peerId) {
		Objects.requireNonNull(peerId);
		return ContextualFuture.succeededFuture(new Resolution(service(peerId, Id.zero()), OPEN));
	}

	@Override
	public CompletableFuture<Admission> admit(Id nodeId) {
		Objects.requireNonNull(nodeId);
		return ContextualFuture.succeededFuture(OPEN);
	}

	@Override
	public CompletableFuture<Optional<FederatedNodeDescriptor>> describe(Id nodeId) {
		Objects.requireNonNull(nodeId);
		return ContextualFuture.succeededFuture(Optional.empty());
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
		// noinspection ConstantConditions
		if (nodeIdentity == null)
			throw new IllegalStateException("Node identity is not set");

		CwtAuthOptions options = new CwtAuthOptions()
				.setIdentity(nodeIdentity)
				.setClientProvider(new ClientProvider() {
					// A federation token always names the calling service as its client.
					@Override
					public Future<Optional<Principal>> getUser(Id nodeId) {
						return Future.succeededFuture(Optional.empty());
					}

					@Override
					public Future<Optional<Principal>> getClient(Id nodeId, Id peerId) {
						return Future.succeededFuture(Optional.of(service(peerId, nodeId)));
					}
				});

		return CwtAuth.create(options);
	}
}
