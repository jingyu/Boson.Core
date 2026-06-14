/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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
 * The core public API of the Boson network — the root types every other package and third-party
 * SDK builds on.
 *
 * <h2>Node and identity</h2>
 * {@link io.bosonnetwork.Node} is the DHT node abstraction (lookups, value/peer storage, encrypt /
 * decrypt / sign), created via {@link io.bosonnetwork.NodeFactory} from a
 * {@link io.bosonnetwork.NodeConfiguration}. {@link io.bosonnetwork.Identity} and
 * {@link io.bosonnetwork.UserProfile} model cryptographic identities.
 *
 * <h2>Addressing and DHT records</h2>
 * {@link io.bosonnetwork.Id} is the 256-bit Ed25519-based identifier used for nodes, values and
 * peers. {@link io.bosonnetwork.Value} (mutable/immutable stored data),
 * {@link io.bosonnetwork.PeerInfo} (announced peers) and {@link io.bosonnetwork.NodeInfo} /
 * {@link io.bosonnetwork.Network} describe what lives in and around the DHT.
 *
 * <h2>Operations and results</h2>
 * Lookups are tuned by {@link io.bosonnetwork.LookupOption} and return their outcome through
 * {@link io.bosonnetwork.Result}. Errors surface as {@link io.bosonnetwork.BosonException} and its
 * subtypes (e.g. {@link io.bosonnetwork.ExpiredException}), and {@link io.bosonnetwork.Version}
 * carries node version metadata.
 *
 * <p>Supporting concerns live in subpackages: {@link io.bosonnetwork.crypto cryptography},
 * {@link io.bosonnetwork.identifier DID/VC identity}, {@link io.bosonnetwork.cwt CWT tokens},
 * {@link io.bosonnetwork.service layer-2 services}, {@link io.bosonnetwork.json serialization},
 * {@link io.bosonnetwork.database database helpers}, {@link io.bosonnetwork.web web auth},
 * {@link io.bosonnetwork.vertx Vert.x helpers} and {@link io.bosonnetwork.utils utilities}.
 *
 * <p>This package is {@link org.jspecify.annotations.NullMarked} — every type, parameter, return and
 * field is non-null by default; anything that may be {@code null} is explicitly
 * {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package io.bosonnetwork;

import org.jspecify.annotations.NullMarked;