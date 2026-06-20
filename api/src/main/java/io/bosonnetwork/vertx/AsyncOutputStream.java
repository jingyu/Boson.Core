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
 * A Vert.x {@link WriteStream} that adapts a blocking {@link OutputStream}.
 * <p>
 * Buffers are written one at a time, each in a short
 * {@link Context#executeBlocking(java.util.concurrent.Callable, boolean) executeBlocking} task, so -
 * unlike a naive adapter - no worker thread is held while the stream is idle. Write ordering is
 * preserved (only one write is in flight at a time) and back-pressure is reported through
 * {@link #writeQueueFull()} / {@link #drainHandler(Handler)}: the queue is measured in buffered bytes
 * and bounded by {@link #setWriteQueueMaxSize(int)}.
 *
 * <h2>Threading</h2>
 * The stream binds to a Vert.x {@link Context} on its first write, and from then on all writes
 * complete, and all handlers are invoked, on that context.
 *
 * <h2>Resource ownership</h2>
 * On {@link #end()} the wrapped {@link OutputStream} is flushed and, if {@code closeOutput} was set
 * ({@code true} for the two-argument constructor), closed.
 */
public class AsyncOutputStream implements WriteStream<Buffer> {
	private static final int DEFAULT_MAX_QUEUE_BYTES = 64 * 1024;

	private final Vertx vertx;
	private final OutputStream output;
	private final boolean closeOutput;

	// All mutable state below is confined to {@link #context} once bound.
	private @Nullable Context context;
	private @Nullable Handler<Throwable> exceptionHandler;
	private @Nullable Handler<Void> drainHandler;

	private final Deque<PendingWrite> pending = new ArrayDeque<>();
	private long pendingBytes;
	private int maxQueueBytes = DEFAULT_MAX_QUEUE_BYTES;
	private boolean wasFull;
	private boolean writeInProgress;
	private boolean ending;
	private boolean closed;
	private @Nullable Promise<Void> endPromise;

	private record PendingWrite(Buffer buffer, Promise<Void> promise) {}

	/**
	 * Creates a new {@code AsyncOutputStream} that flushes and closes the wrapped {@link OutputStream}
	 * on {@link #end()}.
	 *
	 * @param vertx  the Vert.x instance
	 * @param output the output stream to write to
	 */
	public AsyncOutputStream(Vertx vertx, OutputStream output) {
		this(vertx, output, true);
	}

	/**
	 * Creates a new {@code AsyncOutputStream}.
	 *
	 * @param vertx       the Vert.x instance
	 * @param output      the output stream to write to
	 * @param closeOutput whether to close the output stream on {@link #end()} (it is always flushed)
	 */
	public AsyncOutputStream(Vertx vertx, OutputStream output, boolean closeOutput) {
		this.vertx = Objects.requireNonNull(vertx, "vertx");
		this.output = Objects.requireNonNull(output, "output");
		this.closeOutput = closeOutput;
	}

	@Override
	public AsyncOutputStream exceptionHandler(@Nullable Handler<Throwable> handler) {
		this.exceptionHandler = handler;
		return this;
	}

	@Override
	public Future<Void> write(Buffer data) {
		Objects.requireNonNull(data, "data");
		Promise<Void> promise = Promise.promise();
		execute(() -> {
			if (closed || ending) {
				promise.fail(new IllegalStateException("Stream is closed"));
				return;
			}
			pending.add(new PendingWrite(data, promise));
			pendingBytes += data.length();
			pump();
		});
		return promise.future();
	}

	@Override
	public Future<Void> end() {
		Promise<Void> promise = Promise.promise();
		execute(() -> {
			if (closed) {
				promise.complete();
				return;
			}
			if (ending) {
				promise.fail(new IllegalStateException("Stream is already ending"));
				return;
			}
			ending = true;
			endPromise = promise;
			if (!writeInProgress && pending.isEmpty())
				finish();
		});
		return promise.future();
	}

	@Override
	public AsyncOutputStream setWriteQueueMaxSize(int maxSize) {
		if (maxSize < 1)
			throw new IllegalArgumentException("maxSize must be >= 1");
		this.maxQueueBytes = maxSize;
		return this;
	}

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

	private void execute(Runnable action) {
		Context ctx = this.context;
		if (ctx == null) {
			ctx = vertx.getOrCreateContext();
			this.context = ctx;
		}
		if (Vertx.currentContext() == ctx)
			action.run();
		else
			ctx.runOnContext(v -> action.run());
	}

	private void pump() {
		if (writeInProgress || pending.isEmpty())
			return;

		PendingWrite w = pending.poll();
		pendingBytes -= w.buffer().length();
		writeInProgress = true;
		byte[] bytes = w.buffer().getBytes();
		Context ctx = Objects.requireNonNull(context, "context");
		ctx.executeBlocking(() -> {
			output.write(bytes);
			return null;
		}, false).onComplete(ar -> {
			writeInProgress = false;
			if (ar.failed()) {
				w.promise().fail(ar.cause());
				fail(ar.cause());
				return;
			}

			w.promise().complete();
			callDrainIfNeeded();

			if (!pending.isEmpty())
				pump();
			else if (ending)
				finish();
		});
	}

	private void callDrainIfNeeded() {
		if (wasFull && drainHandler != null && pendingBytes <= maxQueueBytes / 2) {
			wasFull = false;
			//noinspection ConstantConditions
			drainHandler.handle(null);
		}
	}

	private void finish() {
		Context ctx = Objects.requireNonNull(context, "context");
		ctx.executeBlocking(() -> {
			output.flush();
			if (closeOutput)
				output.close();
			return null;
		}, false).onComplete(ar -> {
			closed = true;
			Objects.requireNonNull(endPromise, "endPromise");
			if (ar.failed())
				endPromise.fail(ar.cause());
			else
				endPromise.complete();
		});
	}

	private void fail(Throwable cause) {
		if (closed)
			return;
		closed = true;

		PendingWrite w;
		while ((w = pending.poll()) != null)
			w.promise().fail(cause);
		pendingBytes = 0;

		if (closeOutput) {
			try {
				output.close();
			} catch (IOException ignore) {
				// best-effort close
			}
		}

		if (ending && endPromise != null)
			endPromise.fail(cause);

		Handler<Throwable> handler = exceptionHandler;
		if (handler != null)
			handler.handle(cause);
	}
}