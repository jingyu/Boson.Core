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

import java.io.InputStream;
import java.util.Objects;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.jspecify.annotations.Nullable;

/**
 * A Vert.x {@link ReadStream} that adapts a blocking {@link InputStream}, reading it chunk by chunk
 * without holding a worker thread for the stream's lifetime.
 * <p>
 * The stream captures the Vert.x {@link Context} that is current when it is constructed and produces all
 * data, and invokes all handlers, on that context. Each chunk is read in a short
 * {@link Context#executeBlocking(java.util.concurrent.Callable, boolean) executeBlocking} task and the
 * next read is scheduled only while there is outstanding demand, so - unlike a naive adapter that loops
 * on a worker thread - no worker thread is held while the stream is idle or paused.
 * <p>
 * Back-pressure follows the standard Vert.x contract:
 * <ul>
 *   <li>{@link #pause()} stops further reads;</li>
 *   <li>{@link #resume()} restarts continuous (flowing) delivery;</li>
 *   <li>{@link #fetch(long)} requests a bounded number of further chunks while paused.</li>
 * </ul>
 * Standard {@link #pipe()}/{@code pipeTo} support is inherited from {@link ReadStream}.
 *
 * <h2>Threading</h2>
 * Instances must be created on a Vert.x context; constructing one off a context throws
 * {@link IllegalStateException}. As with the built-in Vert.x streams, the stream's methods are expected
 * to be called from that same context.
 *
 * <h2>Resource ownership</h2>
 * When {@code closeInput} is set (the default for the single-argument constructor), the wrapped
 * {@link InputStream} is closed once the stream reaches a terminal state - either normal end of input or
 * a failure. When {@code closeInput} is {@code false} the caller retains ownership and the input is never
 * closed.
 */
public class AsyncInputStream implements ReadStream<Buffer> {
	private static final int DEFAULT_READ_BUFFER_SIZE = 32 * 1024;

	private final Context context;
	private final InputStream input;
	private final int readBufferSize;
	private final boolean closeInput;

	private @Nullable Handler<Buffer> dataHandler;
	private @Nullable Handler<Void> endHandler;
	private @Nullable Handler<Throwable> exceptionHandler;

	// Outstanding number of chunks to deliver: Long.MAX_VALUE means "flowing" (unbounded), 0 means
	// paused, a finite value is the number of further chunks a fetch(long) has requested. Written by
	// pause()/resume() (hence volatile) and adjusted on the context thread while reading.
	private volatile long demand;
	// Whether a blocking read is in flight; guards against overlapping reads on the same input.
	private boolean readingInProgress;
	// Whether the stream has reached a terminal state (end of input or failure) and the input, if
	// owned, has been closed. Once set, no further reads or events occur.
	private boolean ended;

	/**
	 * Creates a stream over {@code input} using the default read buffer size (32 KiB) that closes the
	 * wrapped {@link InputStream} when the stream terminates,
	 * bound to the {@linkplain Vertx#currentContext() current Vert.x context}.
	 *
	 * @param input the blocking input stream to adapt
	 * @throws IllegalStateException if there is no current Vert.x context
	 */
	public AsyncInputStream(InputStream input) {
		this(null, input, DEFAULT_READ_BUFFER_SIZE, true);
	}

	/**
	 * Creates a stream over {@code input} using the default read buffer size (32 KiB) that closes the
	 * wrapped {@link InputStream} when the stream terminates. The stream binds to the
	 * {@linkplain Vertx#currentContext() current Vert.x context} if there is one, otherwise to a context
	 * obtained from {@code vertx}.
	 *
	 * @param vertx the Vert.x instance used to obtain a context when called off a Vert.x context; may be
	 *              {@code null} to require a current context
	 * @param input the blocking input stream to adapt
	 * @throws IllegalStateException if there is no current Vert.x context and {@code vertx} is {@code null}
	 */
	public AsyncInputStream(@Nullable Vertx vertx, InputStream input) {
		this(vertx, input, DEFAULT_READ_BUFFER_SIZE, true);
	}

	/**
	 * Creates a stream over {@code input}, bound to the {@linkplain Vertx#currentContext() current Vert.x
	 * context}.
	 *
	 * @param input          the blocking input stream to adapt
	 * @param readBufferSize the maximum number of bytes to read per chunk; values {@code <= 0} select the
	 *                       default of 32 KiB
	 * @param closeInput     whether to close {@code input} when the stream terminates (ends or fails)
	 * @throws IllegalStateException if there is no current Vert.x context
	 */
	public AsyncInputStream(InputStream input, int readBufferSize, boolean closeInput) {
		this(null, input, readBufferSize, closeInput);
	}

	/**
	 * Creates a stream over {@code input}. The stream binds to the {@linkplain Vertx#currentContext()
	 * current Vert.x context} if there is one, otherwise to a context obtained from {@code vertx}.
	 *
	 * @param vertx          the Vert.x instance used to obtain a context when called off a Vert.x context;
	 *                       may be {@code null} to require a current context
	 * @param input          the blocking input stream to adapt
	 * @param readBufferSize the maximum number of bytes to read per chunk; values {@code <= 0} select the
	 *                       default of 32 KiB
	 * @param closeInput     whether to close {@code input} when the stream terminates (ends or fails)
	 * @throws IllegalStateException if there is no current Vert.x context and {@code vertx} is {@code null}
	 */
	public AsyncInputStream(@Nullable Vertx vertx, InputStream input, int readBufferSize, boolean closeInput) {
		Context current = Vertx.currentContext();
		if (current == null) {
			if (vertx == null)
				throw new IllegalStateException("Must be created on a Vert.x context or passed a Vert.x instance");

			current = vertx.getOrCreateContext();
		}

		this.context = current;
		this.input = Objects.requireNonNull(input, "input");
		this.readBufferSize = readBufferSize > 0 ? readBufferSize : DEFAULT_READ_BUFFER_SIZE;
		this.closeInput = closeInput;
		this.demand = Long.MAX_VALUE; // start in flowing mode
	}

	@Override
	public AsyncInputStream exceptionHandler(@Nullable Handler<Throwable> handler) {
		this.exceptionHandler = handler;
		return this;
	}

	@Override
	public AsyncInputStream handler(@Nullable Handler<Buffer> handler) {
		this.dataHandler = handler;
		if (handler != null)
			doRead(); // Kick off the producer. If the stream already ended this is a no-op.

		return this;
	}

	@Override
	public AsyncInputStream pause() {
		demand = 0L;
		return this;
	}

	@Override
	public AsyncInputStream resume() {
		demand = Long.MAX_VALUE;
		doRead();
		return this;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Adds {@code amount} to the outstanding demand and resumes reading until that many further chunks
	 * have been delivered (or the input is drained), each chunk carrying up to the configured read buffer
	 * size. If the stream is already flowing the call has no effect. Reports {@link IllegalArgumentException}
	 * to the exception handler if {@code amount <= 0}.
	 */
	@Override
	public AsyncInputStream fetch(long amount) {
		context.runOnContext(v -> {
			if (ended)
				return;

			if (amount <= 0) {
				// A bad argument is a caller error, not a terminal stream failure: report it but leave
				// the stream healthy.
				notifyException(new IllegalArgumentException("fetch amount must be > 0"));
				return;
			}

			if (demand != Long.MAX_VALUE) {
				demand += amount;
				if (demand < 0) // overflow: treat as unbounded
					demand = Long.MAX_VALUE;
			}

			doRead();
		});

		return this;
	}

	@Override
	public AsyncInputStream endHandler(@Nullable Handler<Void> handler) {
		this.endHandler = handler;
		return this;
	}

	/**
	 * Reads up to {@code amount} bytes from the input on a worker thread.
	 *
	 * @param amount the size of the read buffer
	 * @return a future completed with the bytes read, or with {@code null} at end of input, or failed if
	 *         the underlying read throws
	 */
	private Future<@Nullable Buffer> blockRead(int amount) {
		byte[] buf = new byte[amount];
		Promise<@Nullable Buffer> promise = Promise.promise();
		context.executeBlocking(() -> input.read(buf), false)
				.onComplete(ar -> {
					if (ar.failed()) {
						promise.fail(ar.cause());
						return;
					}

					int len = ar.result();
					if (len < 0)
						promise.complete(); // end of input
					else
						promise.complete(Buffer.buffer(len).appendBytes(buf, 0, len));
				});
		return promise.future();
	}

	/**
	 * Producer pump: read and emit the next chunk (or signal end) on the context thread. Self-reschedules
	 * while there is outstanding demand, and stops when the stream is paused ({@code demand == 0}), ended,
	 * or a read is already in flight. Re-armed by {@link #resume()} and {@link #fetch(long)}.
	 */
	private void doRead() {
		context.runOnContext(v -> {
			if (ended || demand == 0L || readingInProgress)
				return;

			Handler<Buffer> h = this.dataHandler;
			if (h == null)
				return;

			readingInProgress = true;
			try {
				blockRead(readBufferSize).onComplete(ar -> {
					readingInProgress = false;

					if (ar.failed()) {
						fail(ar.cause());
						return;
					}

					Buffer buf = ar.result();
					if (buf == null) { // end of input
						terminate();
						notifyEndHandler();
						return;
					}

					if (demand != Long.MAX_VALUE)
						demand--;

					if (deliver(buf)) // continue only if delivery succeeded and the stream is still live
						doRead();
				});
			} catch (Throwable t) {
				readingInProgress = false;
				fail(t);
			}
		});
	}

	/**
	 * Delivers a chunk to the data handler. If the handler throws, the stream is terminated and the
	 * failure surfaced to the exception handler.
	 *
	 * @param data the chunk to deliver
	 * @return {@code true} if delivery succeeded and reading may continue, {@code false} if there is no
	 *         handler or the handler failed (in which case the stream has been terminated)
	 */
	private boolean deliver(Buffer data) {
		Handler<Buffer> h = dataHandler;
		if (h == null)
			return false;

		try {
			h.handle(data);
			return true;
		} catch (Throwable t) {
			// A failure from the downstream data handler is treated as terminal.
			fail(t);
			return false;
		}
	}

	/**
	 * Moves the stream to its terminal state and, when {@code closeInput} is set, closes the wrapped input
	 * on a best-effort basis. Idempotent.
	 */
	private void terminate() {
		if (ended)
			return;
		ended = true;

		if (closeInput) {
			try {
				input.close();
			} catch (Throwable ignored) {
				// best-effort close
			}
		}
	}

	/**
	 * Terminal failure: tear the stream down (closing the input when owned) and report the cause to the
	 * exception handler. Idempotent; a no-op once the stream has ended.
	 */
	private void fail(Throwable cause) {
		if (ended)
			return;
		terminate();
		notifyException(cause);
	}

	private void notifyEndHandler() {
		Handler<Void> h = endHandler;
		if (h != null) {
			try {
				h.handle(null);
			} catch (Throwable ignored) {
				// Swallow: an exception from the end handler must not propagate.
			}
		}
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