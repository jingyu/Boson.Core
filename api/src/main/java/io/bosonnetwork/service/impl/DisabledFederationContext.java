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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import io.vertx.core.Future;
import io.vertx.ext.web.handler.HttpException;
import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.service.Admission;
import io.bosonnetwork.service.FederatedNodeDescriptor;
import io.bosonnetwork.service.FederationContext;
import io.bosonnetwork.service.FederationEvent;
import io.bosonnetwork.service.Principal;
import io.bosonnetwork.service.Resolution;
import io.bosonnetwork.vertx.ContextualFuture;
import io.bosonnetwork.web.ClientProvider;
import io.bosonnetwork.web.CwtAuth;
import io.bosonnetwork.web.CwtAuthOptions;

/**
 * The {@link FederationContext} of a host where federation is turned off.
 * <p>
 * Every peer and every node is refused with {@link Admission.Reason#DISABLED DISABLED}, no descriptor is
 * found, reports are dropped, no admission ever changes, and the web authenticator accepts no token.
 */
public class DisabledFederationContext implements FederationContext {
	// Turning federation on is a restart, so a refusal holds for as long as the caller likes.
	private static final Admission DISABLED = new Admission(Admission.Reason.DISABLED, Duration.ofDays(1));

	@Override
	public CompletableFuture<Resolution> resolve(Id peerId) {
		Objects.requireNonNull(peerId);
		return ContextualFuture.succeededFuture(new Resolution(null, DISABLED));
	}

	@Override
	public CompletableFuture<Resolution> resolve(Id nodeId, String serviceType) {
		Objects.requireNonNull(nodeId);
		Objects.requireNonNull(serviceType);
		return ContextualFuture.succeededFuture(new Resolution(null, DISABLED));
	}

	@Override
	public CompletableFuture<Admission> admit(Id nodeId) {
		Objects.requireNonNull(nodeId);
		return ContextualFuture.succeededFuture(DISABLED);
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
		CwtAuthOptions options = new CwtAuthOptions()
				.setIdentity(new CryptoIdentity())
				.setClientProvider(new ClientProvider() {
					@Override
					public Future<Optional<Principal>> getUser(Id nodeId) {
						return Future.succeededFuture(Optional.empty());
					}

					@Override
					public Future<Optional<Principal>> getClient(Id nodeId, Id peerId) {
						// Every node is refused, as a host refuses a node it doesn't admit.
						return Future.failedFuture(new HttpException(403, "Federation is disabled on this node"));
					}
				});

		return CwtAuth.create(options);
	}
}
