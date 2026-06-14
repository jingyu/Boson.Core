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
 * Implementations decide whether a user, acting from a specific device, is permitted to use the
 * requested service, and - on success - return any authorization details the service will need
 * downstream (issued tokens, granted features, rate-limit overrides, etc.).
 * <p>
 * <strong>Result contract.</strong>
 * <ul>
 *   <li><strong>Authorized</strong> - the future completes successfully with a
 *       {@code Map<String, Object>} carrying authorization details. The exact key set is
 *       implementation-defined for now and may evolve; consumers should treat unknown keys as
 *       opaque and missing keys as "not provided." An empty map means "authorized with no extra
 *       details." {@code null} should not be returned.</li>
 *   <li><strong>Denied or system error</strong> - the future completes <em>exceptionally</em> with
 *       a {@link io.bosonnetwork.BosonException} (or a subtype). Implementations are encouraged
 *       to distinguish denial from infrastructure failure through the exception subtype/message,
 *       but both flow through the same exceptional-completion channel from the caller's
 *       perspective.</li>
 * </ul>
 *
 * <p>
 * <strong>Stability:</strong> the map shape is intentionally untyped pending a refined
 * {@code AuthorizationDecision} type; do not couple your code to specific keys without
 * coordinating with the implementation you target.
 */
public interface ClientAuthorizer {
	/**
	 * Authorizes the specified user and device for the given service. See the
	 * {@linkplain ClientAuthorizer interface Javadoc} for the success/failure contract.
	 *
	 * @param userId      the unique identifier of the user requesting access
	 * @param deviceId    the unique identifier of the device used for the request
	 * @param serviceType the identifier of the target service type to be accessed
	 * @return a {@link CompletableFuture} that completes with the authorization-details map on
	 *         success, or completes exceptionally with a {@link io.bosonnetwork.BosonException}
	 *         on denial or system error
	 */
	CompletableFuture<Map<String, Object>> authorize(Id userId, Id deviceId, String serviceType);
}