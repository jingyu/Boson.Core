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
 * Jackson-based serialization for Boson, supporting JSON, CBOR and YAML through one configured
 * facade.
 *
 * <h2>{@link io.bosonnetwork.json.Json}</h2>
 * The single entry point. It exposes pre-configured, shared (thread-safe) Jackson mappers/factories
 * and convenience {@code toString} / {@code toPrettyString} / {@code toBytes} / {@code parse}
 * helpers. The mappers register a module with custom (de)serializers for Boson domain types
 * ({@link io.bosonnetwork.Id}, {@link io.bosonnetwork.NodeInfo}, {@link io.bosonnetwork.PeerInfo},
 * {@link io.bosonnetwork.Value}, {@link java.util.Date}, {@link java.net.InetAddress}).
 * <p>
 * A consistent dual-encoding convention is applied: binary formats (CBOR) carry IDs, addresses and
 * other byte fields as binary; text formats (JSON/YAML) use Base58 / string forms. The URL-safe
 * Base64 variant is used throughout, and {@link io.bosonnetwork.json.Json#cborMapper()} /
 * {@link io.bosonnetwork.json.Json#cborFactory()} also back the CBOR encoding of
 * {@link io.bosonnetwork.cwt.SignedCwt}.
 *
 * <h2>{@link io.bosonnetwork.json.JsonContext}</h2>
 * An immutable Jackson {@code ContextAttributes} subclass for passing per-call or shared
 * serialization options (for example selecting the W3C-DID string form for IDs).
 *
 * <p>The concrete (de)serializers live in the non-published {@code internal} subpackage.
 *
 * <p>This package is {@link org.jspecify.annotations.NullMarked} - every type, parameter, return and
 * field is non-null by default; anything that may be {@code null} is explicitly
 * {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package io.bosonnetwork.json;

import org.jspecify.annotations.NullMarked;