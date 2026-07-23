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
 *   <li>{@link #fetch(long)} delivers a single further chunk of up to {@code min(amount, readBufferSize)}
 *       bytes while paused (the amount is a byte budget, not an element count).</li>
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

	private volatile boolean pause;
	/** Whether the end of the stream has been reached and the end handler notified. */
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
		pause = true;
		return this;
	}

	@Override
	public ReadStream<Buffer> resume() {
		pause = false;
		doRead();
		return this;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Delivers at most one further chunk, of up to {@code min(amount, readBufferSize)} bytes, or fires the
	 * {@link #endHandler(Handler) end handler} if the source is already drained. The {@code amount} is
	 * treated as a byte budget rather than an element count and is clamped to the read buffer size, so the
	 * conventional unbounded request {@link Long#MAX_VALUE} is safe. Reports {@link IllegalArgumentException}
	 * to the exception handler if {@code amount <= 0}.
	 */
	@Override
	public ReadStream<Buffer> fetch(long amount) {
		context.runOnContext(v -> {
			if (ended)
				return;

			if (amount <= 0) {
				notifyException(new IllegalArgumentException("fetch amount must be > 0"));
				return;
			}

			Handler<Buffer> h = this.dataHandler;
			if (h != null) {
				try {
					// Clamp to the read buffer size: this bounds each delivered chunk and, crucially,
					// avoids the (int) overflow when amount is Long.MAX_VALUE (the conventional
					// "unbounded" value), which would otherwise wrap to a negative length.
					int n = (int) Math.min(amount, readBufferSize);
					Buffer buf = readInternal(n);
					if (buf != null) {
						notifyDataHandler(buf);
					} else {
						ended = true;
						notifyEndHandler();
					}
				} catch (Throwable t) {
					notifyException(t);
				}
			}
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
	 * {@code runOnContext} so each chunk is delivered on its own tick, and self-rescheduling until the
	 * source is drained. Stops when {@link #pause()} is set and is re-armed by {@link #resume()} when
	 * demand returns.
	 */
	private void doRead() {
		context.runOnContext(v -> {
			if (ended || pause)
				return;

			Handler<Buffer> h = this.dataHandler;
			if (h != null) {
				try {
					Buffer buf = readInternal(readBufferSize);
					if (buf != null) {
						notifyDataHandler(buf);
						doRead();
					} else {
						ended = true;
						notifyEndHandler();
					}
				} catch (Throwable t) {
					notifyException(t);
				}
			}
		});
	}

	private void notifyDataHandler(Buffer data) {
		Handler<Buffer> h = dataHandler;
		if (h != null) {
			try {
				h.handle(data);
			} catch (Throwable t) {
				// A failure from the downstream data handler is surfaced through the exception handler.
				notifyException(t);
			}
		}
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