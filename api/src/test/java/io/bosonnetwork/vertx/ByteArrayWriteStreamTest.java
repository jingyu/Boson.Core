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

import java.util.Random;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Behavioural tests for {@link ByteArrayWriteStream}. Because {@link ByteArrayWriteStream} is a thin
 * concrete subclass, these tests also exercise the whole {@link AbstractMemoryWriteStream} contract.
 */
@ExtendWith(VertxExtension.class)
public class ByteArrayWriteStreamTest {
	private static byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		new Random(11).nextBytes(b);
		return b;
	}

	@Test
	void constructorRequiresContext() {
		assertThrows(IllegalStateException.class, ByteArrayWriteStream::new);
	}

	@Test
	void collectsAllWritesInOrder(Vertx vertx, VertxTestContext tc) {
		byte[] a = randomBytes(1000);
		byte[] b = randomBytes(2000);
		byte[] expected = Buffer.buffer().appendBytes(a).appendBytes(b).getBytes();
		vertx.runOnContext(v -> {
			ByteArrayWriteStream ws = new ByteArrayWriteStream();
			ws.exceptionHandler(tc::failNow);
			ws.write(Buffer.buffer(a))
					.compose(x -> ws.write(Buffer.buffer(b)))
					.compose(x -> ws.end())
					.compose(x -> ws.getBytes())
					.onComplete(tc.succeeding(bytes -> tc.verify(() -> {
						assertArrayEquals(expected, bytes);
						tc.completeNow();
					})));
		});
	}

	@Test
	void emptyWriteIsAccepted(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> {
			ByteArrayWriteStream ws = new ByteArrayWriteStream();
			ws.exceptionHandler(tc::failNow);
			ws.write(Buffer.buffer())
					.compose(x -> ws.getBytes())
					.onComplete(tc.succeeding(bytes -> tc.verify(() -> {
						assertEquals(0, bytes.length);
						tc.completeNow();
					})));
		});
	}

	@Test
	void handlesLargeData(Vertx vertx, VertxTestContext tc) {
		byte[] big = randomBytes(1 << 20); // 1 MiB
		vertx.runOnContext(v -> {
			ByteArrayWriteStream ws = new ByteArrayWriteStream();
			ws.exceptionHandler(tc::failNow);
			ws.write(Buffer.buffer(big))
					.compose(x -> ws.end())
					.compose(x -> ws.getBytes())
					.onComplete(tc.succeeding(bytes -> tc.verify(() -> {
						assertArrayEquals(big, bytes);
						tc.completeNow();
					})));
		});
	}

	@Test
	void getBytesReturnsIndependentCopies(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> {
			ByteArrayWriteStream ws = new ByteArrayWriteStream();
			ws.exceptionHandler(tc::failNow);
			ws.write(Buffer.buffer(new byte[] { 1, 2, 3 }))
					.compose(x -> ws.getBytes().compose(first -> ws.getBytes().map(second -> new byte[][] { first, second })))
					.onComplete(tc.succeeding(pair -> tc.verify(() -> {
						assertArrayEquals(new byte[] { 1, 2, 3 }, pair[0]);
						assertArrayEquals(new byte[] { 1, 2, 3 }, pair[1]);
						assertFalse(pair[0] == pair[1], "each call returns a fresh copy");
						tc.completeNow();
					})));
		});
	}

	@Test
	void writeAfterEndFails(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> {
			ByteArrayWriteStream ws = new ByteArrayWriteStream();
			ws.end()
					.compose(x -> ws.write(Buffer.buffer("late")))
					.onComplete(tc.failing(err -> tc.verify(() -> {
						assertInstanceOf(IllegalStateException.class, err);
						tc.completeNow();
					})));
		});
	}

	@Test
	void endAfterEndFails(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> {
			ByteArrayWriteStream ws = new ByteArrayWriteStream();
			ws.end()
					.compose(x -> ws.end())
					.onComplete(tc.failing(err -> tc.verify(() -> {
						assertInstanceOf(IllegalStateException.class, err);
						tc.completeNow();
					})));
		});
	}

	@Test
	void writeNullFailsFuture(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> {
			ByteArrayWriteStream ws = new ByteArrayWriteStream();
			ws.write(null).onComplete(tc.failing(err -> tc.verify(() -> {
				assertInstanceOf(NullPointerException.class, err);
				tc.completeNow();
			})));
		});
	}

	@Test
	void writeNullAlsoNotifiesExceptionHandler(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> {
			ByteArrayWriteStream ws = new ByteArrayWriteStream();
			ws.exceptionHandler(err -> tc.verify(() -> {
				assertInstanceOf(NullPointerException.class, err);
				tc.completeNow();
			}));
			ws.write(null);
		});
	}

	@Test
	void writeInternalFailureIsReported(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> {
			ThrowingWriteStream ws = new ThrowingWriteStream();
			// both the write future and the exception handler must observe the failure
			ws.exceptionHandler(err -> tc.verify(() -> assertEquals("store failure", err.getMessage())));
			ws.write(Buffer.buffer("x")).onComplete(tc.failing(err -> tc.verify(() -> {
				assertEquals("store failure", err.getMessage());
				tc.completeNow();
			})));
		});
	}

	@Test
	void getOnContextPropagatesSupplierFailure(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> {
			ThrowingWriteStream ws = new ThrowingWriteStream();
			ws.readFailing().onComplete(tc.failing(err -> tc.verify(() -> {
				assertInstanceOf(IllegalStateException.class, err);
				tc.completeNow();
			})));
		});
	}

	// A write stream whose store always fails, and whose getter supplier always throws.
	private static final class ThrowingWriteStream extends AbstractMemoryWriteStream {
		@Override
		protected void writeInternal(Buffer data) throws Exception {
			throw new Exception("store failure");
		}

		Future<Object> readFailing() {
			return getOnContext(() -> {
				throw new IllegalStateException("supplier failure");
			});
		}
	}

	@Test
	void unboundedQueueSemantics(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> tc.verify(() -> {
			ByteArrayWriteStream ws = new ByteArrayWriteStream();
			assertFalse(ws.writeQueueFull());
			assertSame(ws, ws.setWriteQueueMaxSize(1));
			assertSame(ws, ws.drainHandler(x -> { }));
			assertSame(ws, ws.exceptionHandler(null));
			tc.completeNow();
		}));
	}
}
