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