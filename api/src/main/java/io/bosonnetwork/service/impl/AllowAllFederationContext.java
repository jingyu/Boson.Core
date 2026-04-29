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
import io.bosonnetwork.Identity;
import io.bosonnetwork.service.FederatedNode;
import io.bosonnetwork.service.FederationAuthenticator;
import io.bosonnetwork.service.FederationContext;
import io.bosonnetwork.service.ServiceInfo;
import io.bosonnetwork.vertx.VertxFuture;
import io.bosonnetwork.web.CompactWebTokenAuth;

/**
 * An implementation of the FederationContext interface that allows all federated interactions
 * without any restrictions. This context is permissive and assumes that all nodes, services,
 * and peers are valid and accessible.
 * <p>
 * This class is primarily used in scenarios where no sophisticated authorization or validation
 * logic is required, and the environment is trusted or mock-based.
 */
public class AllowAllFederationContext implements FederationContext {
	private final Identity nodeIdentity;

	/**
	 * Constructs an instance of the {@code AllowAllFederationContext} class using the specified node identity.
	 * This implementation assumes a permissive federation context without any restrictions on nodes,
	 * services, or interactions.
	 *
	 * @param nodeIdentity the {@link Identity} of the node for which this federation context is created;
	 *                     used for authentication and identifying the node in the federation.
	 */
	public AllowAllFederationContext(Identity nodeIdentity) {
		this.nodeIdentity = nodeIdentity;
	}

	private FederatedNode _getNode(Id nodeId) {
		return new PlainFederatedNode(nodeId, "localhost", 65535);
	}

	private ServiceInfo _getService(Id peerId, Id nodeId) {
		return new PlainServiceInfo(peerId, 0, nodeId, "boson://localhost:65532");
	}

	@Override
	public CompletableFuture<FederatedNode> getNode(Id nodeId, boolean tryFederateIfNotExists) {
		return VertxFuture.succeededFuture(_getNode(nodeId));
	}

	@Override
	public CompletableFuture<Boolean> existsNode(Id nodeId) {
		return VertxFuture.succeededFuture(true);
	}

	@Override
	public CompletableFuture<List<ServiceInfo>> getServices(Id peerId, Id nodeId) {
		return VertxFuture.succeededFuture(List.of(_getService(peerId, nodeId)));
	}

	@Override
	public CompletableFuture<List<ServiceInfo>> getServices(Id peerId, boolean tryFederateIfNotExists) {
		return VertxFuture.succeededFuture(List.of());
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
				boolean valid = nonce == null || signature == null || nodeId.toSignatureKey().verify(nonce, signature);
				return VertxFuture.succeededFuture(valid);
			}

			@Override
			public CompletableFuture<Boolean> authenticatePeer(Id nodeId, Id peerId, byte[] nonce, byte[] signature) {
				boolean valid = nonce == null || signature == null || peerId.toSignatureKey().verify(nonce, signature);
				return VertxFuture.succeededFuture(valid);
			}
		};
	}

	@Override
	public CompactWebTokenAuth getWebTokenAuthenticator() {
		if (nodeIdentity == null)
			throw new IllegalStateException("Node identity is not set");

		return CompactWebTokenAuth.create(nodeIdentity, new CompactWebTokenAuth.UserRepository() {
			@Override
			public Future<FederatedNode> getSubject(Id subject) {
				return Future.succeededFuture(_getNode(subject));
			}

			@Override
			public Future<ServiceInfo> getAssociated(Id subject, Id associated) {
				return Future.succeededFuture(_getService(associated, subject));
			}
		});
	}
}