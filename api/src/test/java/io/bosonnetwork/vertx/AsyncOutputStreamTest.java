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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Behavioural tests for {@link AsyncOutputStream}, covering ordered delivery, resource ownership,
 * back-pressure/drain, lifecycle guards, and error propagation.
 */
@ExtendWith(VertxExtension.class)
public class AsyncOutputStreamTest {
	// A ByteArrayOutputStream that records whether it was closed.
	private static class TrackingOutputStream extends ByteArrayOutputStream {
		final AtomicBoolean closed = new AtomicBoolean();

		@Override
		public void close() {
			closed.set(true);
		}
	}

	private static byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		new Random(42).nextBytes(b);
		return b;
	}

	@Test
	void constructorRequiresContext() {
		// Off any Vert.x context (the test body runs on the JUnit thread) construction must fail.
		assertThrows(IllegalStateException.class, () -> new AsyncOutputStream(new ByteArrayOutputStream()));
	}

	@Test
	void writesAllDataInOrderAndCloses(Vertx vertx, VertxTestContext tc) {
		TrackingOutputStream out = new TrackingOutputStream();

		vertx.runOnContext(v -> {
			AsyncOutputStream stream = new AsyncOutputStream(out, true);
			stream.exceptionHandler(tc::failNow);
			stream.write(Buffer.buffer("Hello, "))
					.compose(x -> stream.write(Buffer.buffer("Ion ")))
					.compose(x -> stream.write(Buffer.buffer("Store!")))
					.compose(x -> stream.end())
					.onComplete(tc.succeeding(x -> tc.verify(() -> {
						assertEquals("Hello, Ion Store!", out.toString(StandardCharsets.UTF_8));
						assertTrue(out.closed.get(), "output should be closed on end");
						tc.completeNow();
					})));
		});
	}

	@Test
	void doesNotCloseOutputWhenConfigured(Vertx vertx, VertxTestContext tc) {
		TrackingOutputStream out = new TrackingOutputStream();

		vertx.runOnContext(v -> {
			AsyncOutputStream stream = new AsyncOutputStream(out, false);
			stream.exceptionHandler(tc::failNow);
			stream.write(Buffer.buffer("data"))
					.compose(x -> stream.end())
					.onComplete(tc.succeeding(x -> tc.verify(() -> {
						assertEquals("data", out.toString(StandardCharsets.UTF_8));
						assertFalse(out.closed.get(), "output should not be closed when closeOutput=false");
						tc.completeNow();
					})));
		});
	}

	@Test
	void emptyWriteIsAccepted(Vertx vertx, VertxTestContext tc) {
		TrackingOutputStream out = new TrackingOutputStream();

		vertx.runOnContext(v -> {
			AsyncOutputStream stream = new AsyncOutputStream(out, true);
			stream.exceptionHandler(tc::failNow);
			stream.write(Buffer.buffer())
					.compose(x -> stream.end())
					.onComplete(tc.succeeding(x -> tc.verify(() -> {
						assertEquals(0, out.size());
						tc.completeNow();
					})));
		});
	}

	@Test
	void pipeFromAsyncInputStreamRoundTrips(Vertx vertx, VertxTestContext tc) {
		// Piping completes the last write's future, whose handler synchronously calls end(): a regression
		// guard for the double-finish (double flush/close) hazard.
		byte[] data = randomBytes(4096);
		TrackingOutputStream out = new TrackingOutputStream();

		vertx.runOnContext(v -> {
			AsyncInputStream in = new AsyncInputStream(new ByteArrayInputStream(data), 256, true);
			AsyncOutputStream sink = new AsyncOutputStream(out, true);
			in.pipeTo(sink).onComplete(tc.succeeding(x -> tc.verify(() -> {
				assertArrayEquals(data, out.toByteArray());
				assertTrue(out.closed.get(), "output should be closed once the pipe completes");
				tc.completeNow();
			})));
		});
	}

	@Test
	void writeAfterEndFails(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> {
			AsyncOutputStream stream = new AsyncOutputStream(new ByteArrayOutputStream(), false);
			stream.end()
					.compose(x -> stream.write(Buffer.buffer("late")))
					.onComplete(tc.failing(err -> tc.verify(() -> {
						assertInstanceOf(IllegalStateException.class, err);
						tc.completeNow();
					})));
		});
	}

	@Test
	void endAfterEndFails(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> {
			AsyncOutputStream stream = new AsyncOutputStream(new ByteArrayOutputStream(), false);
			stream.end()
					.compose(x -> stream.end())
					.onComplete(tc.failing(err -> tc.verify(() -> {
						assertInstanceOf(IllegalStateException.class, err);
						tc.completeNow();
					})));
		});
	}

	@Test
	void writeNullFailsFutureAndNotifiesExceptionHandler(Vertx vertx, VertxTestContext tc) {
		AtomicBoolean notified = new AtomicBoolean();
		vertx.runOnContext(v -> {
			AsyncOutputStream stream = new AsyncOutputStream(new ByteArrayOutputStream(), false);
			stream.exceptionHandler(err -> tc.verify(() -> {
				assertInstanceOf(NullPointerException.class, err);
				notified.set(true);
			}));
			stream.write(null).onComplete(tc.failing(err -> tc.verify(() -> {
				assertInstanceOf(NullPointerException.class, err);
				assertTrue(notified.get(), "exception handler must also be notified");
				tc.completeNow();
			})));
		});
	}

	@Test
	void writeFailureIsReportedAndClosesOwnedOutput(Vertx vertx, VertxTestContext tc) {
		AtomicBoolean closed = new AtomicBoolean();
		// An output whose write() always fails; closeOutput=true so it must still be closed on failure.
		OutputStream out = new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				throw new IOException("write boom");
			}

			@Override
			public void write(byte[] b) throws IOException {
				throw new IOException("write boom");
			}

			@Override
			public void close() {
				closed.set(true);
			}
		};

		vertx.runOnContext(v -> {
			AsyncOutputStream stream = new AsyncOutputStream(out, true);
			stream.exceptionHandler(err -> tc.verify(() -> assertEquals("write boom", err.getMessage())));
			stream.write(Buffer.buffer("x")).onComplete(tc.failing(err -> tc.verify(() -> {
				assertEquals("write boom", err.getMessage());
				assertTrue(closed.get(), "owned output must be closed on write failure");
				tc.completeNow();
			})));
		});
	}

	@Test
	void flushFailureFailsEndFuture(Vertx vertx, VertxTestContext tc) {
		// A working store whose flush() fails; end() must surface that failure.
		OutputStream out = new ByteArrayOutputStream() {
			@Override
			public void flush() throws IOException {
				throw new IOException("flush boom");
			}
		};

		vertx.runOnContext(v -> {
			AsyncOutputStream stream = new AsyncOutputStream(out, false);
			stream.write(Buffer.buffer("data"))
					.compose(x -> stream.end())
					.onComplete(tc.failing(err -> tc.verify(() -> {
						assertEquals("flush boom", err.getMessage());
						tc.completeNow();
					})));
		});
	}

	@Test
	void backPressureReportsFullAndDrains(Vertx vertx, VertxTestContext tc) {
		// Block the first write on a worker thread so subsequent writes queue up and the queue fills.
		CountDownLatch gate = new CountDownLatch(1);
		AtomicBoolean gated = new AtomicBoolean(true);
		ByteArrayOutputStream sink = new ByteArrayOutputStream();
		OutputStream out = new OutputStream() {
			@Override
			public void write(int b) {
				sink.write(b);
			}

			@Override
			public void write(byte[] b) throws IOException {
				if (gated.getAndSet(false)) {
					try {
						gate.await();
					} catch (InterruptedException e) {
						throw new IOException(e);
					}
				}
				sink.write(b);
			}
		};

		vertx.runOnContext(v -> {
			AsyncOutputStream stream = new AsyncOutputStream(out, false);
			stream.exceptionHandler(tc::failNow);
			stream.setWriteQueueMaxSize(100);

			stream.write(Buffer.buffer(new byte[50])); // dispatched, blocks on the gate
			stream.write(Buffer.buffer(new byte[60])); // queued
			stream.write(Buffer.buffer(new byte[60])); // queued -> 120 buffered bytes

			// After the enqueues settle, the queue is full; register drain and release the blocked write.
			vertx.setTimer(100, t -> {
				tc.verify(() -> assertTrue(stream.writeQueueFull(), "queue should be full at 120 > 100"));
				stream.drainHandler(d -> tc.verify(() -> {
					assertFalse(stream.writeQueueFull(), "queue drained below the bound");
					stream.end().onComplete(tc.succeeding(x -> tc.verify(() -> {
						assertEquals(170, sink.size(), "all buffered bytes are written");
						tc.completeNow();
					})));
				}));
				gate.countDown();
			});
		});
	}

	@Test
	void setWriteQueueMaxSizeRejectsInvalid(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> tc.verify(() -> {
			AsyncOutputStream stream = new AsyncOutputStream(new ByteArrayOutputStream(), false);
			assertThrows(IllegalArgumentException.class, () -> stream.setWriteQueueMaxSize(0));
			tc.completeNow();
		}));
	}

	@Test
	void fluentMethodsReturnSameStream(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> tc.verify(() -> {
			AsyncOutputStream stream = new AsyncOutputStream(new ByteArrayOutputStream(), false);
			assertSame(stream, stream.exceptionHandler(null));
			assertSame(stream, stream.setWriteQueueMaxSize(1));
			assertSame(stream, stream.drainHandler(null));
			tc.completeNow();
		}));
	}
}
