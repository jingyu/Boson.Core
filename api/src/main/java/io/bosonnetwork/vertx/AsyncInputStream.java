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

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import org.jspecify.annotations.Nullable;

/**
 * A Vert.x {@link ReadStream} that adapts a blocking {@link InputStream}.
 * <p>
 * Each chunk is read in a short {@link Context#executeBlocking(java.util.concurrent.Callable, boolean)
 * executeBlocking} task and the next read is scheduled only when there is outstanding demand, so -
 * unlike a naive adapter that loops on a worker thread for the stream's lifetime - no worker thread is
 * held while the stream is idle or paused. Back-pressure is honored per chunk: {@link #pause()},
 * {@link #resume()} and {@link #fetch(long)} control how many further chunks are read and delivered.
 *
 * <h2>Threading</h2>
 * The stream binds to a Vert.x {@link Context} the first time a data handler is set, and from then on
 * all reads complete, and all handlers are invoked, on that context. As with the built-in Vert.x
 * streams, its methods are expected to be called from that same context.
 *
 * <h2>Resource ownership</h2>
 * The wrapped {@link InputStream} is closed when the stream ends, fails, or {@link #close()} is
 * called - but only if {@code closeInput} was set ({@code true} for the two-argument constructor).
 */
public class AsyncInputStream implements ReadStream<Buffer> {
	private static final int DEFAULT_CHUNK_SIZE = 8192;

	private final Vertx vertx;
	private final InputStream input;
	private final int chunkSize;
	private final boolean closeInput;

	// All mutable state below is confined to {@link #context} once bound.
	private @Nullable Context context;

	private @Nullable Handler<Buffer> dataHandler;
	private @Nullable Handler<Void> endHandler;
	private @Nullable Handler<Throwable> exceptionHandler;

	// Outstanding number of chunks to deliver; Long.MAX_VALUE means "flowing" (unbounded).
	private long demand = Long.MAX_VALUE;
	private boolean readInProgress;
	private boolean closed;
	private boolean inputClosed;

	/**
	 * Creates a new {@code AsyncInputStream} with the default chunk size (8192 bytes) that closes the
	 * wrapped {@link InputStream} when finished.
	 *
	 * @param vertx the Vert.x instance
	 * @param input the input stream to read from
	 */
	public AsyncInputStream(Vertx vertx, InputStream input) {
		this(vertx, input, DEFAULT_CHUNK_SIZE, true);
	}

	/**
	 * Creates a new {@code AsyncInputStream}.
	 *
	 * @param vertx      the Vert.x instance
	 * @param input      the input stream to read from
	 * @param chunkSize  the size of the buffer used for each read (must be {@code > 0})
	 * @param closeInput whether to close the input stream when the stream ends, fails or is closed
	 */
	public AsyncInputStream(Vertx vertx, InputStream input, int chunkSize, boolean closeInput) {
		this.vertx = Objects.requireNonNull(vertx, "vertx");
		this.input = Objects.requireNonNull(input, "input");
		if (chunkSize <= 0)
			throw new IllegalArgumentException("chunkSize must be > 0");

		this.chunkSize = chunkSize;
		this.closeInput = closeInput;
	}

	@Override
	public AsyncInputStream exceptionHandler(@Nullable Handler<Throwable> handler) {
		this.exceptionHandler = handler;
		return this;
	}

	@Override
	public AsyncInputStream handler(@Nullable Handler<Buffer> handler) {
		this.dataHandler = handler;
		if (handler != null && !closed) {
			if (context == null)
				context = vertx.getOrCreateContext();
			doRead();
		}
		return this;
	}

	@Override
	public AsyncInputStream pause() {
		demand = 0L;
		return this;
	}

	@Override
	public AsyncInputStream resume() {
		return fetch(Long.MAX_VALUE);
	}

	@Override
	public AsyncInputStream fetch(long amount) {
		if (amount < 0)
			throw new IllegalArgumentException("amount must be >= 0");
		if (closed)
			return this;

		// saturating add
		demand += amount;
		if (demand < 0)
			demand = Long.MAX_VALUE;

		if (dataHandler != null) {
			if (context == null)
				context = vertx.getOrCreateContext();
			doRead();
		}
		return this;
	}

	@Override
	public AsyncInputStream endHandler(@Nullable Handler<Void> handler) {
		this.endHandler = handler;
		return this;
	}

	/**
	 * Closes the stream, stopping any further reads and (if configured) closing the wrapped
	 * {@link InputStream}. Idempotent. No more data, end or exception events are delivered afterwards.
	 */
	public void close() {
		Context ctx = context;
		if (ctx != null && Vertx.currentContext() != ctx)
			ctx.runOnContext(v -> doClose());
		else
			doClose();
	}

	private void doClose() {
		if (closed)
			return;
		closed = true;
		dataHandler = null;
		// If a blocking read is in flight, defer closing the input to its completion to avoid closing
		// the stream concurrently with a read on the worker thread.
		if (!readInProgress)
			closeInputQuietly();
	}

	private void doRead() {
		if (closed || readInProgress || demand == 0L || dataHandler == null)
			return;

		readInProgress = true;
		byte[] buf = new byte[chunkSize];
		context.executeBlocking(() -> input.read(buf), false).onComplete(ar -> {
			readInProgress = false;

			if (closed) {
				closeInputQuietly();
				return;
			}
			if (ar.failed()) {
				handleException(ar.cause());
				return;
			}

			int len = ar.result();
			if (len < 0) {
				handleEnd();
				return;
			}
			if (len > 0) {
				if (demand != Long.MAX_VALUE)
					demand--;
				Handler<Buffer> handler = dataHandler;
				if (handler != null) {
					try {
						handler.handle(Buffer.buffer(len).appendBytes(buf, 0, len));
					} catch (Throwable t) {
						handleException(t);
						return;
					}
				}
			}

			if (!closed && demand > 0L)
				context.runOnContext(v -> doRead());
		});
	}

	private void handleEnd() {
		if (closed)
			return;
		closed = true;
		dataHandler = null;
		closeInputQuietly();
		Handler<Void> handler = endHandler;
		if (handler != null)
			handler.handle(null);
	}

	private void handleException(Throwable cause) {
		if (closed)
			return;
		closed = true;
		dataHandler = null;
		closeInputQuietly();
		Handler<Throwable> handler = exceptionHandler;
		if (handler != null)
			handler.handle(cause);
	}

	private void closeInputQuietly() {
		if (inputClosed)
			return;
		inputClosed = true;
		if (closeInput) {
			try {
				input.close();
			} catch (IOException ignore) {
				// best-effort close
			}
		}
	}
}