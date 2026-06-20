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

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import io.vertx.core.Future;
import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.service.ClientAuthenticator;
import io.bosonnetwork.service.ClientAuthorizer;
import io.bosonnetwork.service.ClientContext;
import io.bosonnetwork.service.ClientDevice;
import io.bosonnetwork.service.ClientUser;
import io.bosonnetwork.service.Principal;
import io.bosonnetwork.vertx.ContextualFuture;
import io.bosonnetwork.web.ClientProvider;
import io.bosonnetwork.web.CwtAuth;
import io.bosonnetwork.web.CwtAuthOptions;

/**
 * Implementation of the {@link ClientContext} interface that allows all operations without restrictions.
 * This context is permissive, always returning successful results for authentication, authorization,
 * and existence checks. It is intended for use cases where no stringent access control or validation
 * is required.
 * <p>
 * This implementation supports the following behaviors:
 * - All user and device existence checks return {@code true}.
 * - All authentication requests are permitted as valid.
 * - Authorization requests provide empty grants.
 * - Retrieval of users or devices always succeeds with placeholder objects or empty lists, as appropriate.
 * - Provides a permissive {@link CwtAuth} instance for token handling.
 * <p>
 * Note: This class should be used cautiously, as it bypasses all security and validation checks.
 */
public class AllowAllClientContext implements ClientContext {
	private final Identity nodeIdentity;

	/**
	 * Constructs an instance of {@code AllowAllClientContext} with the provided node identity.
	 *
	 * @param nodeIdentity the {@link Identity} associated with this client context.
	 *                     This identity represents the cryptographic entity
	 *                     used within the context, enabling signing, verification,
	 *                     encryption, and decryption operations.
	 */
	public AllowAllClientContext(Identity nodeIdentity) {
		this.nodeIdentity = nodeIdentity;
	}

	@Override
	public CompletableFuture<Optional<ClientUser>> getUser(Id userId) {
		return ContextualFuture.succeededFuture(Optional.of(new PlainUser(userId)));
	}

	@Override
	public CompletableFuture<Boolean> existsUser(Id userId) {
		return ContextualFuture.succeededFuture(true);
	}

	@Override
	public CompletableFuture<Optional<ClientDevice>> getDevice(Id userId, Id deviceId) {
		return ContextualFuture.succeededFuture(Optional.of(new PlainDevice(deviceId, userId)));
	}

	@Override
	public CompletableFuture<Boolean> existsDevice(Id userId, Id deviceId) {
		return ContextualFuture.succeededFuture(true);
	}

	@Override
	public ClientAuthenticator getAuthenticator() {
		return new ClientAuthenticator() {
			@Override
			public CompletableFuture<Boolean> authenticateUser(Id userId, byte @Nullable [] nonce, byte @Nullable [] signature) {
				boolean isValid = (nonce == null && signature == null) ||
						(nonce != null && signature != null && userId.toSignatureKey().verify(nonce, signature));
				return CompletableFuture.completedFuture(isValid);
			}

			@Override
			public CompletableFuture<Boolean> authenticateDevice(Id userId, Id deviceId, byte @Nullable [] nonce, byte @Nullable [] signature, String address) {
				boolean isValid = (nonce == null && signature == null) ||
						(nonce != null && signature != null && deviceId.toSignatureKey().verify(nonce, signature));
				return CompletableFuture.completedFuture(isValid);
			}
		};
	}

	@Override
	public ClientAuthorizer getAuthorizer() {
		return (userId, deviceId, serviceType) -> ContextualFuture.succeededFuture(Map.of());
	}

	@Override
	public CwtAuth getWebAuthenticator() {
		// noinspection ConstantConditions
		if (nodeIdentity == null)
			throw new IllegalStateException("Node identity is not set");

		CwtAuthOptions options = new CwtAuthOptions()
				.setIdentity(nodeIdentity)
				.setClientProvider(new ClientProvider() {
					@Override
					public Future<Optional<Principal>> getUser(Id userId) {
						return Future.succeededFuture(Optional.of(new PlainUser(userId)));
					}

					@Override
					public Future<Optional<Principal>> getClient(Id userId, Id clientId) {
						return Future.succeededFuture(Optional.of(new PlainDevice(clientId, userId)));
					}
				});

		return CwtAuth.create(options);
	}
}