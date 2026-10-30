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

package io.bosonnetwork.web;

/**
 * A request that can't be decided now, and may be sent again later. {@link CwtAuthHandler} answers an
 * authentication that fails with it {@code 503 Service Unavailable}, with {@code Retry-After}.
 */
public class RetryLaterException extends RuntimeException {
	private static final long serialVersionUID = -4212870458113394527L;

	/**
	 * The number of seconds to wait before retrying the request.
	 * The value is at least one second.
	 */
	private final long retryAfterSeconds;

	/**
	 * Creates the exception.
	 *
	 * @param retryAfterSeconds when to try again, in seconds, at least 1
	 * @param message           why, for the caller
	 */
	public RetryLaterException(long retryAfterSeconds, String message) {
		super(message);
		this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
	}

	/**
	 * Returns when to try again.
	 *
	 * @return the delay, in seconds
	 */
	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}
}
