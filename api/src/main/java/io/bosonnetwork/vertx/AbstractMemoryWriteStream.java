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

import java.util.function.Supplier;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.WriteStream;
import org.jspecify.annotations.Nullable;

/**
 * Base class for in-memory {@link WriteStream} implementations that collect the written
 * {@link Buffer} data into an internal store.
 * <p>
 * The stream captures the Vert.x {@link Context} that is current when it is constructed, and every
 * mutating operation ({@link #write(Buffer)}, {@link #end()}) and every collected-data read is
 * marshalled onto that context. As a result all writes are applied in submission order and there is
 * no lock or shared mutable state visible across threads. Because the store is unbounded, the stream
 * is never full: {@link #writeQueueFull()} always returns {@code false}, {@link #setWriteQueueMaxSize(int)}
 * is a no-op, and the {@link #drainHandler(Handler) drain handler} is never invoked.
 * <p>
 * Subclasses supply the backing store by implementing {@link #writeInternal(Buffer)} and expose an
 * accessor for the collected bytes, typically built on {@link #getOnContext(Supplier)} so the read
 * observes the fully written result.
 * <p>
 * Instances must be created on a Vert.x context; constructing one off a context throws
 * {@link IllegalStateException}.
 */
public abstract class AbstractMemoryWriteStream implements WriteStream<Buffer> {
	private final Context context;
	private @Nullable Handler<Throwable> exceptionHandler;
	private @Nullable Handler<Void> drainHandler;

	/** Whether {@link #end()} has been called. */
	private boolean ended;

	/**
	 * Creates a new stream bound to the {@linkplain Vertx#currentContext() current Vert.x context}.
	 *
	 * @throws IllegalStateException if there is no current Vert.x context
	 */
	public AbstractMemoryWriteStream() {
		Context current = Vertx.currentContext();
		if (current == null)
			throw new IllegalStateException("Must be created on a Vert.x context");

		this.context = current;
	}

	@Override
	public WriteStream<Buffer> exceptionHandler(@Nullable Handler<Throwable> handler) {
		exceptionHandler = handler;
		return this;
	}

	/**
	 * Appends the given data to the backing store. Always invoked on the stream's context thread, so
	 * implementations need no synchronization.
	 *
	 * @param data the data to append
	 * @throws Exception if the data cannot be stored; the failure is reported through the returned
	 *                   write future and the {@link #exceptionHandler(Handler) exception handler}
	 */
	protected abstract void writeInternal(Buffer data) throws Exception;

	/**
	 * {@inheritDoc}
	 * <p>
	 * The write is applied asynchronously on the stream's context. The returned future fails with an
	 * {@link IllegalStateException} if the stream has already been {@linkplain #end() ended}, or with a
	 * {@link NullPointerException} if {@code data} is {@code null}.
	 */
	@Override
	public Future<Void> write(Buffer data) {
		Promise<Void> promise = Promise.promise();
		context.runOnContext(v -> {
			if (ended) {
				promise.tryFail(new IllegalStateException("Stream is already ended"));
				return;
			}

			if (data == null) {
				// Notify before resolving the write future, so a caller awaiting it already observes the
				// exception-handler side effect.
				Throwable err = new NullPointerException("data is null");
				notifyException(err);
				promise.tryFail(err);
				return;
			}

			try {
				writeInternal(data);
				promise.tryComplete();
			} catch (Throwable t) {
				notifyException(t);
				promise.tryFail(t);
			}
		});

		return promise.future();
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Marks the stream as ended so that any subsequent {@link #write(Buffer)} fails. {@code end()} is
	 * idempotent: calling it more than once has no additional effect and every returned future completes.
	 */
	@Override
	public Future<Void> end() {
		Promise<Void> promise = Promise.promise();
		context.runOnContext(v -> {
			ended = true;
			promise.tryComplete();
		});
		return promise.future();
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * No-op: the in-memory store is unbounded and never applies back-pressure.
	 */
	@Override
	public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize) {
		return this;
	}

	/**
	 * {@inheritDoc}
	 *
	 * @return always {@code false}; the in-memory store is unbounded
	 */
	@Override
	public boolean writeQueueFull() {
		return false;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * The handler is retained for API compatibility but is never invoked, since the stream is never full.
	 */
	@Override
	public WriteStream<Buffer> drainHandler(@Nullable Handler<Void> handler) {
		this.drainHandler = handler;
		return this;
	}

	/**
	 * Reads a value derived from the backing store on the stream's context thread, so the read is
	 * ordered after every write submitted before this call and its result is safely published to the
	 * caller. Subclasses use this to implement their collected-data accessors.
	 * <p>
	 * For a stable snapshot of everything written, complete the {@link #end()} (or last
	 * {@link #write(Buffer)}) future before, or when, reading.
	 *
	 * @param supplier computes the value on the context thread from the backing store
	 * @param <T> the type of the produced value
	 * @return a future completed on the context with the supplier's result, or failed if it throws
	 */
	protected <T> Future<T> getOnContext(Supplier<T> supplier) {
		Promise<T> promise = Promise.promise();
		context.runOnContext(v -> {
			try {
				promise.complete(supplier.get());
			} catch (Throwable t) {
				promise.fail(t);
			}
		});
		return promise.future();
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
