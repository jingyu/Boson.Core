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
import io.bosonnetwork.utils.vertx.VertxCaffeine;
import io.bosonnetwork.utils.vertx.VertxFuture;

public abstract class AbstractResolver implements Resolver {
	private final AsyncCache<Id, ResolutionResult<Card>> cache;
	private final ResolverCache persistentCache;

	public AbstractResolver(Vertx vertx, ResolverCache persistentCache) {
		Caffeine<Object, Object> caffeine = vertx == null ?
				Caffeine.newBuilder() : VertxCaffeine.newBuilder(vertx);

		this.persistentCache = persistentCache;

		cache = caffeine.maximumSize(256)
				.initialCapacity(32)
				.expireAfterAccess(5, TimeUnit.MINUTES)
				.buildAsync();
	}

	@Override
	public CompletableFuture<ResolutionResult<Card>> resolve(Id id, ResolutionOptions options) {
		Objects.requireNonNull(id, "id");
		ResolutionOptions opts = options == null ? ResolutionOptions.defaultOptions() : options;

		if (!opts.usingCache()) {
			log().debug("Resolver cache is disabled, force to resolve: {}", id);

			return resolveId(id).thenApply(result -> {
				if (persistentCache != null) {
					try {
						persistentCache.put(id, result);
					} catch (Exception e) {
						log().error("Persistent cache update failed: {}, ignore!!!", id, e);
					}
				}

				// update the in-memory cache
				cache.asMap().computeIfAbsent(id, k -> VertxFuture.completedFuture(result));
				return result;
			});
		}

		return cache.get(id, (key, executor) -> {
			Promise<ResolutionResult<Card>> promise = Promise.promise();

			executor.execute(() -> {
				if (persistentCache != null) {
					try {
						ResolutionResult<Card> result = persistentCache.get(id);
						if (result != null && result.getResultMetadata().getResolved().getTime() > System.currentTimeMillis() - opts.validTTL()) {
							promise.complete(result);
							return;
						}
					} catch (Exception e) {
						log().error("Error while trying to get from persistent cache: {}, try to do resolve", id, e);
					}
				}

				resolveId(id).thenApply((result) -> {
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

	protected abstract CompletableFuture<ResolutionResult<Card>> resolveId(Id id);

	protected abstract Logger log();
}