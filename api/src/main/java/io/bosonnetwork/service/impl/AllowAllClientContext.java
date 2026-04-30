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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.vertx.core.Future;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.service.ClientAuthenticator;
import io.bosonnetwork.service.ClientAuthorizer;
import io.bosonnetwork.service.ClientContext;
import io.bosonnetwork.service.ClientDevice;
import io.bosonnetwork.service.ClientUser;
import io.bosonnetwork.vertx.VertxFuture;
import io.bosonnetwork.web.CompactWebTokenAuth;

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
 * - Provides a permissive {@link CompactWebTokenAuth} instance for token handling.
 * <p>
 * Note: This class should be used cautiously, as it bypasses all security and validation checks.
 */
public class AllowAllClientContext implements ClientContext {
	private final Identity nodeIdentity;

	/**
	 * Constructs an instance of {@code AllowAllClientContext} with the provided node identity.
	 *
	 * @param nodeIdentity the {@link Identity} associated with this client context.
	 *                      This identity represents the cryptographic entity
	 *                      used within the context, enabling signing, verification,
	 *                      encryption, and decryption operations.
	 */
	public AllowAllClientContext(Identity nodeIdentity) {
		this.nodeIdentity = nodeIdentity;
	}

	@Override
	public CompletableFuture<ClientUser> getUser(Id userId) {
		return VertxFuture.succeededFuture(new PlainUser(userId));
	}

	@Override
	public CompletableFuture<Boolean> existsUser(Id userId) {
		return VertxFuture.succeededFuture(true);
	}

	@Override
	public CompletableFuture<List<ClientDevice>> getDevices(Id userId) {
		throw new UnsupportedOperationException("getDevices is not supported");
		// return VertxFuture.succeededFuture(List.of());
	}

	@Override
	public CompletableFuture<ClientDevice> getDevice(Id deviceId) {
		throw new UnsupportedOperationException("getDevice is not supported");
		// return VertxFuture.succeededFuture();
	}

	@Override
	public CompletableFuture<Boolean> existsDevice(Id deviceId) {
		throw new UnsupportedOperationException("existsDevice is not supported");
		//return VertxFuture.succeededFuture(true);
	}

	@Override
	public CompletableFuture<Boolean> existsDevice(Id userId, Id deviceId) {
		return VertxFuture.succeededFuture(true);
	}

	@Override
	public ClientAuthenticator getAuthenticator() {
		return new ClientAuthenticator() {
			@Override
			public CompletableFuture<Boolean> authenticateUser(Id userId, byte[] nonce, byte[] signature) {
				boolean isValid = nonce == null || signature == null || userId.toSignatureKey().verify(nonce, signature);
				return CompletableFuture.completedFuture(isValid);
			}

			@Override
			public CompletableFuture<Boolean> authenticateDevice(Id userId, Id deviceId, byte[] nonce, byte[] signature, String address) {
				boolean isValid = nonce == null || signature == null || deviceId.toSignatureKey().verify(nonce, signature);
				return CompletableFuture.completedFuture(isValid);
			}
		};
	}

	@Override
	public ClientAuthorizer getAuthorizer() {
		return (userId, deviceId, serviceType) -> VertxFuture.succeededFuture(Map.of());
	}

	@Override
	public CompactWebTokenAuth getWebTokenAuthenticator() {
		if (nodeIdentity == null)
			throw new IllegalStateException("Node identity is not set");

		return CompactWebTokenAuth.create(nodeIdentity, new CompactWebTokenAuth.UserRepository() {
			@Override
			public Future<ClientUser> getSubject(Id subject) {
				return Future.succeededFuture(new PlainUser(subject));
			}

			@Override
			public Future<?> getAssociated(Id subject, Id associated) {
				return Future.succeededFuture(new PlainDevice(associated, subject));
			}
		});
	}
}