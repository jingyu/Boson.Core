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
 * Implementations provide mechanisms to verify the identity of users and devices
 * typically using cryptographic challenges and signatures (e.g., Ed25519).
 */
public interface ClientAuthenticator {

	/**
	 * Authenticates a user based on their ID and a cryptographic signature.
	 *
	 * @param userId    the unique identifier of the user
	 * @param nonce     the random challenge data (nonce) used for authentication
	 * @param signature the digital signature of the nonce, generated using the user's private key
	 * @return a {@link CompletableFuture} that completes with {@code true} if the user is successfully authenticated,
	 *         or {@code false} otherwise
	 */
	CompletableFuture<Boolean> authenticateUser(Id userId, byte[] nonce, byte[] signature);

	/**
	 * Authenticates a user based on their unique identifier.
	 *
	 * @param userId the unique identifier of the user to authenticate
	 * @return a {@link CompletableFuture} that completes with {@code true} if the user
	 *         is successfully authenticated, or {@code false} otherwise
	 */
	default CompletableFuture<Boolean> authenticateUser(Id userId) {
		return authenticateUser(userId, null, null);
	}

	/**
	 * Authenticates a specific device belonging to a user.
	 *
	 * @param userId    the unique identifier of the user who owns the device
	 * @param deviceId  the unique identifier of the device attempting to authenticate
	 * @param nonce     the random challenge data (nonce) used for authentication
	 * @param signature the digital signature of the nonce, generated using the device's private key
	 * @param address   the network address (e.g., IP address) from which the device is connecting
	 * @return a {@link CompletableFuture} that completes with {@code true} if the device is successfully authenticated,
	 *         or {@code false} otherwise
	 */
	CompletableFuture<Boolean> authenticateDevice(Id userId, Id deviceId, byte[] nonce, byte[] signature, String address);

	/**
	 * Authenticates a specific device belonging to a user using the provided user ID, device ID,
	 * and network address.
	 *
	 * @param userId   the unique identifier of the user who owns the device
	 * @param deviceId the unique identifier of the device attempting to authenticate
	 * @param address  the network address (e.g., IP address) from which the device is connecting
	 * @return a {@link CompletableFuture} that completes with {@code true} if the device is successfully
	 *         authenticated, or {@code false} otherwise
	 */
	default CompletableFuture<Boolean> authenticateDevice(Id userId, Id deviceId, String address) {
		return authenticateDevice(userId, deviceId, null, null, address);
	}
}