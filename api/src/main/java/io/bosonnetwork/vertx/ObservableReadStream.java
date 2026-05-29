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
 */
public class ObservableReadStream<T> implements ReadStream<T> {
	private final ReadStream<T> delegate;
	private final Handler<T> observeHandler;
	private volatile boolean terminated;
	private Handler<Throwable> exceptionHandler;

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
	public ObservableReadStream(ReadStream<T> delegate, Handler<T> observeHandler) {
		this.delegate = delegate;
		this.observeHandler = observeHandler;
	}

	@Override
	public ObservableReadStream<T> exceptionHandler(Handler<Throwable> handler) {
		exceptionHandler = handler;
		delegate.exceptionHandler(handler);
		return this;
	}

	@Override
	public ObservableReadStream<T> handler(Handler<T> handler) {
		if (terminated)
			return this;

		if (handler == null) {
			delegate.handler(null);
			return this;
		}

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
		if (!terminated)
			delegate.resume();
		return this;
	}

	@Override
	public ObservableReadStream<T> fetch(long amount) {
		delegate.fetch(amount);
		return this;
	}

	@Override
	public ObservableReadStream<T> endHandler(Handler<Void> endHandler) {
		delegate.endHandler(endHandler);
		return this;
	}
}