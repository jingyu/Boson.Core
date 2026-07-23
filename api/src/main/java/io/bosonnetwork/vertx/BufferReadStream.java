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

import io.vertx.core.buffer.Buffer;
import org.jspecify.annotations.Nullable;

/**
 * A {@link io.vertx.core.streams.ReadStream ReadStream} backed by a Vert.x {@link Buffer}, exposing a
 * {@code [offset, offset + length)} slice of the buffer as an async stream.
 * <p>
 * The backing buffer is referenced, not copied; each emitted chunk is a {@link Buffer#slice(int, int)
 * slice} that shares the backing storage rather than a copy. The caller should not mutate the buffer
 * while the stream is active. Instances must be created on a Vert.x context.
 *
 * @see AbstractMemoryReadStream
 */
public class BufferReadStream extends AbstractMemoryReadStream {
	private final Buffer data;
	private final int offset;
	private final int length;
	private int position;

	/**
	 * Creates a stream over {@code data[offset, offset + length)}.
	 *
	 * @param data the backing buffer (referenced, not copied)
	 * @param offset the start index of the exposed slice
	 * @param length the number of bytes to expose
	 * @param readBufferSize the maximum bytes per emitted chunk; {@code <= 0} selects the default
	 * @throws NullPointerException if {@code data} is {@code null}
	 * @throws IllegalArgumentException if {@code offset} or {@code length} is negative, or the slice
	 *                                  extends past the end of the buffer
	 */
	public BufferReadStream(Buffer data, int offset, int length, int readBufferSize) {
		super(readBufferSize);

		Objects.requireNonNull(data);
		if (offset < 0 || length < 0 || offset + length > data.length())
			throw new IllegalArgumentException("Invalid offset/length");

		this.data = data;
		this.offset = offset;
		this.length = length;
		this.position = offset;
	}

	/**
	 * Creates a stream over {@code data[offset, offset + length)} using the default chunk size.
	 *
	 * @param data the backing buffer (referenced, not copied)
	 * @param offset the start index of the exposed slice
	 * @param length the number of bytes to expose
	 * @throws NullPointerException if {@code data} is {@code null}
	 * @throws IllegalArgumentException if the slice is out of bounds
	 */
	public BufferReadStream(Buffer data, int offset, int length) {
		this(data, offset, length, 0);
	}

	/**
	 * Creates a stream over the whole buffer using the default chunk size.
	 *
	 * @param data the backing buffer (referenced, not copied)
	 * @throws NullPointerException if {@code data} is {@code null}
	 */
	public BufferReadStream(Buffer data) {
		this(data, 0, data.length(), 0);
	}

	@Override
	protected @Nullable Buffer readInternal(int amount) {
		if (position >= offset + length)
			return null;

		amount = position + amount > offset + length ? offset + length - position : amount;
		// slice() returns a view sharing the backing buffer, avoiding a copy of the chunk.
		Buffer buf = data.slice(position, position + amount);
		position += amount;
		return buf;
	}
}
