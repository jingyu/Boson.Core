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

import java.util.Objects;

import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;

/**
 * A {@link io.vertx.core.streams.WriteStream WriteStream} that collects the written data into a
 * single Vert.x {@link Buffer}.
 * <p>
 * Written buffers are appended to an internal accumulator {@link Buffer}; the accumulated data is
 * retrieved with {@link #getBuffer()}. Instances must be created on a Vert.x context.
 *
 * @see AbstractMemoryWriteStream
 */
public class BufferWriteStream extends AbstractMemoryWriteStream {
	private final Buffer accumulator;

	/**
	 * Creates a new stream with an empty accumulator buffer, bound to the current Vert.x context.
	 *
	 * @throws IllegalStateException if there is no current Vert.x context
	 */
	public BufferWriteStream() {
		super();
		this.accumulator = Buffer.buffer();
	}

	/**
	 * Creates a new instance of {@code BufferWriteStream} with the specified initial buffer as the
	 * data accumulator. The stream is bound to the current Vert.x context.
	 *
	 * @param buffer the initial {@link Buffer} to be used as the accumulator. It must not be {@code null}.
	 * @throws NullPointerException if the provided {@code buffer} is {@code null}.
	 * @throws IllegalStateException if there is no current Vert.x context.
	 */
	public BufferWriteStream(Buffer buffer) {
		super();
		this.accumulator = Objects.requireNonNull(buffer, "buffer");
	}

	@Override
	protected void writeInternal(Buffer data) {
		accumulator.appendBuffer(data);
	}

	/**
	 * Returns the accumulator buffer holding the data collected so far.
	 * <p>
	 * The read is marshalled onto the stream's context, so it observes every write submitted before
	 * this call. The returned buffer is the live accumulator, not a copy; read once the {@link #end()}
	 * (or last write) future has completed so that no further writes mutate it.
	 *
	 * @return a future completed with the collected buffer
	 */
	public Future<Buffer> getBuffer() {
		return getOnContext(() -> accumulator);
	}
}
