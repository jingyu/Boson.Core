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
import io.bosonnetwork.utils.Json;

class FileSystemResolverCache implements ResolverCache {
	private static final long DEFAULT_EXPIRATION = 24 * 60 * 60;

	private final Path cacheDir;
	private final long expiration; 	// expiration time after write, in seconds

	private static final Logger log = LoggerFactory.getLogger(FileSystemResolverCache.class);

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

	@Override
	public void put(Id id, Resolver.ResolutionResult<Card> result) throws IOException {
		try {
			Path file = cacheDir.resolve(id.toString());
			Json.cborMapper().writeValue(file.toFile(), result);
			log.debug("Resolver persistent cache entry updated: {}", id);
		} catch (IOException e) {
			log.error("Resolver persistent cache entry update failed: {}", id, e);
			throw e;
		}
	}

	@Override
	public Resolver.ResolutionResult<Card> get(Id id) throws IOException {
		try {
			Path file = cacheDir.resolve(id.toString());
			if (!Files.exists(file)) {
				log.debug("Resolver persistent cache miss: {}", id);
				return null;
			}

			BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
			if (attrs.lastModifiedTime().toMillis() < System.currentTimeMillis() - expiration * 1000) {
				Files.delete(file);
				log.debug("Resolver persistent cache entry expired and evicted: {}", id);
				return null;
			}

			log.debug("Resolver persistent cache hit: {}", id);
			return Json.cborMapper().readValue(file.toFile(), new TypeReference<Resolver.ResolutionResult<Card>>() {
			});
		} catch (IOException e) {
			log.error("Resolver persistent cache entry read failed: {}", id, e);
			throw e;
		}
	}

	@Override
	public void cleanup() throws IOException {
		Files.walkFileTree(cacheDir, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (attrs.lastModifiedTime().toMillis() < System.currentTimeMillis() - expiration * 1000) {
					Files.delete(file);
					log.debug("Resolver persistent cache entry expired and evicted: {}", file);
				}

				return FileVisitResult.CONTINUE;
			}
		});
	}

	@Override
	public void clear() throws Exception {
		Files.walkFileTree(cacheDir, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}
		});

		log.info("Resolver persistent cache cleared");
	}
}