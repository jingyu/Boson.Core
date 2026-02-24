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

import java.io.OutputStream;

import io.vertx.core.buffer.Buffer;

/**
 * A wrapper for Vert.x Buffer that implements OutputStream
 * to allow zero-copy writing by Jackson and other utilities.
 */
public class BufferOutputStream extends OutputStream {
	private final Buffer buffer;

	/**
	 * Constructs a new BufferOutputStream instance that wraps the provided Vert.x Buffer.
	 * This allows data to be written to the Buffer through a standard OutputStream interface.
	 *
	 * @param buffer the Vert.x Buffer to wrap and write data into
	 */
	public BufferOutputStream(Buffer buffer) {
		this.buffer = buffer;
	}

	/** {@inheritDoc} */
	@Override
	public void write(int b) {
		buffer.appendByte((byte) b);
	}

	/** {@inheritDoc} */
	@Override
	public void write(byte[] b, int off, int len) {
		buffer.appendBytes(b, off, len);
	}
}