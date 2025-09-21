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
 * Exception indicating a failure due to an invalid cryptographic signature.
 * This exception extends {@link BosonException}.
 */
public class InvalidSignatureException extends BosonException {
	private static final long serialVersionUID = 6913356936649079074L;

	/**
	 * Creates a new {@code InvalidSignatureException} with {@code null} as its detail message.
	 * The cause is not initialized and may subsequently be initialized by a call to {@link #initCause}.
	 */
	public InvalidSignatureException() {
		super();
	}

	/**
	 * Creates a new {@code InvalidSignatureException} with the specified detail message.
	 *
	 * @param message the detail message saved for later retrieval by {@link #getMessage()}
	 */
	public InvalidSignatureException(String message) {
		super(message);
	}

	/**
	 * Creates a new {@code InvalidSignatureException} with the specified detail message and cause.
	 * Note that the detail message associated with {@code cause} is not automatically incorporated in this exception's detail message.
	 *
	 * @param message the detail message saved for later retrieval by {@link #getMessage()}
	 * @param cause the cause saved for later retrieval by {@link #getCause()}, may be {@code null}
	 */
	public InvalidSignatureException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Creates a new {@code InvalidSignatureException} with the specified cause and a detail message of
	 * {@code (cause == null ? null : cause.toString())} (which typically contains the class and detail message of {@code cause}).
	 *
	 * @param cause the cause saved for later retrieval by {@link #getCause()}, may be {@code null}
	 */
	public InvalidSignatureException(Throwable cause) {
		super(cause);
	}
}