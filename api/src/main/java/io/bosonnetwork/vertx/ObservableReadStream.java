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

import io.vertx.core.Handler;
import io.vertx.core.streams.ReadStream;

import org.jspecify.annotations.Nullable;

/**
 * A ReadStream wrapper that observes each element before forwarding it.
 * <p>
 * If the {@code observeHandler} throws an exception:
 * - the stream is paused
 * - no further elements are forwarded
 * - the exception handler is invoked
 * </p>
 * Note: the underlying stream may still invoke {@code endHandler}
 * after termination. Consumers should treat {@code exceptionHandler}
 * as the authoritative failure signal.
 * <p>
 * A terminated stream stays paused and refuses to be restarted, so that no further element can
 * reach a consumer that has already failed. Whoever owns the wrapper releases the underlying stream
 * with {@link #close()}, which detaches this wrapper and hands the stream back to its owner.
 */
public class ObservableReadStream<T> implements ReadStream<T> {
	private final ReadStream<T> delegate;
	private final @Nullable Handler<T> observeHandler;
	private volatile boolean terminated;
	private volatile boolean ended;
	private volatile boolean closed;
	private @Nullable Handler<Throwable> exceptionHandler;
	private @Nullable Handler<Void> endHandler;

	/**
	 * Constructs an ObservableReadStream that wraps a given ReadStream and observes each element
	 * before forwarding it, using the provided observeHandler.
	 *
	 * @param delegate the underlying ReadStream to wrap and delegate operations to
	 * @param observeHandler the handler invoked to observe each element before it is forwarded
	 *                        to the final consumer; if this handler throws an exception, the stream
	 *                        is paused, no further elements are forwarded, and the exception
	 *                        handler (if set) is invoked
	 */
	public ObservableReadStream(ReadStream<T> delegate, @Nullable Handler<T> observeHandler) {
		this.delegate = delegate;
		this.observeHandler = observeHandler;
	}

	@Override
	public ObservableReadStream<T> exceptionHandler(@Nullable Handler<Throwable> handler) {
		exceptionHandler = handler;
		delegate.exceptionHandler(handler);
		return this;
	}

	@Override
	public ObservableReadStream<T> handler(@Nullable Handler<T> handler) {
		// Unsetting the handler is always allowed: it stops delivery rather than resuming it, and a
		// pipe unsets it as part of its own cleanup.
		if (handler == null) {
			delegate.handler(null);
			return this;
		}

		if (terminated || closed)
			return this;

		delegate.handler(element -> {
			if (observeHandler != null) {
				try {
					observeHandler.handle(element);
				} catch (Throwable t) {
					delegate.pause();
					terminated = true;
					if (exceptionHandler != null)
						exceptionHandler.handle(t);

					return;
				}
 			}

			handler.handle(element);
		});

		return this;
	}

	@Override
	public ObservableReadStream<T> pause() {
		delegate.pause();
		return this;
	}

	@Override
	public ObservableReadStream<T> resume() {
		if (!terminated && !closed)
			delegate.resume();
		return this;
	}

	@Override
	public ObservableReadStream<T> fetch(long amount) {
		if (!terminated && !closed)
			delegate.fetch(amount);
		return this;
	}

	@Override
	public ObservableReadStream<T> endHandler(@Nullable Handler<Void> endHandler) {
		this.endHandler = endHandler;
		// Wrapped rather than passed through, so that the wrapper knows whether the stream has
		// ended - close() must not resume a stream that is already over.
		delegate.endHandler(v -> {
			ended = true;
			Handler<Void> handler = this.endHandler;
			if (handler != null)
				handler.handle(v);
		});
		return this;
	}

	/**
	 * Detaches this wrapper from the underlying stream and hands the stream back to its owner.
	 * <p>
	 * The handlers this wrapper installed are removed and the stream is resumed, unless it has
	 * already ended. What is left is a flowing stream with no handler, whose remaining elements are
	 * discarded and whose end still arrives - which for an HTTP request body is what releases the
	 * connection, and for a peer's response is what lets the client reuse it.
	 * <p>
	 * This is the way out of a terminated stream. When the observer throws, the stream is paused and
	 * refuses to resume, so that nothing further reaches the failed consumer; the pipe's own cleanup
	 * cannot release it, and without this neither can anyone else. Whoever created the wrapper calls
	 * this on its failure path, and then decides what the stream is for: reading the remainder away,
	 * as here, or closing the resource underneath it.
	 * <p>
	 * Wrappers nest - one component measures a stream that another component has already wrapped -
	 * and closing one closes those beneath it. Otherwise the stream would be released only as far as
	 * the next wrapper down, which may itself be terminated and refusing to resume.
	 * <p>
	 * Calling it more than once does nothing. Modelled on {@code Pipe.close()}.
	 */
	public void close() {
		if (closed)
			return;

		closed = true;
		endHandler = null;
		exceptionHandler = null;
		delegate.handler(null);
		delegate.exceptionHandler(null);
		delegate.endHandler(null);

		if (delegate instanceof ObservableReadStream<T> wrapped)
			wrapped.close();
		else if (!ended)
			delegate.resume();
	}
}