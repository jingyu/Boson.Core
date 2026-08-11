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

package io.bosonnetwork.kademlia.exceptions;

import io.bosonnetwork.kademlia.impl.ErrorCode;

/**
 * Thrown when a Kademlia message exceeds the maximum allowed size.
 */
public class MessageTooBigException extends KadException {
	private static final long serialVersionUID = 178612190989090105L;

	/**
	 * Constructs a new MessageTooBig exception with the specified detail message.
	 *
	 * @param message the detail message
	 */
	public MessageTooBigException(String message) {
		super(ErrorCode.MessageTooBig.value(), message);
	}

	/**
	 * Constructs a new MessageTooBig exception with the specified cause.
	 *
	 * @param cause the cause
	 */
	public MessageTooBigException(Throwable cause) {
		super(ErrorCode.MessageTooBig.value(), cause);
	}

	/**
	 * Constructs a new MessageTooBig exception with the specified detail message
	 * and cause.
	 *
	 * @param message the detail message
	 * @param cause the cause
	 */
	public MessageTooBigException(String message, Throwable cause) {
		super(ErrorCode.MessageTooBig.value(), message, cause);
	}
}