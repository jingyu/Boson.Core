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
 * A pluggable cache for {@link Resolver.ResolutionResult resolution results} of Boson
 * {@link Card}s, keyed by {@link Id}.
 * <p>
 * Implementations cache full resolution results (the resolved {@code Card} plus its
 * {@link Resolver.ResolutionMetadata metadata}) so the resolver can short-circuit a
 * repeat lookup. Cache misses, expired entries, and storage errors leave it to the caller to
 * resolve from the underlying source.
 * <p>
 * Implementations may be in-memory, on disk, or remote; they may purge expired entries lazily
 * on access and/or expose an explicit {@link #evictExpired()} hook.
 * <p>
 * Use {@link #fileSystem(Path, long)} or {@link #fileSystem()} for the built-in on-disk
 * implementation.
 */
public interface ResolutionCache {
	/**
	 * Stores a resolution result for the given {@link Id}, replacing any previous entry.
	 * Implementations should only persist results the caller considers authoritative; negative
	 * or invalid results are typically not worth keeping.
	 *
	 * @param id the identifier being cached
	 * @param result the resolution result to cache (the resolved Card and its metadata)
	 * @throws ResolutionCacheException if storing fails (I/O error, serialization error, etc.)
	 */
	void put(Id id, Resolver.ResolutionResult<Card> result) throws ResolutionCacheException;

	/**
	 * Returns the cached resolution result for the given {@link Id}, or {@code null} if absent
	 * or expired. Implementations may purge expired entries as a side-effect of this lookup.
	 *
	 * @param id the identifier whose cached result is requested
	 * @return the cached resolution result, or {@code null} if no valid entry exists
	 * @throws ResolutionCacheException if retrieval fails (I/O error, deserialization error, etc.)
	 */
	Resolver.ResolutionResult<Card> get(Id id) throws ResolutionCacheException;

	/**
	 * Evicts entries whose expiration has passed. Implementations that already purge lazily on
	 * {@link #get(Id)} may treat this as an explicit sweep hook (e.g., for a scheduled task).
	 *
	 * @throws ResolutionCacheException if eviction fails (e.g., I/O error)
	 */
	void evictExpired() throws ResolutionCacheException;

	/**
	 * Removes every entry from the cache. For persistent implementations this also removes the
	 * underlying storage (e.g., deletes all cache files).
	 *
	 * @throws ResolutionCacheException if clearing fails (e.g., I/O error)
	 */
	void clear() throws ResolutionCacheException;

	/**
	 * Creates an on-disk {@link ResolutionCache} backed by the given directory.
	 * Entries expire after {@code expiration} seconds; expired entries are purged lazily on
	 * {@link #get(Id)} and can also be swept via {@link #evictExpired()}.
	 *
	 * @param cacheDir the directory in which to store cache files (created if absent)
	 * @param expiration the entry time-to-live in seconds; values {@code <= 0} fall back to a 24-hour default
	 * @return a file-system backed cache
	 * @throws ResolutionCacheException if the cache cannot be created (e.g., the directory is inaccessible)
	 */
	static ResolutionCache fileSystem(Path cacheDir, long expiration) throws ResolutionCacheException {
		return new FileSystemResolutionCache(cacheDir, expiration);
	}

	/**
	 * Creates an on-disk {@link ResolutionCache} using built-in defaults: directory
	 * {@code ~/.cache/boson/identifier/resolver} (or {@code java.io.tmpdir/boson-resolver-cache}
	 * when the user has no home directory) and a 24-hour expiration.
	 *
	 * @return a file-system backed cache with default settings
	 * @throws ResolutionCacheException if the cache cannot be created
	 */
	static ResolutionCache fileSystem() throws ResolutionCacheException {
		return new FileSystemResolutionCache();
	}
}