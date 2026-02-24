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

import java.io.InputStream;

import io.vertx.core.buffer.Buffer;

/**
 * A wrapper for Vert.x Buffer that implements InputStream
 * to allow zero-copy reading by Jackson and other utilities.
 */
public class BufferInputStream extends InputStream {
	private final Buffer buffer;
	private int pos;
	private final int limit;
	private int markPos = 0;

	/**
	 * Constructs a new BufferInputStream instance to facilitate reading data from the specified
	 * Vert.x Buffer. The stream provides a zero-copy mechanism for efficient data handling.
	 *
	 * @param buffer the Vert.x Buffer from which data will be read
	 */
	public BufferInputStream(Buffer buffer) {
		this.buffer = buffer;
		this.pos = 0;
		this.limit = buffer.length();
	}

	/** {@inheritDoc} */
	@Override
	public int read() {
		if (pos >= limit)
			return -1;

		return buffer.getByte(pos++) & 0xFF;
	}

	/** {@inheritDoc} */
	@Override
	public int read(byte[] b, int off, int len) {
		if (pos >= limit)
			return -1;

		int available = limit - pos;
		int toRead = Math.min(len, available);
		buffer.getBytes(pos, pos + toRead, b, off);
		pos += toRead;
		return toRead;
	}

	/** {@inheritDoc} */
	@Override
	public int available() {
		return limit - pos;
	}

	/** {@inheritDoc} */
	@Override
	public boolean markSupported() {
		return true;
	}

	/** {@inheritDoc} */
	@Override
	public synchronized void mark(int readlimit) {
		markPos = pos;
	}

	/** {@inheritDoc} */
	@Override
	public synchronized void reset() {
		pos = markPos;
	}
}