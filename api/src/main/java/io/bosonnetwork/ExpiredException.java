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

package io.bosonnetwork;

/**
 * Signals that an operation has failed due to an expired resource, token, or entity within the Boson network.
 * This exception is thrown when an action cannot be completed because the relevant item has passed its valid lifetime.
 *
 * <p>Examples of scenarios where this exception might be thrown include:
 * <ul>
 *   <li>An expired cryptographic key or certificate.</li>
 *   <li>A resource whose access period has ended.</li>
 * </ul>
 *
 * <p>This class extends {@link BosonException} and is part of the Boson API exception hierarchy,
 * providing a specific type to indicate expiration-related failures within the Boson network.
 */
public class ExpiredException extends BosonException {
	private static final long serialVersionUID = 1502154611092829891L;

	/**
	 * Creates a new {@code ExpiredException} with {@code null} as its detail message.
	 * The cause is not initialized and may be set later by a call to {@link #initCause}.
	 */
	public ExpiredException() {
		super();
	}

	/**
	 * Creates a new {@code ExpiredException} with the specified detail message.
	 * The cause is not initialized and may be set later by a call to {@link #initCause}.
	 *
	 * @param message the detail message saved for later retrieval by {@link #getMessage()}
	 */
	public ExpiredException(String message) {
		super(message);
	}

	/**
	 * Creates a new {@code ExpiredException} with the specified detail message and cause.
	 * The detail message associated with {@code cause} is not automatically incorporated.
	 *
	 * @param message the detail message saved for later retrieval by {@link #getMessage()}
	 * @param cause the cause saved for later retrieval by {@link #getCause()}, may be {@code null}
	 */
	public ExpiredException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Creates a new {@code ExpiredException} with the specified cause and a detail message
	 * of {@code (cause==null ? null : cause.toString())}, which typically contains the class and detail message of {@code cause}.
	 *
	 * @param cause the cause saved for later retrieval by {@link #getCause()}, may be {@code null}
	 */
	public ExpiredException(Throwable cause) {
		super(cause);
	}
}