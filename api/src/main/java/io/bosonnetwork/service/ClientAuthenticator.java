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

import io.bosonnetwork.Id;

/**
 * Interface for authenticating clients (users and devices) within the Boson network.
 * <p>
 * Implementations verify the identity of users and devices, typically using cryptographic
 * challenges and signatures (e.g., Ed25519).
 * <p>
 * <strong>Nonce/signature contract.</strong> The overloads that accept {@code (nonce, signature)}
 * support three call shapes:
 * <ul>
 *   <li>Both {@code nonce} and {@code signature} non-null — the implementation MUST verify the
 *       signature against the nonce using the id's signing key, and return the verification result.</li>
 *   <li>Both {@code nonce} and {@code signature} null — "pre-authenticated" mode: the caller has
 *       already verified the identity out of band (typically at the transport layer) and is asking
 *       only whether the id is admissible. The implementation MUST NOT treat the absence of a
 *       signature as a failure; it should apply its admission policy and return that. The no-nonce
 *       default overloads delegate to this mode.</li>
 *   <li>Exactly one of {@code nonce} / {@code signature} is null — caller bug; the implementation
 *       MUST return {@code false}.</li>
 * </ul>
 */
public interface ClientAuthenticator {

	/**
	 * Authenticates a user. See the {@linkplain ClientAuthenticator interface Javadoc} for the
	 * nonce/signature contract.
	 *
	 * @param userId    the unique identifier of the user
	 * @param nonce     the challenge data, or {@code null} for pre-authenticated mode
	 * @param signature the signature over {@code nonce}, or {@code null} for pre-authenticated mode
	 * @return a {@link CompletableFuture} that completes with {@code true} if the user is admitted,
	 *         or {@code false} otherwise
	 */
	CompletableFuture<Boolean> authenticateUser(Id userId, byte[] nonce, byte[] signature);

	/**
	 * Convenience for pre-authenticated mode — equivalent to
	 * {@link #authenticateUser(Id, byte[], byte[]) authenticateUser(userId, null, null)}.
	 *
	 * @param userId the unique identifier of the user to authenticate
	 * @return a {@link CompletableFuture} that completes with {@code true} if the user is admitted,
	 *         or {@code false} otherwise
	 */
	default CompletableFuture<Boolean> authenticateUser(Id userId) {
		return authenticateUser(userId, null, null);
	}

	/**
	 * Authenticates a specific device belonging to a user. See the
	 * {@linkplain ClientAuthenticator interface Javadoc} for the nonce/signature contract.
	 *
	 * @param userId    the unique identifier of the user who owns the device
	 * @param deviceId  the unique identifier of the device attempting to authenticate
	 * @param nonce     the challenge data, or {@code null} for pre-authenticated mode
	 * @param signature the signature over {@code nonce}, or {@code null} for pre-authenticated mode
	 * @param address   the network address (e.g., IP address) from which the device is connecting
	 * @return a {@link CompletableFuture} that completes with {@code true} if the device is admitted,
	 *         or {@code false} otherwise
	 */
	CompletableFuture<Boolean> authenticateDevice(Id userId, Id deviceId, byte[] nonce, byte[] signature, String address);

	/**
	 * Convenience for pre-authenticated mode — equivalent to
	 * {@link #authenticateDevice(Id, Id, byte[], byte[], String) authenticateDevice(userId, deviceId, null, null, address)}.
	 *
	 * @param userId   the unique identifier of the user who owns the device
	 * @param deviceId the unique identifier of the device attempting to authenticate
	 * @param address  the network address (e.g., IP address) from which the device is connecting
	 * @return a {@link CompletableFuture} that completes with {@code true} if the device is admitted,
	 *         or {@code false} otherwise
	 */
	default CompletableFuture<Boolean> authenticateDevice(Id userId, Id deviceId, String address) {
		return authenticateDevice(userId, deviceId, null, null, address);
	}
}