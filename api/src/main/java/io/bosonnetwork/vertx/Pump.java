/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

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
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;

// NOTE: This is a modified version of Vert.x's PipeImpl class.
// Avoid reformatting to preserve diff clarity for easier comparison and
// merging with upstream changes.

/**
 * Pump works like a pipe, pumping data from a {@code ReadStream} to a {@code WriteStream}.
 * <p>
 * This class is a copy of Vert.x's {@code PipeImpl} class with an additional observer handler.
 */
public class Pump<T> implements Pipe<T> {

  private final Promise<Void> result;
  private final ReadStream<T> src;
  private boolean endOnSuccess = true;
  private boolean endOnFailure = true;
  private Handler<T> observer;
  private WriteStream<T> dst;

  private Pump(ReadStream<T> src) {
    this.src = src;
    this.result = Promise.promise();

    // Set handlers now
    src.endHandler(result::tryComplete);
    src.exceptionHandler(result::tryFail);
  }

  /**
   * Create a new {@code Pump} with the given source {@code ReadStream}.
   *
   * @param src the source read stream
   * @param <T> the type of items being pumped
   * @return the pump instance
   */
  public static <T> Pump<T> from(ReadStream<T> src) {
    return new Pump<>(src);
  }

  /**
   * Set to {@code true} to end the destination {@code WriteStream} when the source {@code ReadStream} fails.
   * <p>
   * The default value is {@code true}.
   *
   * @param end {@code true} to end the destination on failure
   * @return a reference to this, so the API can be used fluently
   */
  @Override
  public synchronized Pipe<T> endOnFailure(boolean end) {
    endOnFailure = end;
    return this;
  }

  /**
   * Set to {@code true} to end the destination {@code WriteStream} when the source {@code ReadStream} ends.
   * <p>
   * The default value is {@code true}.
   *
   * @param end {@code true} to end the destination on success
   * @return a reference to this, so the API can be used fluently
   */
  @Override
  public synchronized Pipe<T> endOnSuccess(boolean end) {
    endOnSuccess = end;
    return this;
  }

  /**
   * Set to {@code true} to end the destination {@code WriteStream} when the source {@code ReadStream} ends or fails.
   *
   * @param end {@code true} to end the destination on completion
   * @return a reference to this, so the API can be used fluently
   */
  @Override
  public synchronized Pipe<T> endOnComplete(boolean end) {
    endOnSuccess = end;
    endOnFailure = end;
    return this;
  }

  /**
   * Set an observer {@code Handler} to be notified of the elements pumped.
   *
   * @param observer the observer handler
   * @return a reference to this, so the API can be used fluently
   */
  public synchronized Pipe<T> observer(Handler<T> observer) {
    this.observer = observer;
    return this;
  }

  private void handleWriteResult(AsyncResult<Void> ack) {
    if (ack.failed()) {
      result.tryFail(new WriteException(ack.cause()));
    }
  }

  /**
   * Start pumping to the given destination {@code WriteStream}.
   *
   * @param ws the destination write stream
   * @return a future that completes when the pumping is complete
   */
  @Override
  public Future<Void> to(WriteStream<T> ws) {
    Promise<Void> promise = Promise.promise();
    if (ws == null) {
      throw new NullPointerException();
    }
    synchronized (Pump.this) {
      if (dst != null) {
        throw new IllegalStateException();
      }
      dst = ws;
    }
    Handler<Void> drainHandler = v -> src.resume();
    src.handler(item -> {
      if (observer != null) {
        try {
          observer.handle(item);
        } catch (Throwable t) {
          result.tryFail(t);
          return;
        }
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
   * Close the pump.
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
    private WriteException(Throwable cause) {
      super(cause, true);
    }
  }
}