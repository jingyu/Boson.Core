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

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.VertxException;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;

/**
 * Pumper that reads data from a {@link ReadStream} and writes it to a {@link WriteStream}.
 * <p>
 * It handles back-pressure by pausing the source when the destination's write queue is full
 * and resuming it when it's drained. It also supports optional byte limits and configurable
 * behavior for closing the destination stream on completion or failure.
 */
public class Pump implements Pipe<Buffer> {
	private final Promise<Void> result;
	private final ReadStream<Buffer> src;
	private boolean endOnSuccess = true;
	private boolean endOnFailure = true;
	private long bytesLimit = 0;
	private long bytesPumped = 0;
	private WriteStream<Buffer> dst;

	/**
	 * Creates a new Pump for the given source stream.
	 *
	 * @param src the source stream to read from
	 */
	public Pump(ReadStream<Buffer> src) {
		this.src = src;
		this.result = Promise.promise();

		// Set handlers now
		src.endHandler(result::tryComplete);
		src.exceptionHandler(result::tryFail);
	}

	/**
	 * Sets a limit on the maximum number of bytes to pump.
	 * If the limit is exceeded, the pump fails with a {@link WriteException}.
	 *
	 * @param bytesLimit the maximum bytes to pump (must be >= 0)
	 * @return a reference to this pump for chaining
	 * @throws IllegalArgumentException if bytesLimit is negative
	 */
	public synchronized Pump bytesLimit(long bytesLimit) {
		if (bytesLimit < 0)
			throw new IllegalArgumentException("bytesLimit must be >= 0");

		this.bytesLimit = bytesLimit;
		return this;
	}

	/**
	 * Configures whether to end the destination stream when the source stream fails.
	 *
	 * @param end true to end the destination on failure, false otherwise
	 * @return a reference to this pump for chaining
	 */
	@Override
	public synchronized Pump endOnFailure(boolean end) {
		endOnFailure = end;
		return this;
	}

	/**
	 * Configures whether to end the destination stream when the source stream completes successfully.
	 *
	 * @param end true to end the destination on success, false otherwise
	 * @return a reference to this pump for chaining
	 */
	@Override
	public synchronized Pump endOnSuccess(boolean end) {
		endOnSuccess = end;
		return this;
	}

	/**
	 * Configures whether to end the destination stream when the source stream completes (success or failure).
	 *
	 * @param end true to end the destination on completion, false otherwise
	 * @return a reference to this pump for chaining
	 */
	@Override
	public synchronized Pump endOnComplete(boolean end) {
		endOnSuccess = end;
		endOnFailure = end;
		return this;
	}

	private void handleWriteResult(AsyncResult<Void> ack) {
		if (ack.failed()) {
			result.tryFail(new WriteException(ack.cause()));
		}
	}

	/**
	 * Starts the pumping process to the specified destination.
	 *
	 * @param ws the destination write stream
	 * @return a future that completes when the pumping is finished
	 * @throws NullPointerException if the destination is null
	 * @throws IllegalStateException if a destination has already been set
	 */
	@Override
	public Future<Void> to(WriteStream<Buffer> ws) {
		Promise<Void> promise = Promise.promise();
		if (ws == null) {
			throw new NullPointerException();
		}
		synchronized (this) {
			if (dst != null) {
				throw new IllegalStateException();
			}
			dst = ws;
		}
		Handler<Void> drainHandler = v -> src.resume();
		src.handler(item -> {
			bytesPumped += item.length();
			if (bytesLimit > 0 && bytesPumped > bytesLimit) {
				result.tryFail(new WriteException("Pumped bytes limit exceeded"));
				return;
			}

			ws.write(item).onComplete(this::handleWriteResult);
			if (ws.writeQueueFull()) {
				src.pause();
				ws.drainHandler(drainHandler);
			}
		});
		src.resume();
		result.future().onComplete(ar -> {
			try {
				src.handler(null);
			} catch (Exception ignore) {
			}
			try {
				src.exceptionHandler(null);
			} catch (Exception ignore) {
			}
			try {
				src.endHandler(null);
			} catch (Exception ignore) {
			}
			if (ar.succeeded()) {
				handleSuccess(promise);
			} else {
				Throwable err = ar.cause();
				if (err instanceof WriteException) {
					src.resume();
					err = err.getCause();
				}
				handleFailure(err, promise);
			}
		});
		return promise.future();
	}

	private void handleSuccess(Promise<Void> promise) {
		if (endOnSuccess) {
			dst.end().onComplete(promise);
		} else {
			promise.complete();
		}
	}

	private void handleFailure(Throwable cause, Promise<Void> completionHandler) {
		if (endOnFailure){
			dst
					.end()
					.transform(ar -> Future.<Void>failedFuture(cause))
					.onComplete(completionHandler);
		} else {
			completionHandler.fail(cause);
		}
	}

	/**
	 * Closes the pump, detaching handlers from the source and destination.
	 * This will fail the result future if it's not already completed.
	 */
	public void close() {
		synchronized (this) {
			src.exceptionHandler(null);
			src.handler(null);
			if (dst != null) {
				dst.drainHandler(null);
				dst.exceptionHandler(null);
			}
		}
		VertxException err = new VertxException("Pipe closed", true);
		if (result.tryFail(err)) {
			src.resume();
		}
	}

	private static class WriteException extends VertxException {
		private static final long serialVersionUID = 5995527496327696671L;

		private WriteException(String message) {
			super(message, true);
		}

		private WriteException(Throwable cause) {
			super(cause, true);
		}
	}
}