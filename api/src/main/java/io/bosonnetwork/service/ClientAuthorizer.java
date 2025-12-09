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

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.bosonnetwork.Id;

/**
 * Interface for authorizing client requests to access specific services.
 * <p>
 * Implementations of this interface define the logic to determine if a user on a specific device
 * is granted permission to use the requested service.
 */
public interface ClientAuthorizer {
	/**
	 * Authorizes the specified user and device for the given service.
	 *
	 * @param userId    the unique identifier of the user requesting access
	 * @param deviceId  the unique identifier of the device used for the request
	 * @param serviceId the identifier of the service to be accessed
	 * @return a {@link CompletableFuture} that completes with a map containing authorization details
	 *         (e.g., tokens, permissions) if successful, or completes exceptionally if authorization fails
	 */
	CompletableFuture<Map<String, Object>> authorize(Id userId, Id deviceId, String serviceId);

	/**
	 * Returns a no-operation implementation of {@link ClientAuthorizer}.
	 *
	 * This implementation provides an authorization mechanism that always completes
	 * successfully without performing any checks or validations. It returns an
	 * empty map to indicate no authorization details are provided.
	 *
	 * @return a no-op {@link ClientAuthorizer} instance
	 */
	static ClientAuthorizer noop() {
		return (userId, deviceId, serviceId) -> CompletableFuture.completedFuture(Map.of());
	}
}