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
 * Exception thrown when an operation is attempted before its valid period.
 * <p>
 * This exception indicates that the current time or state is before the allowed
 * valid period for a particular operation or action within the Boson network.
 * </p>
 */
public class BeforeValidPeriodException extends BosonException {
	private static final long serialVersionUID = 7465022365373073472L;

	/**
	 * Constructs a new {@code BeforeValidPeriodException} with {@code null} as its
	 * detail message. The cause is not initialized and may be initialized later
	 * by a call to {@link #initCause}.
	 */
	public BeforeValidPeriodException() {
		super();
	}

	/**
	 * Constructs a new {@code BeforeValidPeriodException} with the specified detail
	 * message. The cause is not initialized and may be initialized later by a call
	 * to {@link #initCause}.
	 *
	 * @param message the detail message saved for later retrieval by
	 *                {@link #getMessage()}
	 */
	public BeforeValidPeriodException(String message) {
		super(message);
	}

	/**
	 * Constructs a new {@code BeforeValidPeriodException} with the specified detail
	 * message and cause. Note that the detail message associated with {@code cause}
	 * is <i>not</i> automatically incorporated into this exception's detail message.
	 *
	 * @param message the detail message saved for later retrieval by
	 *                {@link #getMessage()}
	 * @param cause   the cause saved for later retrieval by {@link #getCause()};
	 *                may be {@code null} if the cause is nonexistent or unknown
	 */
	public BeforeValidPeriodException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Constructs a new {@code BeforeValidPeriodException} with the specified cause
	 * and a detail message of {@code (cause==null ? null : cause.toString())},
	 * which typically contains the class and detail message of {@code cause}.
	 * This constructor is useful for exceptions that serve as wrappers for other
	 * throwables.
	 *
	 * @param cause the cause saved for later retrieval by {@link #getCause()};
	 *              may be {@code null} if the cause is nonexistent or unknown
	 */
	public BeforeValidPeriodException(Throwable cause) {
		super(cause);
	}
}