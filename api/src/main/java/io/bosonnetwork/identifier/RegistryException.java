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

package io.bosonnetwork.identifier;

import io.bosonnetwork.BosonException;

/**
 * Exception class representing errors related to the Boson {@code Registry}.
 */
public class RegistryException extends BosonException {
	private static final long serialVersionUID = 8284109061811829467L;

	/**
	 * Constructs a new RegistryException with {@code null} as its detail message.
	 * The cause is not initialized and may be set later.
	 */
	public RegistryException() {
		super();
	}

	/**
	 * Constructs a new RegistryException with the specified detail message.
	 * The cause is not initialized and may be set later.
	 *
	 * @param message the detail message saved for later retrieval by {@link #getMessage()}.
	 */
	public RegistryException(String message) {
		super(message);
	}

	/**
	 * Constructs a new RegistryException with the specified detail message and cause.
	 * The cause's detail message is not automatically included.
	 *
	 * @param message the detail message saved for later retrieval by {@link #getMessage()}.
	 * @param cause the cause saved for later retrieval by {@link #getCause()}, may be {@code null}.
	 */
	public RegistryException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Constructs a new RegistryException with the specified cause.
	 * The detail message is set to {@code (cause == null ? null : cause.toString())}.
	 *
	 * @param cause the cause saved for later retrieval by {@link #getCause()}, may be {@code null}.
	 */
	public RegistryException(Throwable cause) {
		super(cause);
	}
}