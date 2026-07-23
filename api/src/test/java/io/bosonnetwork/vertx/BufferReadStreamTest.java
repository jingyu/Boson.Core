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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Random;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Tests specific to {@link BufferReadStream}; the shared {@link AbstractMemoryReadStream} contract is
 * covered in depth by {@link ByteArrayReadStreamTest}.
 */
@ExtendWith(VertxExtension.class)
public class BufferReadStreamTest {
	private static byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		new Random(7).nextBytes(b);
		return b;
	}

	@Test
	void constructorRequiresContext() {
		assertThrows(IllegalStateException.class, () -> new BufferReadStream(Buffer.buffer(new byte[4])));
	}

	@Test
	void validatesArguments(Vertx vertx, VertxTestContext tc) {
		Buffer data = Buffer.buffer(randomBytes(16));
		vertx.runOnContext(v -> tc.verify(() -> {
			assertThrows(NullPointerException.class, () -> new BufferReadStream(null));
			assertThrows(IllegalArgumentException.class, () -> new BufferReadStream(data, -1, 4));
			assertThrows(IllegalArgumentException.class, () -> new BufferReadStream(data, 0, -1));
			assertThrows(IllegalArgumentException.class, () -> new BufferReadStream(data, 8, 9));
			tc.completeNow();
		}));
	}

	@Test
	void readsWholeBufferInOrder(Vertx vertx, VertxTestContext tc) {
		byte[] bytes = randomBytes(4096);
		Buffer data = Buffer.buffer(bytes);
		vertx.runOnContext(v -> {
			Buffer acc = Buffer.buffer();
			BufferReadStream s = new BufferReadStream(data, 0, data.length(), 300);
			s.exceptionHandler(tc::failNow);
			s.endHandler(x -> tc.verify(() -> {
				assertArrayEquals(bytes, acc.getBytes());
				tc.completeNow();
			}));
			s.handler(acc::appendBuffer);
		});
	}

	@Test
	void exposesOnlyTheRequestedSlice(Vertx vertx, VertxTestContext tc) {
		byte[] bytes = randomBytes(200);
		Buffer data = Buffer.buffer(bytes);
		vertx.runOnContext(v -> {
			Buffer acc = Buffer.buffer();
			BufferReadStream s = new BufferReadStream(data, 50, 75, 16);
			s.exceptionHandler(tc::failNow);
			s.endHandler(x -> tc.verify(() -> {
				assertArrayEquals(Arrays.copyOfRange(bytes, 50, 125), acc.getBytes());
				tc.completeNow();
			}));
			s.handler(acc::appendBuffer);
		});
	}

	@Test
	void emptySliceEndsImmediately(Vertx vertx, VertxTestContext tc) {
		Buffer data = Buffer.buffer(randomBytes(32));
		vertx.runOnContext(v -> {
			BufferReadStream s = new BufferReadStream(data, 10, 0);
			s.exceptionHandler(tc::failNow);
			s.endHandler(x -> tc.completeNow());
			s.handler(b -> tc.failNow(new AssertionError("no data expected for empty slice")));
		});
	}
}
