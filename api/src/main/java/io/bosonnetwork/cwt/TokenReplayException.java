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

package io.bosonnetwork.cwt;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when a CWT whose "CWT ID" (cti) claim has already been seen is presented again, or when the
 * {@link ReplayCache} that would have to remember it is full.
 */
public class TokenReplayException extends CwtException {
	private static final long serialVersionUID = 2918463517330950144L;

	/**
	 * Constructs a new TokenReplayException with the specified detail message.
	 *
	 * @param message the detail message.
	 */
	public TokenReplayException(@Nullable String message) {
		super(message);
	}

	/**
	 * Constructs a new TokenReplayException with the specified detail message and cause.
	 *
	 * @param message the detail message.
	 * @param cause   the cause of the exception.
	 */
	public TokenReplayException(@Nullable String message, @Nullable Throwable cause) {
		super(message, cause);
	}
}
