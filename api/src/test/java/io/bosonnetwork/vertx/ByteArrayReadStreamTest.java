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

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Behavioural tests for {@link ByteArrayReadStream}. Because {@link ByteArrayReadStream} is a thin
 * concrete subclass, these tests also exercise the whole {@link AbstractMemoryReadStream} contract.
 */
@ExtendWith(VertxExtension.class)
public class ByteArrayReadStreamTest {
	private static byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		new Random(42).nextBytes(b);
		return b;
	}

	@Test
	void constructorRequiresContext() {
		// Off any Vert.x context, construction must fail fast rather than fabricate one.
		assertThrows(IllegalStateException.class, () -> new ByteArrayReadStream(new byte[4]));
	}

	@Test
	void validatesArguments(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(16);
		vertx.runOnContext(v -> tc.verify(() -> {
			assertThrows(NullPointerException.class, () -> new ByteArrayReadStream(null));
			assertThrows(IllegalArgumentException.class, () -> new ByteArrayReadStream(data, -1, 4));
			assertThrows(IllegalArgumentException.class, () -> new ByteArrayReadStream(data, 0, -1));
			assertThrows(IllegalArgumentException.class, () -> new ByteArrayReadStream(data, 8, 9));
			tc.completeNow();
		}));
	}

	@Test
	void readsAllContentInOrder(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(4096);
		vertx.runOnContext(v -> {
			Buffer acc = Buffer.buffer();
			// small read buffer forces multiple chunks
			ByteArrayReadStream s = new ByteArrayReadStream(data, 0, data.length, 256);
			s.exceptionHandler(tc::failNow);
			s.endHandler(x -> tc.verify(() -> {
				assertArrayEquals(data, acc.getBytes());
				tc.completeNow();
			}));
			s.handler(acc::appendBuffer);
		});
	}

	@Test
	void exposesOnlyTheRequestedSlice(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(100);
		vertx.runOnContext(v -> {
			Buffer acc = Buffer.buffer();
			ByteArrayReadStream s = new ByteArrayReadStream(data, 10, 30, 7);
			s.exceptionHandler(tc::failNow);
			s.endHandler(x -> tc.verify(() -> {
				assertArrayEquals(Arrays.copyOfRange(data, 10, 40), acc.getBytes());
				tc.completeNow();
			}));
			s.handler(acc::appendBuffer);
		});
	}

	@Test
	void emptyInputEndsImmediatelyWithNoData(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> {
			AtomicInteger chunks = new AtomicInteger();
			ByteArrayReadStream s = new ByteArrayReadStream(new byte[0]);
			s.exceptionHandler(tc::failNow);
			s.endHandler(x -> tc.verify(() -> {
				assertEquals(0, chunks.get(), "no data chunks for empty input");
				tc.completeNow();
			}));
			s.handler(b -> chunks.incrementAndGet());
		});
	}

	@Test
	void defaultChunkSizeDeliversWholeBodyInOneChunk(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(1000); // well under the 32 KiB default
		vertx.runOnContext(v -> {
			AtomicInteger chunks = new AtomicInteger();
			Buffer acc = Buffer.buffer();
			ByteArrayReadStream s = new ByteArrayReadStream(data);
			s.exceptionHandler(tc::failNow);
			s.endHandler(x -> tc.verify(() -> {
				assertEquals(1, chunks.get());
				assertArrayEquals(data, acc.getBytes());
				tc.completeNow();
			}));
			s.handler(b -> {
				chunks.incrementAndGet();
				acc.appendBuffer(b);
			});
		});
	}

	@Test
	void pauseThenResumeDeliversEverything(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(1024);
		vertx.runOnContext(v -> {
			Buffer acc = Buffer.buffer();
			ByteArrayReadStream s = new ByteArrayReadStream(data, 0, data.length, 256);
			s.exceptionHandler(tc::failNow);
			s.endHandler(x -> tc.verify(() -> {
				assertArrayEquals(data, acc.getBytes());
				tc.completeNow();
			}));
			s.pause();
			s.handler(acc::appendBuffer);
			// nothing should flow while paused; resume after a tick
			vertx.setTimer(200, t -> tc.verify(() -> {
				assertEquals(0, acc.length(), "no data must flow while paused");
				s.resume();
			}));
		});
	}

	@Test
	void fetchDeliversExactlyOneChunk(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(1024); // 4 chunks at 256
		vertx.runOnContext(v -> {
			AtomicInteger chunks = new AtomicInteger();
			Buffer acc = Buffer.buffer();
			ByteArrayReadStream s = new ByteArrayReadStream(data, 0, data.length, 256);
			s.exceptionHandler(tc::failNow);
			s.endHandler(x -> tc.verify(() -> {
				assertArrayEquals(data, acc.getBytes());
				tc.completeNow();
			}));
			s.pause();
			s.handler(b -> {
				chunks.incrementAndGet();
				acc.appendBuffer(b);
			});
			s.fetch(1); // amount is an element count: request exactly one chunk
			vertx.setTimer(200, t -> tc.verify(() -> {
				assertEquals(1, chunks.get(), "fetch(1) must deliver exactly one chunk");
				assertEquals(256, acc.length());
				s.resume();
			}));
		});
	}

	@Test
	void fetchWithUnboundedAmountIsSafe(Vertx vertx, VertxTestContext tc) {
		// Long.MAX_VALUE is the conventional "unbounded" value: it must not overflow, and (as the flowing
		// sentinel) it drains the whole source.
		byte[] data = randomBytes(1024); // 4 chunks at 256
		vertx.runOnContext(v -> {
			AtomicInteger chunks = new AtomicInteger();
			Buffer acc = Buffer.buffer();
			ByteArrayReadStream s = new ByteArrayReadStream(data, 0, data.length, 256);
			s.exceptionHandler(tc::failNow);
			s.endHandler(x -> tc.verify(() -> {
				assertEquals(4, chunks.get(), "unbounded fetch drains every chunk");
				assertArrayEquals(data, acc.getBytes());
				tc.completeNow();
			}));
			s.pause();
			s.handler(b -> {
				chunks.incrementAndGet();
				acc.appendBuffer(b);
			});
			s.fetch(Long.MAX_VALUE);
		});
	}

	@Test
	void fetchRejectsNonPositiveAmount(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(64);
		vertx.runOnContext(v -> {
			ByteArrayReadStream s = new ByteArrayReadStream(data);
			s.exceptionHandler(err -> tc.verify(() -> {
				assertInstanceOf(IllegalArgumentException.class, err);
				tc.completeNow();
			}));
			s.pause();
			s.handler(b -> { });
			s.fetch(0);
		});
	}

	@Test
	void dataHandlerExceptionIsRoutedToExceptionHandler(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(64);
		vertx.runOnContext(v -> {
			// big read buffer -> whole body is a single chunk, so the throwing handler fires once
			ByteArrayReadStream s = new ByteArrayReadStream(data, 0, data.length, 4096);
			s.exceptionHandler(err -> tc.verify(() -> {
				assertInstanceOf(IllegalStateException.class, err);
				tc.completeNow();
			}));
			s.handler(b -> {
				throw new IllegalStateException("boom");
			});
		});
	}

	@Test
	void readInternalExceptionIsRoutedToExceptionHandler(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> {
			AbstractMemoryReadStream s = new AbstractMemoryReadStream(0) {
				@Override
				protected Buffer readInternal(int amount) {
					throw new IllegalStateException("read failure");
				}
			};
			s.exceptionHandler(err -> tc.verify(() -> {
				assertInstanceOf(IllegalStateException.class, err);
				tc.completeNow();
			}));
			s.handler(b -> tc.failNow(new AssertionError("no data expected")));
		});
	}

	@Test
	void pipesToWriteStream(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(5000);
		vertx.runOnContext(v -> {
			ByteArrayReadStream src = new ByteArrayReadStream(data, 0, data.length, 256);
			BufferWriteStream dst = new BufferWriteStream();
			src.pipeTo(dst)
					.compose(x -> dst.getBuffer())
					.onComplete(tc.succeeding(buf -> tc.verify(() -> {
						assertArrayEquals(data, buf.getBytes());
						tc.completeNow();
					})));
		});
	}

	@Test
	void fluentMethodsReturnSameStream(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> tc.verify(() -> {
			ByteArrayReadStream s = new ByteArrayReadStream(new byte[4]);
			assertSame(s, s.exceptionHandler(null));
			assertSame(s, s.pause());
			assertSame(s, s.fetch(1));
			assertSame(s, s.resume());
			assertSame(s, s.endHandler(null));
			assertSame(s, s.handler(null));
			tc.completeNow();
		}));
	}

	@Test
	void fetchAfterEndIsANoOp(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(64);
		vertx.runOnContext(v -> {
			AtomicInteger chunks = new AtomicInteger();
			ByteArrayReadStream s = new ByteArrayReadStream(data);
			s.exceptionHandler(tc::failNow);
			s.endHandler(x -> {
				int seen = chunks.get();
				// fetching after the stream ended must do nothing and must not error
				s.fetch(1);
				vertx.setTimer(150, t -> tc.verify(() -> {
					assertEquals(seen, chunks.get(), "no further chunks after end");
					assertTrue(seen >= 1);
					tc.completeNow();
				}));
			});
			s.handler(b -> chunks.incrementAndGet());
		});
	}

	@Test
	void fetchPastTheEndFiresEndHandler(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(200);
		vertx.runOnContext(v -> {
			Buffer acc = Buffer.buffer();
			// the body is a single chunk (4096 >= 200); one fetch delivers it and then observes end
			ByteArrayReadStream s = new ByteArrayReadStream(data, 0, data.length, 4096);
			s.exceptionHandler(tc::failNow);
			s.endHandler(x -> tc.verify(() -> {
				assertArrayEquals(data, acc.getBytes());
				tc.completeNow();
			}));
			s.pause();
			s.handler(acc::appendBuffer);
			s.fetch(2); // delivers the single chunk, then reads again and hits end
		});
	}

	@Test
	void fetchReadInternalExceptionIsRoutedToExceptionHandler(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> {
			AbstractMemoryReadStream s = new AbstractMemoryReadStream(0) {
				@Override
				protected Buffer readInternal(int amount) {
					throw new IllegalStateException("fetch read failure");
				}
			};
			s.exceptionHandler(err -> tc.verify(() -> {
				assertInstanceOf(IllegalStateException.class, err);
				tc.completeNow();
			}));
			s.pause();
			s.handler(b -> tc.failNow(new AssertionError("no data expected")));
			s.fetch(1);
		});
	}

	@Test
	void handlerCanBeClearedThenReset(Vertx vertx, VertxTestContext tc) {
		byte[] data = randomBytes(300);
		vertx.runOnContext(v -> {
			Buffer acc = Buffer.buffer();
			ByteArrayReadStream s = new ByteArrayReadStream(data);
			s.exceptionHandler(tc::failNow);
			s.handler(null); // clearing before any data is a no-op
			assertFalse(acc.length() > 0);
			s.endHandler(x -> tc.verify(() -> {
				assertArrayEquals(data, acc.getBytes());
				tc.completeNow();
			}));
			s.handler(acc::appendBuffer);
		});
	}
}
