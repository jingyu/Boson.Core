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

import org.jspecify.annotations.Nullable;

/**
 * Thrown by {@link ResolutionCache} implementations when a cache operation
 * ({@code put}, {@code get}, {@code evictExpired}, {@code clear}, or construction) cannot be
 * completed - typically due to I/O failures or (de)serialization errors.
 * <p>
 * Part of the resolver exception hierarchy ({@link io.bosonnetwork.BosonException} →
 * {@link RegistryException} → {@link ResolverException} → {@code ResolutionCacheException}),
 * so it is a checked exception. {@link CachedResolver} treats it as recoverable: a cache failure is logged and the
 * call falls through to the underlying resolver rather than propagating to the caller.
 */
public class ResolutionCacheException extends ResolverException {
	private static final long serialVersionUID = 7301217164571775472L;

	/**
	 * Constructs a new {@code ResolutionCacheException} with {@code null} as its detail message.
	 */
	public ResolutionCacheException() {
		super();
	}

	/**
	 * Constructs a new {@code ResolutionCacheException} with the specified detail message.
	 *
	 * @param message the detail message that explains the reason for the exception
	 */
	public ResolutionCacheException(@Nullable String message) {
		super(message);
	}

	/**
	 * Constructs a new {@code ResolutionCacheException} with the specified detail message
	 * and cause.
	 *
	 * @param message the detail message that explains the reason for the exception
	 * @param cause   the cause of the exception (may be {@code null})
	 */
	public ResolutionCacheException(@Nullable String message, @Nullable Throwable cause) {
		super(message, cause);
	}

	/**
	 * Constructs a new {@code ResolutionCacheException} with the specified cause.
	 *
	 * @param cause the cause of the exception (may be {@code null})
	 */
	public ResolutionCacheException(@Nullable Throwable cause) {
		super(cause);
	}
}