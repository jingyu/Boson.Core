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
import java.util.concurrent.CompletableFuture;

import io.bosonnetwork.Id;

/**
 * Interface for authenticating clients (users and devices).
 * <p>
 * Implementations of this interface provide mechanisms to verify the identity of users and devices
 * using cryptographic signatures and other credentials.
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
	 * Returns a `ClientAuthenticator` instance that allows all authentication attempts.
	 * The returned authenticator verifies the provided signature against the corresponding
	 * signature key derived from the user or device ID, enabling universal authentication
	 * acceptance when the signature is valid.
	 *
	 * @return a `ClientAuthenticator` instance that performs signature validation for user
	 *         and device authentication and always allows access if the signature is valid
	 */
	static ClientAuthenticator allowAll() {
		return new ClientAuthenticator() {
			@Override
			public CompletableFuture<Boolean> authenticateUser(Id userId, byte[] nonce, byte[] signature) {
				return userId.toSignatureKey().verify(nonce, signature) ?
						CompletableFuture.completedFuture(true) :
						CompletableFuture.completedFuture(false);
			}

			@Override
			public CompletableFuture<Boolean> authenticateDevice(Id userId, Id deviceId, byte[] nonce, byte[] signature, String address) {
				return deviceId.toSignatureKey().verify(nonce, signature) ?
						CompletableFuture.completedFuture(true) :
						CompletableFuture.completedFuture(false);
			}
		};
	}

	/**
	 * Creates a `ClientAuthenticator` instance that validates authentication
	 * attempts based on a provided mapping of users and their associated devices.
	 * The returned authenticator verifies the provided signature and ensures
	 * that the user ID and device ID exist in the given map, granting access
	 * if all checks are satisfied.
	 *
	 * @param userDeviceMap a mapping where the keys represent user IDs and the values
	 *                      are lists of device IDs associated with each user
	 * @return a `ClientAuthenticator` instance that performs authentication based
	 *         on the provided user-device mapping and cryptographic signature verification
	 */
	static ClientAuthenticator allow(Map<Id, List<Id>> userDeviceMap) {
		return new ClientAuthenticator() {
			@Override
			public CompletableFuture<Boolean> authenticateUser(Id userId, byte[] nonce, byte[] signature) {
				if (!userDeviceMap.containsKey(userId))
					return CompletableFuture.completedFuture(false);

				return userId.toSignatureKey().verify(nonce, signature) ?
						CompletableFuture.completedFuture(true) :
						CompletableFuture.completedFuture(false);
			}

			@Override
			public CompletableFuture<Boolean> authenticateDevice(Id userId, Id deviceId, byte[] nonce, byte[] signature, String address) {
				if (!userDeviceMap.containsKey(userId) || !userDeviceMap.get(userId).contains(deviceId))
					return CompletableFuture.completedFuture(false);

				return deviceId.toSignatureKey().verify(nonce, signature) ?
						CompletableFuture.completedFuture(true) :
						CompletableFuture.completedFuture(false);
			}
		};
	}
}