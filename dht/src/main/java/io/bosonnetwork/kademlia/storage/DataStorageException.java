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

package io.bosonnetwork.kademlia.storage;

import io.bosonnetwork.kademlia.exceptions.IOError;

/**
 * An exception thrown to indicate errors related to data storage operations,
 * such as issues with reading from or writing to a storage system. This exception
 * extends {@link IOError} to represent I/O-related failures specific to data
 * storage mechanisms.
 */
public class DataStorageException extends IOError {
	private static final long serialVersionUID = -7280654714507016219L;

	/**
	 * Constructs a new {@code DataStorageException} with {@code null} as its
	 * detail message. The cause is not initialized and may be set later
	 * using {@link #initCause(Throwable)}.
	 */
	public DataStorageException() {
		super();
	}

	/**
	 * Constructs a new {@code DataStorageException} with the specified detail
	 * message. The cause is not initialized and may be set later using
	 * {@link #initCause(Throwable)}.
	 *
	 * @param message the detail message, saved for later retrieval by the
	 *                {@link #getMessage()} method. May be {@code null}.
	 */
	public DataStorageException(String message) {
		super(message);
	}

	/**
	 * Constructs a new {@code DataStorageException} with the specified detail
	 * message and cause. The detail message associated with the cause is not
	 * automatically included in this exception's detail message.
	 *
	 * @param message the detail message, saved for later retrieval by the
	 *                {@link #getMessage()} method. May be {@code null}.
	 * @param cause   the cause of the exception, saved for later retrieval by the
	 *                {@link #getCause()} method. A {@code null} value is permitted
	 *                and indicates that the cause is nonexistent or unknown.
	 */
	public DataStorageException(String message, Throwable cause) {
		super(message, cause);
	}

	/**
	 * Constructs a new {@code DataStorageException} with the specified cause and
	 * a detail message of {@code (cause == null ? null : cause.toString())}.
	 * This constructor is useful for wrapping other throwables, such as those
	 * thrown by underlying storage systems.
	 *
	 * @param cause the cause of the exception, saved for later retrieval by the
	 *              {@link #getCause()} method. A {@code null} value is permitted
	 *              and indicates that the cause is nonexistent or unknown.
	 */
	public DataStorageException(Throwable cause) {
		super(cause);
	}
}