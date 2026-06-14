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

/**
 * CWT-based HTTP authentication for Boson web services, built on Vert.x Web.
 * <p>
 * This package adapts the {@link io.bosonnetwork.cwt.SignedCwt} token format into the Vert.x
 * authentication/authorization model so that super-node HTTP services can authenticate clients and
 * federation peers from a bearer token.
 * <ul>
 *   <li>{@link io.bosonnetwork.web.CwtAuth} - a Vert.x {@code AuthenticationProvider} that parses
 *       and verifies a CWT, validates the issuer against the accepted set (this node, the subject
 *       for self-issued tokens, or the client), and resolves the principal;</li>
 *   <li>{@link io.bosonnetwork.web.CwtAuthHandler} - the Vert.x Web handler that extracts the bearer
 *       token, drives {@code CwtAuth}, and enforces required scopes;</li>
 *   <li>{@link io.bosonnetwork.web.CwtAuthOptions} - configuration (issuing identity, expected
 *       audience, clock-skew leeway, default scope/TTL, client provider);</li>
 *   <li>{@link io.bosonnetwork.web.ClientProvider} - the lookup callback the auth layer uses to
 *       resolve a client/principal referenced by a token.</li>
 * </ul>
 * Principal entitlements map onto {@link io.bosonnetwork.service.Role} /
 * {@link io.bosonnetwork.service.AccessScope} from the service package.
 *
 * <p>This package is {@link org.jspecify.annotations.NullMarked} - every type, parameter, return and
 * field is non-null by default; anything that may be {@code null} is explicitly
 * {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package io.bosonnetwork.web;

import org.jspecify.annotations.NullMarked;