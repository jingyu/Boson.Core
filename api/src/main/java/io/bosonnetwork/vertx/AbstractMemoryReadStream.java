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

import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.jspecify.annotations.Nullable;

/**
 * Base class for in-memory {@link ReadStream} implementations that emit a fixed body of bytes,
 * chunk by chunk, following the Vert.x read-stream contract.
 * <p>
 * The stream captures the Vert.x {@link Context} that is current when it is constructed and produces
 * data on that context. Each chunk is delivered on its own {@code runOnContext} tick, so a long body
 * does not monopolise the event loop. In the default flowing mode (a {@link #handler(Handler) data
 * handler} is set and the stream is not paused) chunks are emitted continuously until the source is
 * drained, at which point the {@link #endHandler(Handler) end handler} fires exactly once.
 * <p>
 * Back-pressure follows the standard contract:
 * <ul>
 *   <li>{@link #pause()} stops the producer;</li>
 *   <li>{@link #resume()} restarts continuous delivery;</li>
 *   <li>{@link #fetch(long)} requests a bounded number of further chunks while paused.</li>
 * </ul>
 * Standard {@link #pipe()}/{@code pipeTo} support is inherited from {@link ReadStream}.
 * <p>
 * Subclasses supply the body by implementing {@link #readInternal(int)}. Instances must be created on
 * a Vert.x context; constructing one off a context throws {@link IllegalStateException}.
 */
public abstract class AbstractMemoryReadStream implements ReadStream<Buffer> {
	private final static int DEFAULT_READ_BUFFER_SIZE = 32 * 1024;

	private final Context context;
	private final int readBufferSize;

	private @Nullable Handler<Buffer> dataHandler;
	private @Nullable Handler<Void> endHandler;
	private @Nullable Handler<Throwable> exceptionHandler;

	// Outstanding number of chunks to deliver: Long.MAX_VALUE means "flowing" (unbounded), 0 means
	// paused, a finite value is the number of further chunks a fetch(long) has requested. Written by
	// pause()/resume() (hence volatile) and adjusted on the context thread while reading.
	private volatile long demand = Long.MAX_VALUE;
	/** Whether the stream has reached a terminal state (end of source or failure). */
	private boolean ended;

	/**
	 * Creates a new stream bound to the {@linkplain Vertx#currentContext() current Vert.x context}.
	 *
	 * @param readBufferSize the maximum number of bytes to read per chunk; values {@code <= 0} select a
	 *                       default of 32 KiB
	 * @throws IllegalStateException if there is no current Vert.x context
	 */
	public AbstractMemoryReadStream(int readBufferSize) {
		Context current = Vertx.currentContext();
		if (current == null)
			throw new IllegalStateException("Must be created on a Vert.x context");

		this.context = current;
		this.readBufferSize = readBufferSize > 0 ? readBufferSize : DEFAULT_READ_BUFFER_SIZE;
	}

	@Override
	public ReadStream<Buffer> exceptionHandler(@Nullable Handler<Throwable> handler) {
		this.exceptionHandler = handler;
		return this;
	}

	@Override
	public ReadStream<Buffer> handler(@Nullable Handler<Buffer> handler) {
		this.dataHandler = handler;
		if (handler != null)
			doRead(); // Kick off the producer. If the stream already ended this is a no-op.

		return this;
	}

	@Override
	public ReadStream<Buffer> pause() {
		demand = 0L;
		return this;
	}

	@Override
	public ReadStream<Buffer> resume() {
		demand = Long.MAX_VALUE;
		doRead();
		return this;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Adds {@code amount} to the outstanding demand and resumes reading until that many further chunks
	 * have been delivered (or the source is drained), each chunk carrying up to the configured read buffer
	 * size. If the stream is already flowing the call has no effect. Reports {@link IllegalArgumentException}
	 * to the exception handler if {@code amount <= 0}.
	 */
	@Override
	public ReadStream<Buffer> fetch(long amount) {
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
	public ReadStream<Buffer> endHandler(@Nullable Handler<Void> endHandler) {
		this.endHandler = endHandler;
		return this;
	}

	/**
	 * Reads the next chunk from the backing source. Always invoked on the stream's context thread, so
	 * implementations need no synchronization.
	 *
	 * @param amount the maximum number of bytes to return in this chunk
	 * @return the next chunk (never empty), or {@code null} to signal that the source is drained
	 */
	protected abstract @Nullable Buffer readInternal(int amount);

	/**
	 * Producer pump: emit the next chunk (or signal end) on the context thread. Paced by
	 * {@code runOnContext} so each chunk is delivered on its own tick, and self-rescheduling while there
	 * is outstanding demand. Stops when the stream is paused ({@code demand == 0}) or ended, and is
	 * re-armed by {@link #resume()} and {@link #fetch(long)} when demand returns.
	 */
	private void doRead() {
		context.runOnContext(v -> {
			if (ended || demand == 0L)
				return;

			Handler<Buffer> h = this.dataHandler;
			if (h == null)
				return;

			Buffer buf;
			try {
				buf = readInternal(readBufferSize);
			} catch (Throwable t) {
				fail(t);
				return;
			}

			if (buf == null) { // source drained
				terminate();
				notifyEndHandler();
				return;
			}

			if (demand != Long.MAX_VALUE)
				demand--;

			if (deliver(buf)) // continue only if delivery succeeded and the stream is still live
				doRead();
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

	/** Moves the stream to its terminal state. Idempotent. */
	private void terminate() {
		ended = true;
	}

	/**
	 * Terminal failure: end the stream and report the cause to the exception handler. Idempotent; a no-op
	 * once the stream has ended.
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