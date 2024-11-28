package io.bosonnetwork.utils.vertx;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Scheduler;

import io.vertx.core.Vertx;

public class VertxBackedCaffeine {
	public static Caffeine<Object, Object> newBuilder(Vertx vertx) {
		// Executor vertxExecutor = (r) -> vertx.runOnContext(() -> {
		Executor vertxExecutor = (r) -> vertx.executeBlocking(() -> {
			r.run();
			return null;
		});

		Scheduler vertxScheduler = (executor, runnable, delay, unit) -> {
			CompletableFuture<?> future = new CompletableFuture<>();

			vertx.setTimer(unit.toMillis(delay), (tid) -> {
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
