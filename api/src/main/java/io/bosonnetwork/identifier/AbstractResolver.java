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

import io.bosonnetwork.Id;
import io.bosonnetwork.vertx.VertxCaffeine;
import io.bosonnetwork.vertx.VertxFuture;

/**
 * Abstract base class for Boson {@link Resolver} implementations.
 * <p>
 * This class provides in-memory caching of resolved {@link Card} objects using Caffeine's asynchronous cache,
 * with an optional persistent cache layer for longer-term storage.
 * <p>
 * Resolution requests first check the in-memory cache, then the persistent cache (if configured),
 * before falling back to the concrete resolver implementation via {@link #resolveId(Id)}.
 * <p>
 * The cache entries expire after a configured TTL, and the cache size is limited to prevent excessive memory usage.
 * <p>
 * This class also integrates with Vert.x for asynchronous execution and event-loop safety when a {@link Vertx} instance is provided.
 */
public abstract class AbstractResolver implements Resolver {

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

	/**
	 * Constructs a new {@code AbstractResolver} with the given Vert.x instance and optional persistent cache.
	 *
	 * @param vertx           the Vert.x instance for asynchronous event loop integration, may be null
	 * @param persistentCache an optional persistent cache implementation, may be null
	 */
	public AbstractResolver(Vertx vertx, ResolverCache persistentCache) {
		Caffeine<Object, Object> caffeine = vertx == null ?
				Caffeine.newBuilder() : VertxCaffeine.newBuilder(vertx);

		this.persistentCache = persistentCache;

		cache = caffeine.maximumSize(256)
				.initialCapacity(32)
				.expireAfterAccess(5, TimeUnit.MINUTES)
				.buildAsync();
	}

	/**
	 * Resolves the given {@link Id} to a {@link ResolutionResult} of {@link Card}.
	 * <p>
	 * This method implements caching logic as follows:
	 * <ol>
	 *   <li>If caching is disabled via {@link ResolutionOptions}, it directly calls {@link #resolveId(Id)}
	 *       and updates both the persistent cache and in-memory cache with the result.</li>
	 *   <li>If caching is enabled:
	 *     <ul>
	 *       <li>It first attempts to retrieve the result from the in-memory asynchronous cache.</li>
	 *       <li>If not found or expired in-memory, it attempts to retrieve from the persistent cache if configured,
	 *           and checks if the cached result is still valid based on the TTL.</li>
	 *       <li>If no valid cached result is found, it calls {@link #resolveId(Id)} to perform the actual resolution,
	 *           then updates both caches accordingly.</li>
	 *     </ul>
	 *   </li>
	 * </ol>
	 * <p>
	 * All cache operations and resolution calls are performed asynchronously.
	 *
	 * @param id      the identifier to resolve, must not be null
	 * @param options the resolution options controlling caching behavior and TTL, may be null to use defaults
	 * @return a {@link CompletableFuture} that completes with the resolution result
	 * @throws NullPointerException if {@code id} is null
	 */
	@Override
	public CompletableFuture<ResolutionResult<Card>> resolve(Id id, ResolutionOptions options) {
		Objects.requireNonNull(id, "id");
		ResolutionOptions opts = options == null ? ResolutionOptions.defaultOptions() : options;

		// If caching is disabled, directly resolve and update caches
		if (!opts.usingCache()) {
			log().debug("Resolver cache is disabled, force to resolve: {}", id);

			return resolveId(id).thenApply(result -> {
				// Update persistent cache if available
				if (persistentCache != null) {
					try {
						persistentCache.put(id, result);
					} catch (Exception e) {
						log().error("Persistent cache update failed: {}, ignore!!!", id, e);
					}
				}

				// Update the in-memory cache asynchronously
				cache.asMap().computeIfAbsent(id, k -> VertxFuture.completedFuture(result));
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
						// Check if persistent cache result is valid based on TTL
						if (result != null && result.getResultMetadata().getResolved().getTime() > System.currentTimeMillis() - opts.validTTL()) {
							promise.complete(result);
							return;
						}
					} catch (Exception e) {
						log().error("Error while trying to get from persistent cache: {}, try to do resolve", id, e);
					}
				}

				// Perform actual resolution if no valid cache found
				resolveId(id).thenApply((result) -> {
					// Update persistent cache with new result
					if (persistentCache != null) {
						try {
							persistentCache.put(id, result);
						} catch (Exception e) {
							log().error("Error while trying to put in persistent cache: {}, ignore!!!", id, e);
						}
					}

					return result;
				}).whenComplete((result, error) -> {
					if (error != null) {
						promise.fail(error);
					} else {
						promise.complete(result);
					}
				});
			});

			return VertxFuture.of(promise.future());
		});
	}

	/**
	 * Resolves the given {@link Id} to a {@link ResolutionResult} of {@link Card}.
	 * <p>
	 * This method must be implemented by concrete subclasses to perform the actual resolution logic,
	 * such as querying a remote service or database.
	 * <p>
	 * This method is called when no valid cached result is available.
	 *
	 * @param id the identifier to resolve, guaranteed to be non-null
	 * @return a {@link CompletableFuture} that completes with the resolution result
	 */
	protected abstract CompletableFuture<ResolutionResult<Card>> resolveId(Id id);

	/**
	 * Returns the {@link Logger} instance for this resolver.
	 * <p>
	 * Used for logging debug and error messages.
	 *
	 * @return the logger instance
	 */
	protected abstract Logger log();
}