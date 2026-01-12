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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.json.Json;

/**
 * A file system-based implementation of {@link ResolverCache} that provides persistent storage
 * for resolver results. This cache stores each entry as a file in a specified directory, supports
 * configurable expiration (TTL), and provides methods for inserting, retrieving, cleaning up expired
 * entries, and clearing the cache.
 * <p>
 * Entries are serialized to CBOR format using Jackson, and each cache entry is stored in a file
 * named after the associated {@link Id}.
 * </p>
 */
class FileSystemResolverCache implements ResolverCache {
	private static final long DEFAULT_EXPIRATION = 24 * 60 * 60;

	/**
	 * The directory where cache entries are stored. Each cache entry is a file in this directory,
	 * named after the {@link Id} of the cached item.
	 */
	private final Path cacheDir;
	/**
	 * The expiration time (time-to-live, TTL) for cache entries, in seconds. Entries older than this
	 * value will be considered expired and evicted upon access or cleanup.
	 */
	private final long expiration; 	// expiration time after write, in seconds

	private static final Logger log = LoggerFactory.getLogger(FileSystemResolverCache.class);

	/**
	 * Constructs a {@code FileSystemResolverCache} using a specified directory and expiration time.
	 * If the directory does not exist, it will be created. If the expiration time is non-positive,
	 * a default TTL of 24 hours will be used.
	 *
	 * @param cacheDir   the directory in which to store cache files; must be a directory or creatable
	 * @param expiration the expiration time (TTL) for cache entries in seconds; if {@code <= 0}, uses default
	 * @throws IOException if the directory cannot be created or is not a directory
	 */
	public FileSystemResolverCache(Path cacheDir, long expiration) throws IOException {
		Objects.requireNonNull(cacheDir, "cacheDir");

		if (Files.exists(cacheDir)) {
			if (!Files.isDirectory(cacheDir)) {
				log.error("Resolver cache path {} exists and is not a directory", cacheDir);
				throw new IOException("Resolver cache path " + cacheDir + " exists and is not a directory");
			}
		} else {
			try {
				Files.createDirectories(cacheDir);
			} catch (IOException e) {
				log.error("Resolver cache path {} can not be created", cacheDir);
				throw new IOException("Resolver cache path " + cacheDir + " can not be created", e);
			}
		}

		this.cacheDir = cacheDir;
		this.expiration = expiration <= 0 ? DEFAULT_EXPIRATION : expiration;
		log.info("Resolver persistent cache created at {}, TTL: {}", cacheDir, expiration);
	}

	/**
	 * Constructs a {@code FileSystemResolverCache} with the default cache directory and default expiration time.
	 * The default directory is {@code ~/.cache/boson/identifier/resolver} if a home directory is available,
	 * otherwise a directory in the system temporary directory.
	 *
	 * @throws IOException if the default cache directory cannot be created
	 */
	public FileSystemResolverCache() throws IOException {
		this(defaultCacheDir(), DEFAULT_EXPIRATION);
	}

	private static Path defaultCacheDir() {
		String homeDir = System.getProperty("user.home");
		if (homeDir != null && !homeDir.isEmpty()) {
			// current user has home directory, using the user home directory
			return Path.of(homeDir, ".cache", "boson", "identifier", "resolver");
		} else {
			// using the temp directory
			return Path.of(System.getProperty("java.io.tmpdir"), "boson-resolver-cache");
		}
	}

	/**
	 * Stores the given resolution result in the cache, associated with the specified {@link Id}.
	 * The entry is serialized and written to a file in the cache directory. Any existing entry for
	 * the same {@code Id} will be overwritten.
	 *
	 * @param id     the identifier for the cache entry
	 * @param result the resolution result to store
	 * @throws IOException if writing to the cache file fails
	 */
	@Override
	public void put(Id id, Resolver.ResolutionResult<Card> result) throws IOException {
		try {
			// Create or overwrite the cache file for the given Id
			Path file = cacheDir.resolve(id.toString());
			Json.cborMapper().writeValue(file.toFile(), result);
			log.debug("Resolver persistent cache entry updated: {}", id);
		} catch (IOException e) {
			log.error("Resolver persistent cache entry update failed: {}", id, e);
			throw e;
		}
	}

	/**
	 * Retrieves the cached resolution result for the specified {@link Id}, if present and not expired.
	 * If the entry is expired, it is deleted and {@code null} is returned. If not found, returns {@code null}.
	 *
	 * @param id the identifier for the cache entry
	 * @return the cached resolution result, or {@code null} if not found or expired
	 * @throws IOException if reading from the cache file fails
	 */
	@Override
	public Resolver.ResolutionResult<Card> get(Id id) throws IOException {
		try {
			Path file = cacheDir.resolve(id.toString());
			// Check if the cache file exists for this Id
			if (!Files.exists(file)) {
				log.debug("Resolver persistent cache miss: {}", id);
				return null;
			}

			// Read file attributes to check for expiration
			BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
			// If the file is older than the expiration threshold, delete and evict it
			if (attrs.lastModifiedTime().toMillis() < System.currentTimeMillis() - expiration * 1000) {
				Files.delete(file);
				log.debug("Resolver persistent cache entry expired and evicted: {}", id);
				return null;
			}

			// Cache hit: read and return the cached result
			log.debug("Resolver persistent cache hit: {}", id);
			return Json.cborMapper().readValue(file.toFile(), new TypeReference<Resolver.ResolutionResult<Card>>() { });
		} catch (IOException e) {
			log.error("Resolver persistent cache entry read failed: {}", id, e);
			throw e;
		}
	}

	/**
	 * Removes all expired entries from the cache directory. Each file is checked for expiration based
	 * on its last modified time and the configured TTL. Expired files are deleted and a debug log is emitted.
	 *
	 * @throws IOException if an error occurs while accessing or deleting files
	 */
	@Override
	public void cleanup() throws IOException {
		Files.walkFileTree(cacheDir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				// Check if the file is expired according to TTL
				if (attrs.lastModifiedTime().toMillis() < System.currentTimeMillis() - expiration * 1000) {
					Files.delete(file);
					log.debug("Resolver persistent cache entry expired and evicted: {}", file);
				}

				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Removes all entries from the cache directory, regardless of age or expiration. All cache files
	 * are deleted, and an info log is emitted after clearing.
	 *
	 * @throws Exception if an error occurs while deleting files
	 */
	@Override
	public void clear() throws Exception {
		Files.walkFileTree(cacheDir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				// Delete every file in the cache directory
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}
		});

		log.info("Resolver persistent cache cleared");
	}
}