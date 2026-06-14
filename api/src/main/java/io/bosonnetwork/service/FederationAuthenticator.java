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

import java.util.concurrent.CompletableFuture;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;

/**
 * Interface for authenticating nodes and peers within a federation.
 * <p>
 * Implementations verify the identity of other nodes in the federation using cryptographic
 * challenges and signatures.
 * <p>
 * <strong>Nonce/signature contract.</strong> The {@code (id, nonce, signature)} overloads accept
 * three argument shapes:
 * <ul>
 *   <li>Both {@code nonce} and {@code signature} non-null - the implementation MUST verify the
 *       signature against the nonce using the id's signing key, and return the verification result.</li>
 *   <li>Both {@code nonce} and {@code signature} null - "pre-authenticated" mode: the caller has
 *       already verified the identity out of band (typically at the transport layer) and is asking
 *       only whether the id is admissible. The implementation MUST NOT treat the absence of a
 *       signature as a failure; it should apply its admission policy (membership, allow-list, etc.)
 *       and return that. The no-nonce default overloads delegate to this mode.</li>
 *   <li>Exactly one of {@code nonce} / {@code signature} is null - caller bug; the implementation
 *       MUST return {@code false}.</li>
 * </ul>
 */
public interface FederationAuthenticator {
	/**
	 * Authenticates a node in the federation. See the
	 * {@linkplain FederationAuthenticator interface Javadoc} for the nonce/signature contract.
	 *
	 * @param nodeId    the unique identifier of the node to be authenticated
	 * @param nonce     the challenge data, or {@code null} for pre-authenticated mode
	 * @param signature the signature over {@code nonce}, or {@code null} for pre-authenticated mode
	 * @return a {@link CompletableFuture} that completes with {@code true} if the node is admitted,
	 *         or {@code false} otherwise
	 */
	CompletableFuture<Boolean> authenticateNode(Id nodeId, byte @Nullable [] nonce, byte @Nullable [] signature);

	/**
	 * Convenience for pre-authenticated mode - equivalent to
	 * {@link #authenticateNode(Id, byte[], byte[]) authenticateNode(nodeId, null, null)}.
	 *
	 * @param nodeId the unique identifier of the node to be authenticated
	 * @return a {@link CompletableFuture} that completes with {@code true} if the node is admitted,
	 *         or {@code false} otherwise
	 */
	default CompletableFuture<Boolean> authenticateNode(Id nodeId) {
		return authenticateNode(nodeId, null, null);
	}

	/**
	 * Authenticates a peer associated with a node in the federation. See the
	 * {@linkplain FederationAuthenticator interface Javadoc} for the nonce/signature contract.
	 *
	 * @param nodeId    the unique identifier of the node managing the peer
	 * @param peerId    the unique identifier of the peer to be authenticated
	 * @param nonce     the challenge data, or {@code null} for pre-authenticated mode
	 * @param signature the signature over {@code nonce}, or {@code null} for pre-authenticated mode
	 * @return a {@link CompletableFuture} that completes with {@code true} if the peer is admitted,
	 *         or {@code false} otherwise
	 */
	CompletableFuture<Boolean> authenticatePeer(Id nodeId, Id peerId, byte @Nullable [] nonce, byte @Nullable [] signature);

	/**
	 * Convenience for pre-authenticated mode - equivalent to
	 * {@link #authenticatePeer(Id, Id, byte[], byte[]) authenticatePeer(nodeId, peerId, null, null)}.
	 *
	 * @param nodeId the unique identifier of the node managing the peer
	 * @param peerId the unique identifier of the peer to be authenticated
	 * @return a {@link CompletableFuture} that completes with {@code true} if the peer is admitted,
	 *         or {@code false} otherwise
	 */
	default CompletableFuture<Boolean> authenticatePeer(Id nodeId, Id peerId) {
		return authenticatePeer(nodeId, peerId, null, null);
	}
}