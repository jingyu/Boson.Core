/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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
 * Represents a checked exception that may be thrown by the Boson library to indicate
 * an error condition specific to Boson operations.
 * <p>
 * This is the base class for all checked exceptions in the Boson API.
 * </p>
 */
public class BosonException extends Exception {
	private static final long serialVersionUID = 7113857681570350392L;

	/**
	 * Constructs a new {@code BosonException} with {@code null} as its detail message.
	 * The cause is not initialized and may be set later by a call to {@link #initCause}.
	 */
	public BosonException() {
		super();
	}

	/**
	 * Constructs a new {@code BosonException} with the specified detail message.
	 * The cause is not initialized and may be set later by a call to {@link #initCause}.
	 *
	 * @param message the detail message, which is saved for later retrieval by the {@link #getMessage()} method.
	 */
	public BosonException(String message) {
		super(message);
	}

	/**
	 * Constructs a new {@code BosonException} with the specified detail message and cause.
	 * <p>
	 * Note that the detail message associated with {@code cause} is not automatically
	 * incorporated into this exception's detail message.
	 * </p>
	 *
	 * @param message the detail message, saved for later retrieval by {@link #getMessage()}.
	 * @param cause the cause, which is saved for later retrieval by {@link #getCause()}.
	 *              A {@code null} value is permitted and indicates that the cause is nonexistent or unknown.
	 */
	public BosonException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Constructs a new {@code BosonException} with the specified cause and a detail
	 * message of {@code (cause == null ? null : cause.toString())}, which typically contains
	 * the class and detail message of {@code cause}.
	 * <p>
	 * This constructor is useful for exceptions that are wrappers for other throwables.
	 * </p>
	 *
	 * @param cause the cause, saved for later retrieval by {@link #getCause()}.
	 *              A {@code null} value is permitted and indicates that the cause is nonexistent or unknown.
	 */
	public BosonException(Throwable cause) {
		super(cause);
	}
}