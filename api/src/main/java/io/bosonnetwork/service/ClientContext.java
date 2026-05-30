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
import io.bosonnetwork.Identity;
import io.bosonnetwork.service.impl.AllowAllClientContext;
import io.bosonnetwork.service.impl.StaticClientContext;
import io.bosonnetwork.web.CwtAuth;

/**
 * Client manager interface (Read-only) for the Boson Super Node services.
 * <p>
 * This interface provides methods to query information about client users and their associated devices
 * registered within the network. It allows retrieving user details, checking for user existence,
 * and listing or verifying devices associated with users.
 */
public interface ClientContext {
	/**
	 * Retrieves the user information for a given user ID.
	 *
	 * @param userId the unique identifier of the user to retrieve
	 * @return a {@link CompletableFuture} that completes with the {@link ClientUser} object if found,
	 *         or completes with {@code null} if the user does not exist
	 */
	CompletableFuture<ClientUser> getUser(Id userId);

	/**
	 * Checks if a user with the specified ID exists.
	 *
	 * @param userId the unique identifier of the user to check
	 * @return a {@link CompletableFuture} that completes with {@code true} if the user exists,
	 *         or {@code false} otherwise
	 */
	CompletableFuture<Boolean> existsUser(Id userId);

	/**
	 * Retrieves a device associated with the specified user.
	 *
	 * @param userId   the unique identifier of the user
	 * @param deviceId the unique identifier of the device
	 * @return a {@link CompletableFuture} that completes with the {@link ClientDevice} if it exists
	 *         and belongs to the user, or completes with {@code null} otherwise
	 */
	CompletableFuture<ClientDevice> getDevice(Id userId, Id deviceId);

	/**
	 * Checks if a specific device exists and is associated with the specified user.
	 *
	 * @param userId   the unique identifier of the user
	 * @param deviceId the unique identifier of the device
	 * @return a {@link CompletableFuture} that completes with {@code true} if the device exists and belongs to the user,
	 *         or {@code false} otherwise
	 */
	CompletableFuture<Boolean> existsDevice(Id userId, Id deviceId);

	/**
	 * Gets the client authenticator instance.
	 *
	 * @return the {@link ClientAuthenticator} used for authenticating clients.
	 */
	ClientAuthenticator getAuthenticator();

	/**
	 * Gets the client authorizer instance.
	 *
	 * @return the {@link ClientAuthorizer} used for authorizing client requests.
	 */
	ClientAuthorizer getAuthorizer();

	/**
	 * Get the instance of {@link CwtAuth} used for handling
	 * web token authentication within the client context.
	 *
	 * @return the {@link CwtAuth} instance responsible for managing
	 *         web token authentication.
	 */
	CwtAuth getWebAuthenticator();

	/**
	 * Returns an "allow-all" client context — intended for development, smoke tests, and bring-up
	 * of a service that does not yet wire a real client store. Concretely:
	 * <ul>
	 *   <li>{@link #getUser(Id)} returns a fresh anonymous {@code PlainUser} for any id, and
	 *       {@link #getDevice(Id, Id)} returns a fresh anonymous {@code PlainDevice};</li>
	 *   <li>{@link #existsUser(Id)} and {@link #existsDevice(Id, Id)} always complete with
	 *       {@code true};</li>
	 *   <li>{@link #getAuthenticator()} accepts any caller (and, when a nonce/signature is supplied,
	 *       verifies it against the id's key);</li>
	 *   <li>{@link #getAuthorizer()} grants access with an empty details map;</li>
	 *   <li>{@link #getWebAuthenticator()} returns a {@link CwtAuth} backed by the same allow-all
	 *       provider, and therefore requires a non-null {@code nodeIdentity}.</li>
	 * </ul>
	 *
	 * @param nodeIdentity the identity that will sign issued web tokens (required if
	 *                     {@link #getWebAuthenticator()} will be called)
	 * @return a permissive {@link ClientContext}
	 */
	static ClientContext allowAll(Identity nodeIdentity) {
		return new AllowAllClientContext(nodeIdentity);
	}

	/**
	 * Returns an in-memory client context whose user and device registries are populated
	 * imperatively at test/bring-up time. {@link io.bosonnetwork.service.impl.StaticClientContext}
	 * exposes {@code addUser(...)}, {@code addDevice(...)}, {@code removeUser(...)}, etc. for
	 * fixture setup. {@link #getWebAuthenticator()} returns a real {@link CwtAuth} backed by the
	 * registry and requires a non-null {@code nodeIdentity}.
	 *
	 * @param nodeIdentity the identity that will sign issued web tokens (required if
	 *                     {@link #getWebAuthenticator()} will be called)
	 * @return an in-memory {@link ClientContext}
	 */
	static ClientContext staticContext(Identity nodeIdentity) {
		return new StaticClientContext(nodeIdentity);
	}
}