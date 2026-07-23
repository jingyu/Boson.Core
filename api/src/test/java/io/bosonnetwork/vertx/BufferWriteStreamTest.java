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

import java.util.Random;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Tests specific to {@link BufferWriteStream}; the shared {@link AbstractMemoryWriteStream} contract is
 * covered in depth by {@link ByteArrayWriteStreamTest}.
 */
@ExtendWith(VertxExtension.class)
public class BufferWriteStreamTest {
	private static byte[] randomBytes(int n) {
		byte[] b = new byte[n];
		new Random(13).nextBytes(b);
		return b;
	}

	@Test
	void constructorRequiresContext() {
		assertThrows(IllegalStateException.class, BufferWriteStream::new);
	}

	@Test
	void collectsWritesIntoBuffer(Vertx vertx, VertxTestContext tc) {
		byte[] a = randomBytes(800);
		byte[] b = randomBytes(1200);
		byte[] expected = Buffer.buffer().appendBytes(a).appendBytes(b).getBytes();
		vertx.runOnContext(v -> {
			BufferWriteStream ws = new BufferWriteStream();
			ws.exceptionHandler(tc::failNow);
			ws.write(Buffer.buffer(a))
					.compose(x -> ws.write(Buffer.buffer(b)))
					.compose(x -> ws.end())
					.compose(x -> ws.getBuffer())
					.onComplete(tc.succeeding(buf -> tc.verify(() -> {
						assertArrayEquals(expected, buf.getBytes());
						tc.completeNow();
					})));
		});
	}

	@Test
	void getBufferOnEmptyStreamIsEmpty(Vertx vertx, VertxTestContext tc) {
		vertx.runOnContext(v -> {
			BufferWriteStream ws = new BufferWriteStream();
			ws.getBuffer().onComplete(tc.succeeding(buf -> tc.verify(() -> {
				assertArrayEquals(new byte[0], buf.getBytes());
				tc.completeNow();
			})));
		});
	}
}
