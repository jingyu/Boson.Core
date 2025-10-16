package io.bosonnetwork.vertx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.concurrent.TimeUnit;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.utils.Variable;

@ExtendWith(VertxExtension.class)
public class VertxCaffeineTests {
	@Test
	public void testAsyncCache(Vertx vertx, VertxTestContext context) {
		Context ctx = vertx.getOrCreateContext();
		Variable<Long> tid = Variable.empty();

		System.out.println("Test thread: " + Thread.currentThread().getName());

		AsyncCache<String, String> cache = VertxCaffeine.newBuilder(vertx)
				.maximumSize(64)
				.initialCapacity(8)
				.expireAfterWrite(1, TimeUnit.MINUTES)
				.buildAsync();

		var future = cache.get("foo", (key, executor) -> {
			Promise<String> promise = Promise.promise();
			ctx.runOnContext(v -> {
				System.out.println("Loader thread: " + Thread.currentThread().getName());

				tid.set(Thread.currentThread().getId());

				try {
					TimeUnit.SECONDS.sleep(1);
					promise.complete(Thread.currentThread().getName() + "-" + key + "#" + System.currentTimeMillis());
				} catch (InterruptedException e) {
					promise.fail(e);
				}
			});
			return VertxFuture.of(promise.future());
		});

		assertInstanceOf(VertxFuture.class, future);

		future.thenAccept(s -> {
			System.out.println("Future::thenAccept thread: " + Thread.currentThread().getName());
			System.out.println(s);
			context.verify(() -> assertEquals(tid.get(), Thread.currentThread().getId()));
		}).thenRun(context::completeNow);
	}

	@Test
	public void testAsyncLoadingCache(Vertx vertx, VertxTestContext context) {
		Context ctx = vertx.getOrCreateContext();
		Variable<Long> tid = Variable.empty();

		System.out.println("Test thread: " + Thread.currentThread().getName());

		AsyncLoadingCache<String, String> cache = VertxCaffeine.newBuilder(vertx)
				.maximumSize(64)
				.initialCapacity(8)
				.expireAfterWrite(1, TimeUnit.MINUTES)
				.buildAsync((key, executor) -> {
					Promise<String> promise = Promise.promise();
					ctx.runOnContext(v -> {
						System.out.println("Loader thread: " + Thread.currentThread().getName());

						if (tid.isEmpty())
							tid.set(Thread.currentThread().getId());

						try {
							TimeUnit.SECONDS.sleep(1);
							promise.complete(Thread.currentThread().getName() + "-" + key + "#" + System.currentTimeMillis());
						} catch (InterruptedException e) {
							promise.fail(e);
						}
					});

					return VertxFuture.of(promise.future());
				});

		var future = cache.get("foo");

		assertInstanceOf(VertxFuture.class, future);

		future.thenAccept(s -> {
			System.out.println("Future::thenAccept thread: " + Thread.currentThread().getName());
			System.out.println(s);
			context.verify(() -> assertEquals(tid.get(), Thread.currentThread().getId()));
		}).thenRun(context::completeNow);
	}
}