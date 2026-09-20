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
 * The client side of Boson's HTTP authentication: what a client calling a Boson service needs, as
 * {@link io.bosonnetwork.web} holds what the service itself needs.
 * <ul>
 *   <li>{@link io.bosonnetwork.web.client.AccessTokenSource} - supplies the bearer token for each
 *       request, and decides whether a refusal is worth one retry;</li>
 *   <li>{@link io.bosonnetwork.web.client.SelfIssuedAccessTokens} - the usual implementation: the
 *       client signs its own CWT with its own key, caches it, and corrects its clock from the
 *       {@code Date} header of a refusal.</li>
 * </ul>
 * Nothing here depends on {@code vertx-web}, which is what keeps it usable by clients: only
 * {@code vertx-core} and the CWT codec.
 *
 * <p>This package is {@link org.jspecify.annotations.NullMarked} - every type, parameter, return and
 * field is non-null by default; anything that may be {@code null} is explicitly
 * {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package io.bosonnetwork.web.client;

import org.jspecify.annotations.NullMarked;
