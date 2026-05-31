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
 * Common, dependency-light utility and support classes used across Boson.
 *
 * <ul>
 *   <li><strong>Encoding:</strong> {@link io.bosonnetwork.utils.Hex},
 *       {@link io.bosonnetwork.utils.Base58}, {@link io.bosonnetwork.utils.Bytes} and
 *       {@link io.bosonnetwork.utils.Sha256Hash} for byte/string conversion and hashing;</li>
 *   <li><strong>Networking:</strong> {@link io.bosonnetwork.utils.AddressUtils} for classifying IP
 *       addresses (bogon / martian / private / global-unicast) relevant to DHT routing;</li>
 *   <li><strong>Configuration &amp; files:</strong> {@link io.bosonnetwork.utils.ConfigMap} (typed
 *       config access), {@link io.bosonnetwork.utils.FileUtils} (XDG-aware paths) and
 *       {@link io.bosonnetwork.utils.ApplicationLock} (single-instance file lock);</li>
 *   <li><strong>Functional &amp; data holders:</strong> {@link io.bosonnetwork.utils.Functional}
 *       (checked-exception lambda helpers), {@link io.bosonnetwork.utils.Variable} (a mutable
 *       {@code Optional}-like cell), and the {@link io.bosonnetwork.utils.Pair} /
 *       {@link io.bosonnetwork.utils.Triple} / {@link io.bosonnetwork.utils.Quadruple} tuples;</li>
 *   <li><strong>Streams:</strong> {@link io.bosonnetwork.utils.ByteBufferInputStream} /
 *       {@link io.bosonnetwork.utils.ByteBufferOutputStream} adapters, and string helpers in
 *       {@link io.bosonnetwork.utils.StringUtils}.</li>
 * </ul>
 */
package io.bosonnetwork.utils;