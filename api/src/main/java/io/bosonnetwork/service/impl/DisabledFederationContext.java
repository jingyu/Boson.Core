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
import java.util.concurrent.CompletableFuture;

import io.vertx.core.Future;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.service.FederatedNode;
import io.bosonnetwork.service.FederationAuthenticator;
import io.bosonnetwork.service.FederationContext;
import io.bosonnetwork.service.ServiceInfo;
import io.bosonnetwork.vertx.VertxFuture;
import io.bosonnetwork.web.CompactWebTokenAuth;

/**
 * A no-op implementation of the {@link FederationContext} interface, intended to be used
 * in scenarios where federation functionalities are disabled or not required. This
 * implementation returns default, non-functional values for all operations.
 * <p>
 * All methods in this class are implemented to return default responses such as
 * - Null values for objects
 * - False for boolean results
 * - Empty lists for collections
 * <p>
 * This class is useful when a FederationContext is required by the system, but federation
 * interactions are explicitly disabled or not supported.
 */
public class DisabledFederationContext implements FederationContext {
	@Override
	public CompletableFuture<FederatedNode> getNode(Id nodeId, boolean federateIfNotExists) {
		return VertxFuture.succeededFuture(null);
	}

	@Override
	public CompletableFuture<Boolean> existsNode(Id nodeId) {
		return VertxFuture.succeededFuture(false);
	}

	@Override
	public CompletableFuture<List<ServiceInfo>> getServices(Id peerId, Id nodeId) {
		return VertxFuture.succeededFuture(List.of());
	}

	@Override
	public CompletableFuture<List<ServiceInfo>> getServices(Id peerId) {
		throw new UnsupportedOperationException("getServices is not supported");
		// return VertxFuture.succeededFuture(List.of());
	}

	@Override
	public CompletableFuture<Void> reportIncident(Id nodeId, Id peerId, IncidentType incident, String details) {
		return VertxFuture.succeededFuture();
	}

	@Override
	public FederationAuthenticator getAuthenticator() {
		return new FederationAuthenticator() {
			@Override
			public CompletableFuture<Boolean> authenticateNode(Id nodeId, byte[] nonce, byte[] signature) {
				return VertxFuture.succeededFuture(false);
			}

			@Override
			public CompletableFuture<Boolean> authenticatePeer(Id nodeId, Id peerId, byte[] nonce, byte[] signature) {
				return VertxFuture.succeededFuture(false);
			}
		};
	}

	@Override
	public CompactWebTokenAuth getWebTokenAuthenticator() {
		return CompactWebTokenAuth.create(new CryptoIdentity(), new CompactWebTokenAuth.UserRepository() {
			@Override
			public Future<?> getSubject(Id subject) {
				return Future.succeededFuture();
			}

			@Override
			public Future<?> getAssociated(Id subject, Id associated) {
				return Future.succeededFuture();
			}
		});
	}
}