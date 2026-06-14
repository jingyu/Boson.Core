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
 * Integration helpers that make Vert.x easier and safer to use within Boson modules.
 * <ul>
 *   <li>{@link io.bosonnetwork.vertx.BosonVerticle} - an abstract verticle base that hides the
 *       Vert.x 4.x/5.x {@code Verticle}/{@code Deployable} API differences behind
 *       {@code deploy()}/{@code undeploy()};</li>
 *   <li>{@link io.bosonnetwork.vertx.ContextualFuture} - a {@link java.util.concurrent.CompletableFuture}
 *       compatible wrapper around a Vert.x {@code Future}, bridging the reactive and
 *       {@code CompletionStage} programming models;</li>
 *   <li>{@link io.bosonnetwork.vertx.BufferInputStream} / {@link io.bosonnetwork.vertx.BufferOutputStream}
 *       â zero-copy {@code InputStream}/{@code OutputStream} views over a Vert.x {@code Buffer}
 *       (e.g. for Jackson);</li>
 *   <li>{@link io.bosonnetwork.vertx.ObservableReadStream} - a {@code ReadStream} wrapper that
 *       observes each element (and treats an observer error as the authoritative failure signal);</li>
 *   <li>{@link io.bosonnetwork.vertx.VertxCaffeine} - an {@link java.util.concurrent.Executor} and
 *       scheduler that let a Caffeine cache run cooperatively on the Vert.x event loop.</li>
 * </ul>
 *
 * <p><strong>Threading:</strong> blocking accessors (such as
 * {@link io.bosonnetwork.vertx.ContextualFuture#get()}) must never be called on a Vert.x event-loop
 * or worker thread.
 *
 * <p>This package is {@link org.jspecify.annotations.NullMarked} - every type, parameter, return and
 * field is non-null by default; anything that may be {@code null} is explicitly
 * {@link org.jspecify.annotations.Nullable}.
 */
@NullMarked
package io.bosonnetwork.vertx;

import org.jspecify.annotations.NullMarked;