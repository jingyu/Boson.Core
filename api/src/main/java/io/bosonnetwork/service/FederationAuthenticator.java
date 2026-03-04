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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import io.bosonnetwork.Id;
import io.bosonnetwork.vertx.VertxFuture;

/**
 * Interface for authenticating nodes and peers within a federation.
 * <p>
 * Implementations of this interface provide mechanisms to verify the identity of other nodes
 * in the federation using cryptographic challenges and signatures.
 */
public interface FederationAuthenticator {
	/**
	 * Authenticates a node in the federation.
	 *
	 * @param nodeId    the unique identifier of the node to be authenticated
	 * @param nonce     the random challenge data (nonce) used for authentication
	 * @param signature the digital signature of the nonce, generated using the node's private key
	 * @return a {@link CompletableFuture} that completes with {@code true} if the node is successfully authenticated,
	 *         or {@code false} otherwise
	 */
	CompletableFuture<Boolean> authenticateNode(Id nodeId, byte[] nonce, byte[] signature);

	/**
	 * Authenticates a node in the federation.
	 *
	 * @param nodeId    the unique identifier of the node to be authenticated
	 * @return a {@link CompletableFuture} that completes with {@code true} if the node is successfully authenticated,
	 *         or {@code false} otherwise
	 */
	default CompletableFuture<Boolean> authenticateNode(Id nodeId) {
		return authenticateNode(nodeId, null, null);
	}

	/**
	 * Authenticates a peer associated with a node in the federation.
	 *
	 * @param nodeId    the unique identifier of the node managing the peer
	 * @param peerId    the unique identifier of the peer to be authenticated
	 * @param nonce     the random challenge data (nonce) used for authentication
	 * @param signature the digital signature of the nonce, generated using the peer's private key
	 * @return a {@link CompletableFuture} that completes with {@code true} if the peer is successfully authenticated,
	 *         or {@code false} otherwise
	 */
	CompletableFuture<Boolean> authenticatePeer(Id nodeId, Id peerId, byte[] nonce, byte[] signature);

	/**
	 * Authenticates a peer associated with a node in the federation.
	 *
	 * @param nodeId    the unique identifier of the node managing the peer
	 * @param peerId    the unique identifier of the peer to be authenticated
	 * @return a {@link CompletableFuture} that completes with {@code true} if the peer is successfully authenticated,
	 *         or {@code false} otherwise
	 */
	default CompletableFuture<Boolean> authenticatePeer(Id nodeId, Id peerId) {
		return authenticatePeer(nodeId, peerId, null, null);
	}

	/**
	 * Provides a FederationAuthenticator implementation that allows all authentication attempts.
	 * The returned authenticator verifies the provided signature against the corresponding
	 * signature key derived from the node or peer ID, enabling universal authentication
	 * acceptance when the signature is valid.
	 *
	 * @return a FederationAuthenticator instance that performs authentication by signature verification
	 */
	static FederationAuthenticator allowAll() {
		return new FederationAuthenticator() {
			@Override
			public CompletableFuture<Boolean> authenticateNode(Id nodeId, byte[] nonce, byte[] signature) {
				Objects.requireNonNull(nodeId, "nodeId");

				boolean valid = nonce == null || signature == null || nodeId.toSignatureKey().verify(nonce, signature);
				return VertxFuture.succeededFuture(valid);
			}

			@Override
			public CompletableFuture<Boolean> authenticatePeer(Id nodeId, Id peerId, byte[] nonce, byte[] signature) {
				Objects.requireNonNull(nodeId, "nodeId");
				Objects.requireNonNull(peerId, "peerId");

				boolean valid = nonce == null || signature == null || peerId.toSignatureKey().verify(nonce, signature);
				return VertxFuture.succeededFuture(valid);
			}
		};
	}

	/**
	 * Provides a FederationAuthenticator implementation that restricts successful
	 * authentication based on the supplied nodeServicesMap. The returned
	 * FederationAuthenticator validates that the provided node or peer ID is
	 * included in the nodeServicesMap and verifies the associated digital signature.
	 *
	 * @param nodeServicesMap a map where each key is a node ID, and each value is
	 *                        a list of peer IDs associated with that node. This
	 *                        map is used to determine whether a given node or
	 *                        peer is authorized for authentication.
	 * @return a FederationAuthenticator instance that authenticates nodes and peers
	 *         according to the provided nodeServicesMap and performs signature
	 *         verification.
	 */
	static FederationAuthenticator allow(Map<Id, List<Id>> nodeServicesMap) {
		Objects.requireNonNull(nodeServicesMap, "nodeServicesMap");

		return new FederationAuthenticator() {
			@Override
			public CompletableFuture<Boolean> authenticateNode(Id nodeId, byte[] nonce, byte[] signature) {
				Objects.requireNonNull(nodeId, "nodeId");

				if (!nodeServicesMap.containsKey(nodeId))
					return VertxFuture.succeededFuture(false);

				boolean valid = nonce == null || signature == null || nodeId.toSignatureKey().verify(nonce, signature);
				return VertxFuture.succeededFuture(valid);
			}

			@Override
			public CompletableFuture<Boolean> authenticatePeer(Id nodeId, Id peerId, byte[] nonce, byte[] signature) {
				Objects.requireNonNull(nodeId, "nodeId");
				Objects.requireNonNull(peerId, "peerId");

				if (!nodeServicesMap.containsKey(nodeId) || !nodeServicesMap.get(nodeId).contains(peerId))
					return VertxFuture.succeededFuture(false);

				boolean valid = nonce == null || signature == null || peerId.toSignatureKey().verify(nonce, signature);
				return VertxFuture.succeededFuture(valid);
			}
		};
	}
}