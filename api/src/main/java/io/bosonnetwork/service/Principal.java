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

/**
 * A resolved principal returned by a {@link io.bosonnetwork.web.ClientProvider} lookup.
 * <p>
 * A principal is one of the four authenticatable entities Boson super-node services deal with: an
 * end user ({@link ClientUser}) or one of its devices ({@link ClientDevice}) on the client side, and
 * a federated super node ({@link SuperNodeInfo}) or one of the services it hosts ({@link ServiceInfo})
 * on the federation side. This sealed type lets callers dispatch on the concrete kind exhaustively
 * (for example with a {@code switch} over the permitted subtypes) instead of testing an untyped
 * {@code Object}.
 */
public sealed interface Principal permits ClientUser, ClientDevice, SuperNodeInfo, ServiceInfo {
}
