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

import java.nio.file.Path;

import io.bosonnetwork.Id;

/**
 * A pluggable cache interface for resolved Boson {@link Card}s.
 * <p>
 * Implementations of this interface provide caching for the results of resolving
 * {@link Id} entities to their corresponding {@link Card} objects, typically to
 * improve resolution performance and reduce redundant network or computation cost.
 * <p>
 * The cache may be in-memory, persistent (e.g., file-system based), or distributed.
 * Implementations may support cache expiration and/or eviction policies.
 * <p>
 * The provided static factory methods allow creation of a file-system based cache
 * implementation, which persists cache entries to disk and supports expiration.
 */
public interface ResolverCache {
	/**
	 * Stores the resolution result for a given {@link Id} in the cache.
	 *
	 * @param id the identifier for which the result is being cached
	 * @param result the resolved {@link Card} result to cache
	 * @throws Exception if storing the result fails (e.g., I/O error or serialization error)
	 */
	void put(Id id, Resolver.ResolutionResult<Card> result) throws Exception;

	/**
	 * Retrieves the cached resolution result for a given {@link Id}.
	 *
	 * @param id the identifier whose cached result is requested
	 * @return the cached {@link Resolver.ResolutionResult} for the given id,
	 *         or {@code null} if no valid entry exists or the entry has expired
	 * @throws Exception if retrieval fails (e.g., I/O error or deserialization error)
	 */
	Resolver.ResolutionResult<Card> get(Id id) throws Exception;

	/**
	 * Performs cache cleanup, such as removing expired entries or reclaiming resources.
	 * <p>
	 * For file-system based caches, this may delete files whose entries have expired.
	 *
	 * @throws Exception if cleanup fails (e.g., I/O error)
	 */
	void cleanup() throws Exception;

	/**
	 * Clears all entries from the cache.
	 * <p>
	 * For persistent caches, this typically removes all stored data (e.g., deletes all cache files).
	 *
	 * @throws Exception if clearing fails (e.g., I/O error)
	 */
	void clear() throws Exception;

	/**
	 * Creates a file-system based {@link ResolverCache} instance.
	 * <p>
	 * Cache entries are persisted to the specified directory and will expire after the given duration.
	 * Expired entries are automatically purged on access or via {@link #cleanup()}.
	 *
	 * @param cacheDir the directory to store cache files
	 * @param expiration the duration in milliseconds after which cached entries expire
	 * @return a file-system based cache instance
	 * @throws Exception if the cache cannot be created (e.g., directory inaccessible)
	 */
	static ResolverCache fileSystem(Path cacheDir, long expiration) throws Exception {
		return new FileSystemResolverCache(cacheDir, expiration);
	}

	/**
	 * Creates a file-system based {@link ResolverCache} instance with default directory and expiration.
	 * <p>
	 * The default location and expiration policy are implementation-defined.
	 *
	 * @return a file-system based cache instance with default settings
	 * @throws Exception if the cache cannot be created
	 */
	static ResolverCache fileSystem() throws Exception {
		return new FileSystemResolverCache();
	}
}