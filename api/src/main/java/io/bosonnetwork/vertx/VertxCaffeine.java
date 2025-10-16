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

package io.bosonnetwork.vertx;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;

import io.vertx.core.Vertx;

/**
 * Helper class to integrate Caffeine cache with Vert.x.
 * <p>
 * Provides a {@link java.util.concurrent.Executor} and a {@link com.github.benmanes.caffeine.cache.Scheduler}
 * that are compatible with Vert.x's event loop, allowing Caffeine cache operations to be scheduled and executed
 * in a Vert.x-friendly way.
 * </p>
 */
public class VertxCaffeine {
	/**
	 * Returns a Caffeine builder configured to use Vert.x's event loop for cache operations.
	 * <p>
	 * The returned builder sets a custom {@link Executor} that delegates execution to Vert.x,
	 * ensuring that cache operations are performed in a Vert.x-compatible context.
	 * It also sets a custom {@link Scheduler} that uses Vert.x timers for scheduling.
	 * </p>
	 *
	 * @param vertx the Vert.x instance to integrate with
	 * @return a Caffeine builder using Vert.x-friendly executor and scheduler
	 */
	public static Caffeine<Object, Object> newBuilder(Vertx vertx) {
		// We use executeBlocking to ensure the cache operation runs outside the event loop,
		// which is safer for potentially blocking or long-running tasks.
		// runOnContext would execute on the event loop, which is not recommended for blocking work.
		Executor vertxExecutor = (r) -> vertx.executeBlocking(() -> {
			r.run();
			return null;
		});

		/**
		 * Custom Caffeine Scheduler that schedules tasks using Vert.x timers.
		 * <p>
		 * The scheduled task is executed on the provided executor after the specified delay.
		 * Completion is signaled via a {@link CompletableFuture}, which is completed when the task finishes
		 * or completed exceptionally if an error occurs.
		 * </p>
		 */
		Scheduler vertxScheduler = (executor, runnable, delay, unit) -> {
			CompletableFuture<?> future = new CompletableFuture<>();

			vertx.setTimer(unit.toMillis(delay), (tid) -> {
				// When the timer fires, execute the scheduled task on the provided executor.
				// Complete the future when done, or complete exceptionally if an error occurs.
				executor.execute(() -> {
					try {
						runnable.run();
						future.complete(null);
					} catch (Exception e) {
						future.completeExceptionally(e);
					}
				});
			});

			return future;
		};

		return Caffeine.newBuilder()
				.executor(vertxExecutor)
				.scheduler(vertxScheduler);
	}
}