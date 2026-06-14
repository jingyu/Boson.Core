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

package io.bosonnetwork.web;

import java.util.Optional;

import io.vertx.core.Future;

import io.bosonnetwork.Id;
import io.bosonnetwork.service.Principal;

/**
 * Interface for retrieving user and client entities.
 */
public interface ClientProvider {
	/**
	 * Retrieves the user or federated node by ID.
	 *
	 * @param userId the user ID or node ID
	 * @return a {@link Future} that completes with the resolved {@link Principal}
	 *         (a {@link io.bosonnetwork.service.ClientUser ClientUser} or
	 *         {@link io.bosonnetwork.service.SuperNodeInfo SuperNodeInfo}), or
	 *         {@link Optional#empty()} if no such principal exists
	 */
	Future<Optional<Principal>> getUser(Id userId);

	/**
	 * Retrieves the client (device or service) by ID.
	 *
	 * @param userId   the ID of the user or node that owns the client
	 * @param clientId the client ID (device ID or service peer ID)
	 * @return a {@link Future} that completes with the resolved {@link Principal}
	 *         (a {@link io.bosonnetwork.service.ClientDevice ClientDevice} or
	 *         {@link io.bosonnetwork.service.ServiceInfo ServiceInfo}), or
	 *         {@link Optional#empty()} if no such principal exists
	 */
	Future<Optional<Principal>> getClient(Id userId, Id clientId);
}