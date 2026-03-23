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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.vertx.VertxCaffeine;
import io.bosonnetwork.vertx.VertxFuture;

/**
 * A resolver implementation that uses in-memory and optional persistent caching
 * to optimize and reduce repeated resolution operations for {@link Card} instances.
 * <p>
 * This class wraps around another {@link Resolver} and introduces caching
 * mechanisms to store and retrieve previously resolved results, minimizing
 * additional computations or network calls. The caching behavior depends on the
 * provided {@link ResolutionOptions}.
 * <p>
 * Key features:
 * - An in-memory cache with a maximum size and expiration policy, using Caffeine.
 * - Optional integration with a persistent cache for longer-lived storage of resolved results.
 * - Asynchronous caching and resolution to support non-blocking operations.
 * - Configurable caching behavior based on the provided options.
 */
public class CachedResolver implements Resolver {
	private final Resolver resolver;

	/**
	 * In-memory asynchronous cache for resolved {@link Card} objects keyed by {@link Id}.
	 * <p>
	 * Uses Caffeine cache with a maximum size and expiration policy.
	 */
	private final AsyncCache<Id, ResolutionResult<Card>> cache;

	/**
	 * Optional persistent cache for storing resolved results beyond the in-memory cache lifetime.
	 * <p>
	 * This cache is used to retrieve and store resolved {@link Card} objects to provide longer-lived caching
	 * across application restarts or multiple instances.
	 */
	private final ResolverCache persistentCache;

	private final static Logger log = LoggerFactory.getLogger(CachedResolver.class);

	/**
	 * Constructs a new {@code CachedResolver} instance with the provided underlying resolver,
	 * Vert.x instance, and persistent cache. This resolver uses an in-memory cache and an optional
	 * persistent cache to improve resolution performance. If Vert.x is unavailable, a default
	 * Caffeine cache is constructed.
	 *
	 * @param resolver the underlying resolver used for resolving identifiers, must not be null
	 * @param vertx the Vert.x instance used for integration with Vert.x asynchronous features,
	 *              may be null if Vert.x support is not required
	 * @param persistentCache an optional persistent cache implementation for storing resolved values,
	 *                        may be null if no persistent storage is needed
	 */
	public CachedResolver(Resolver resolver, Vertx vertx, ResolverCache persistentCache) {
		this.resolver = Objects.requireNonNull(resolver, "resolver");

		this.persistentCache = persistentCache;

		if (vertx == null) {
			try {
				Class.forName("io.vertx.core.Vertx");
				vertx = Vertx.currentContext().owner();
			} catch (ClassNotFoundException ignored) {
			}
		}

		Caffeine<Object, Object> caffeine = vertx == null ?
				Caffeine.newBuilder() : VertxCaffeine.newBuilder(vertx);
		cache = caffeine.maximumSize(256)
				.initialCapacity(32)
				.expireAfterAccess(5, TimeUnit.MINUTES)
				.buildAsync();
	}

	/**
	 * Constructs a new {@code CachedResolver} instance with the provided underlying resolver
	 * and Vert.x instance. This resolver uses an in-memory cache and optionally integrates
	 * with Vert.x for asynchronous features. If Vert.x is not required, it may be set to null.
	 *
	 * @param resolver the underlying resolver used for resolving identifiers, must not be null
	 * @param vertx the Vert.x instance used for asynchronous support, may be null if not needed
	 */
	public CachedResolver(Resolver resolver, Vertx vertx) {
		this(resolver, vertx, null);
	}

	/**
	 * Constructs a new {@code CachedResolver} instance with the provided underlying resolver.
	 * This initializer uses default in-memory caching and does not integrate with a persistent
	 * cache or asynchronous features.
	 *
	 * @param resolver the underlying resolver used for resolving identifiers, must not be null
	 */
	public CachedResolver(Resolver resolver) {
		this(resolver, null, null);
	}

	/**
	 * Resolves the given {@code id} with the specified resolution options.
	 * The method supports caching to optimize resolutions by using both in-memory and persistent caches.
	 * If caching is disabled or no valid cache is found, the resolution process will be performed
	 * using the underlying resolver.
	 *
	 * @param id the unique identifier to resolve, must not be null
	 * @param options the resolution options specifying caching behavior and time-to-live (TTL),
	 *                defaults to {@code ResolutionOptions.defaultOptions()} if null
	 * @return a {@code CompletableFuture} that resolves to a {@code ResolutionResult<Card>} containing
	 *         the resolved result, or completes exceptionally if the resolution fails
	 */
	@Override
	public CompletableFuture<ResolutionResult<Card>> resolve(Id id, ResolutionOptions options) {
		Objects.requireNonNull(id, "id");
		ResolutionOptions opts = options == null ? ResolutionOptions.defaultOptions() : options;

		// If caching is disabled, directly resolve and update caches
		if (!opts.usingCache()) {
			log().debug("Resolver cache is disabled, force to resolve: {}", id);

			return resolver.resolve(id, options).thenApply(result -> {
				// Update persistent cache if available
				if (persistentCache != null) {
					try {
						persistentCache.put(id, result);
					} catch (Exception e) {
						log().error("Persistent cache update failed: {}, ignore!!!", id, e);
					}
				}

				// Update the in-memory cache asynchronously
				cache.synchronous().get(id, k -> result);
				return result;
			});
		}

		// Use the asynchronous cache to get or load the resolution result
		return cache.get(id, (key, executor) -> {
			Promise<ResolutionResult<Card>> promise = Promise.promise();

			// Execute cache loading logic asynchronously on the provided executor
			executor.execute(() -> {
				// Attempt to retrieve from persistent cache if configured
				if (persistentCache != null) {
					try {
						ResolutionResult<Card> result = persistentCache.get(id);
						// Check if the Card exists in the persistent cache and it's valid
						if (result != null && result.getResultMetadata().getResolved().getTime() > System.currentTimeMillis() - opts.validTTL()) {
							promise.complete(result);
							return;
						}
					} catch (Exception e) {
						log().error("Error while trying to get from persistent cache: {}, try to do resolve", id, e);
					}
				}

				// Perform actual resolution if no valid cache found
				resolver.resolve(id, options).whenComplete((result, error) -> {
					if (error == null) {
						// Update the persistent cache with the result
						if (persistentCache != null) {
							try {
								persistentCache.put(id, result);
							} catch (Exception e) {
								log().error("Error while trying to put in persistent cache: {}, ignore!!!", id, e);
							}
						}

						promise.complete(result);
					} else {
						promise.fail(error);
					}
				});
			});

			return VertxFuture.of(promise.future());
		});
	}

	/**
	 * Returns the {@link Logger} instance for this resolver.
	 * <p>
	 * Used for logging debug and error messages.
	 *
	 * @return the logger instance
	 */
	protected Logger log() {
		return log;
	}
}