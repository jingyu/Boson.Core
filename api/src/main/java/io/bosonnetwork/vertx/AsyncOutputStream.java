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

package io.bosonnetwork.vertx;

import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;
import org.jspecify.annotations.Nullable;

/**
 * A Vert.x {@link WriteStream} that adapts a blocking {@link OutputStream}, writing buffers to it
 * without holding a worker thread for the stream's lifetime.
 * <p>
 * The stream captures the Vert.x {@link Context} that is current when it is constructed and completes
 * all writes, and invokes all handlers, on that context. Buffers are written one at a time, each in a
 * short {@link Context#executeBlocking(java.util.concurrent.Callable, boolean) executeBlocking} task, so
 * - unlike a naive adapter that holds a worker thread while idle - the worker pool is used only for the
 * duration of an actual write. Only one write is in flight at a time, so buffers reach the underlying
 * stream in submission order.
 * <p>
 * Back-pressure follows the standard Vert.x contract. The write queue is measured in buffered bytes and
 * bounded by {@link #setWriteQueueMaxSize(int)}: {@link #writeQueueFull()} reports when the buffered
 * bytes reach that bound, and once full, the {@link #drainHandler(Handler) drain handler} fires when the
 * queue drains back to half the bound. Writes are always accepted (never rejected for fullness); it is
 * the producer's responsibility to honour {@code writeQueueFull()}.
 *
 * <h2>Threading</h2>
 * Instances must be created on a Vert.x context; constructing one off a context throws
 * {@link IllegalStateException}. As with the built-in Vert.x streams, the stream's methods are expected
 * to be called from that same context.
 *
 * <h2>Resource ownership</h2>
 * On {@link #end()} the wrapped {@link OutputStream} is flushed and, when {@code closeOutput} is set (the
 * default for the single-argument constructor), closed. It is also closed on failure when owned. When
 * {@code closeOutput} is {@code false} the caller retains ownership and the stream is only flushed.
 */
public class AsyncOutputStream implements WriteStream<Buffer> {
	private static final int DEFAULT_MAX_QUEUE_BYTES = 1024 * 1024;

	private final Context context;
	private final OutputStream output;
	private final boolean closeOutput;

	private @Nullable Handler<Throwable> exceptionHandler;
	private @Nullable Handler<Void> drainHandler;

	// Buffers awaiting write, in submission order, each paired with the promise of its write() future.
	private final Deque<PendingWrite> pending = new ArrayDeque<>();
	// Total bytes currently buffered in pending; the quantity bounded by maxQueueBytes.
	private long pendingBytes;
	private int maxQueueBytes = DEFAULT_MAX_QUEUE_BYTES;
	// Set once the queue has been observed full; cleared when the drain handler fires. Gates drain so it
	// fires only after a full -> half-empty transition, per the Vert.x back-pressure contract.
	private boolean wasFull;

	private boolean writeInProgress; // a blocking write is in flight; serializes writes and ordering
	private boolean ended;           // end() has been requested; no further writes are accepted
	private boolean finishing;       // the terminal flush/close has been dispatched (fires exactly once)
	private boolean closed;          // terminal state reached: flushed/closed or failed
	private @Nullable Promise<Void> endPromise;

	private record PendingWrite(Buffer buffer, Promise<Void> promise) {}

	/**
	 * Creates a stream over {@code output} that flushes and closes the wrapped {@link OutputStream} on
	 * {@link #end()}.
	 *
	 * @param output the blocking output stream to adapt
	 * @throws IllegalStateException if there is no current Vert.x context
	 */
	public AsyncOutputStream(OutputStream output) {
		this(output, true);
	}

	/**
	 * Creates a stream over {@code output}, bound to the {@linkplain Vertx#currentContext() current Vert.x
	 * context}.
	 *
	 * @param output      the blocking output stream to adapt
	 * @param closeOutput whether to close {@code output} when the stream ends or fails (it is always
	 *                    flushed on {@link #end()})
	 * @throws IllegalStateException if there is no current Vert.x context
	 */
	public AsyncOutputStream(OutputStream output, boolean closeOutput) {
		Context current = Vertx.currentContext();
		if (current == null)
			throw new IllegalStateException("Must be created on a Vert.x context");

		this.context = current;
		this.output = Objects.requireNonNull(output, "output");
		this.closeOutput = closeOutput;
	}

	@Override
	public AsyncOutputStream exceptionHandler(@Nullable Handler<Throwable> handler) {
		this.exceptionHandler = handler;
		return this;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The buffer is queued and written asynchronously on the stream's context, preserving submission
	 * order. The returned future completes once this buffer has been written to the underlying stream, or
	 * fails if the write fails, if the stream has already been {@linkplain #end() ended}, or if
	 * {@code data} is {@code null}.
	 */
	@Override
	public Future<Void> write(Buffer data) {
		Promise<Void> promise = Promise.promise();
		context.runOnContext(v -> {
			if (ended || closed) {
				promise.tryFail(new IllegalStateException("Stream is already ended"));
				return;
			}

			if (data == null) {
				Throwable err = new NullPointerException("data is null");
				notifyException(err);
				promise.tryFail(err);
				return;
			}

			pending.add(new PendingWrite(data, promise));
			pendingBytes += data.length();
			pump();
		});
		return promise.future();
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Marks the stream as ended so that no further {@link #write(Buffer)} is accepted, drains any queued
	 * writes, then flushes and (when owned) closes the underlying stream. The returned future completes
	 * once that terminal flush/close has finished, or fails if it - or any outstanding write - fails, or
	 * if the stream was already ended.
	 */
	@Override
	public Future<Void> end() {
		Promise<Void> promise = Promise.promise();
		context.runOnContext(v -> {
			if (ended || closed) {
				promise.tryFail(new IllegalStateException("Stream is already ended"));
				return;
			}

			ended = true;
			endPromise = promise;
			pump(); // drain remaining writes, then finish; a no-op guard runs finish() exactly once
		});
		return promise.future();
	}

	/**
	 * {@inheritDoc}
	 *
	 * @param maxSize the maximum number of buffered bytes before {@link #writeQueueFull()} reports full
	 * @throws IllegalArgumentException if {@code maxSize < 1}
	 */
	@Override
	public AsyncOutputStream setWriteQueueMaxSize(int maxSize) {
		if (maxSize < 1)
			throw new IllegalArgumentException("maxSize must be >= 1");
		this.maxQueueBytes = maxSize;
		return this;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return {@code true} if the buffered bytes have reached {@link #setWriteQueueMaxSize(int) the bound}
	 */
	@Override
	public boolean writeQueueFull() {
		boolean full = pendingBytes >= maxQueueBytes;
		if (full)
			wasFull = true;
		return full;
	}

	@Override
	public AsyncOutputStream drainHandler(@Nullable Handler<Void> handler) {
		this.drainHandler = handler;
		return this;
	}

	/**
	 * Write pump: dispatches the next queued buffer, or - when the queue is empty and {@link #end()} has
	 * been requested - runs the terminal flush/close. At most one blocking write is in flight at a time
	 * (guarded by {@code writeInProgress}), so writes stay ordered and never overlap. Called after each
	 * enqueue, after each write completes, and from {@link #end()}.
	 */
	private void pump() {
		if (writeInProgress)
			return;

		if (pending.isEmpty()) {
			// Nothing left to write: run the terminal flush/close once, if end() has been requested.
			if (ended && !finishing) {
				finishing = true;
				finish();
			}
			return;
		}

		PendingWrite w = pending.poll();
		pendingBytes -= w.buffer().length();
		writeInProgress = true;

		byte[] bytes = w.buffer().getBytes();
		context.executeBlocking(() -> {
			output.write(bytes);
			return null;
		}, false).onComplete(ar -> {
			writeInProgress = false;

			if (ar.failed()) {
				// Run the terminal cleanup (close, notify) first, then resolve this write's future so a
				// caller awaiting it already observes the closed/failed state.
				fail(ar.cause());
				w.promise().tryFail(ar.cause());
				return;
			}

			// Completing the write future may re-enter this stream (e.g. a pipe reacting to the write by
			// calling end()); end() only sets state and re-pumps, so the single finish() below stays safe.
			w.promise().tryComplete();
			callDrainIfNeeded();
			pump();
		});
	}

	/**
	 * Fires the drain handler on the full -> half-empty transition: only after the queue has been observed
	 * full and once the buffered bytes fall back to at most half the configured bound.
	 */
	private void callDrainIfNeeded() {
		if (wasFull && pendingBytes <= maxQueueBytes / 2) {
			wasFull = false;
			Handler<Void> h = drainHandler;
			if (h != null)
				h.handle(null);
		}
	}

	/**
	 * Terminal success path: flush and (when owned) close the underlying stream on a worker thread, then
	 * complete the {@link #end()} future. A flush/close failure is routed through {@link #fail(Throwable)}.
	 */
	private void finish() {
		context.executeBlocking(() -> {
			output.flush();
			if (closeOutput)
				output.close();
			return null;
		}, false).onComplete(ar -> {
			if (ar.failed()) {
				fail(ar.cause());
				return;
			}

			closed = true;
			Objects.requireNonNull(endPromise, "endPromise").tryComplete();
		});
	}

	/**
	 * Terminal failure path: close the output when owned, surface the cause to the exception handler, then
	 * fail the {@link #end()} future (if one is awaiting) and every queued write. Side effects run before
	 * the futures resolve, so a caller awaiting one already observes the closed/failed state. Idempotent;
	 * a no-op once the stream has reached a terminal state.
	 */
	private void fail(Throwable cause) {
		if (closed)
			return;
		closed = true;
		pendingBytes = 0;

		if (closeOutput) {
			try {
				output.close();
			} catch (Throwable ignored) {
				// best-effort close
			}
		}

		notifyException(cause);

		Promise<Void> ep = endPromise;
		if (ep != null)
			ep.tryFail(cause);

		PendingWrite w;
		while ((w = pending.poll()) != null)
			w.promise().tryFail(cause);
	}

	/**
	 * Deliver a failure to the registered exception handler. Always invoked on the captured context thread.
	 */
	private void notifyException(Throwable t) {
		Handler<Throwable> h = exceptionHandler;
		if (h != null) {
			try {
				h.handle(t);
			} catch (Throwable ignored) {
				// Swallow: an exception from the exception handler must not propagate.
			}
		}
	}
}