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

import java.io.ByteArrayOutputStream;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

/**
 * A {@link io.vertx.core.streams.WriteStream WriteStream} that collects the written data into a
 * growable {@code byte[]}, analogous to {@link ByteArrayOutputStream}.
 * <p>
 * Written buffers are appended to an internal {@link ByteArrayOutputStream}; the accumulated bytes
 * are retrieved with {@link #getBytes()}. Instances must be created on a Vert.x context.
 *
 * @see AbstractMemoryWriteStream
 */
public class ByteArrayWriteStream extends AbstractMemoryWriteStream {
	private final ByteArrayOutputStream accumulator;

	/**
	 * Creates a new stream with an empty backing buffer, bound to the current Vert.x context.
	 *
	 * @throws IllegalStateException if there is no current Vert.x context
	 */
	public ByteArrayWriteStream() {
		super();
		this.accumulator = new ByteArrayOutputStream();
	}

	@Override
	protected void writeInternal(Buffer data) throws Exception {
		accumulator.write(data.getBytes());
	}

	/**
	 * Returns the bytes collected so far, as a fresh copy.
	 * <p>
	 * The read is marshalled onto the stream's context, so it observes every write submitted before
	 * this call. For the complete result, read once the {@link #end()} (or last write) future has
	 * completed.
	 *
	 * @return a future completed with a copy of the collected bytes
	 */
	public Future<byte[]> getBytes() {
		return getOnContext(accumulator::toByteArray);
	}
}
