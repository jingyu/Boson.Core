package io.bosonnetwork.utils.vertx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.utils.Variable;

@ExtendWith(VertxExtension.class)
public class VertxFutureTests {
	private static void printThreadContext(String prefix) {
		System.out.printf("%s: %s:%d\n", prefix, Thread.currentThread().getName(), Thread.currentThread().getId());
	}

	@Test
	void testFuture(Vertx vertx, VertxTestContext context) {
		Context ctx = vertx.getOrCreateContext();

		Promise<String> promise = Promise.promise();
		Variable<Long> tid = Variable.empty();

		ctx.runOnContext(v -> {
			tid.set(Thread.currentThread().getId());
			try {
				TimeUnit.SECONDS.sleep(1);
			} catch (InterruptedException e) {
				promise.fail(e);
			}

			printThreadContext("Context.runOnContext");
			promise.complete("Hello future");
		});

		promise.future().andThen(ar -> {
			printThreadContext("Future.andThen");
			context.verify(() -> assertEquals(tid.get(), Thread.currentThread().getId()));
		}).map(s -> {
			printThreadContext("Future.map");
			context.verify(() -> assertEquals(tid.get(), Thread.currentThread().getId()));
			return s + " ==>> mapped";
		}).compose(s -> {
			printThreadContext("Future.compose");
			context.verify(() -> assertEquals(tid.get(), Thread.currentThread().getId()));
			return Future.succeededFuture(s + " ==>> composed");
		}).onComplete(context.succeedingThenComplete());
	}

	@Test
	void testCompletableFuture() {
		CompletableFuture<String> future = CompletableFuture.completedFuture("Foo bar");
		long threadId = Thread.currentThread().getId();

		future.thenApply(s -> {
			printThreadContext("Future.thenApply");
			assertEquals(threadId, Thread.currentThread().getId());
			return s + " ==>> applied";
		}).thenCompose(s -> {
			printThreadContext("Future.thenCompose");
			assertEquals(threadId, Thread.currentThread().getId());
			return CompletableFuture.completedFuture(s + " ==>> composed");
		}).thenAccept(s -> {
			printThreadContext("Future.thenAccept");
			assertEquals(threadId, Thread.currentThread().getId());
		}).thenRun(() -> {
			printThreadContext("Future.thenRun");
			assertEquals(threadId, Thread.currentThread().getId());
		}).thenApplyAsync(s -> {
			printThreadContext("Future.thenApplyAsync");
			assertNotEquals(threadId, Thread.currentThread().getId());
			return s + " ==>> appliedAsync";
		}).thenCompose(s -> {
			printThreadContext("Future.thenCompose");
			assertNotEquals(threadId, Thread.currentThread().getId());
			return CompletableFuture.completedFuture(s + " ==>> composed");
		}).thenComposeAsync(s -> {
			printThreadContext("Future.thenComposeAsync");
			assertNotEquals(threadId, Thread.currentThread().getId());
			return CompletableFuture.completedFuture(s + " ==>> composedAsync");
		}).thenCompose(s -> {
			printThreadContext("Future.thenCompose");
			assertNotEquals(threadId, Thread.currentThread().getId());
			return CompletableFuture.completedFuture(s + " ==>> composed");
		}).thenRunAsync(() -> {
			printThreadContext("Future.thenRunAsync");
			assertNotEquals(threadId, Thread.currentThread().getId());
		}).join();
	}

	@Test
	void testVertxCompletableFuture(Vertx vertx, VertxTestContext context) {
		var ctx = vertx.getOrCreateContext();
		Promise<String> promise = Promise.promise();

		Variable<Long> tid = Variable.empty();

		ctx.runOnContext(v -> {
			tid.set(Thread.currentThread().getId());

			try {
				TimeUnit.SECONDS.sleep(1);
			} catch (InterruptedException e) {
				promise.fail(e);
			}

			printThreadContext("Context.runOnContext");
			promise.complete("Hello future");
		});

		Future<String> future = promise.future().andThen(ar -> {
			printThreadContext("Future.andThen");
			context.verify(() -> assertEquals(tid.get(), Thread.currentThread().getId()));
		}).map(s -> {
			printThreadContext("Future.map");
			context.verify(() -> assertEquals(tid.get(), Thread.currentThread().getId()));
			return s + " ==>> mapped";
		}).compose(s -> {
			printThreadContext("Future.compose");
			context.verify(() -> assertEquals(tid.get(), Thread.currentThread().getId()));
			Promise<String> p = Promise.promise();
			ctx.runOnContext(v -> {
				printThreadContext("Future.compose::Context.runOnContext");
				p.complete(s + " ==>> composed");
			});
			return p.future();
		});

		CompletableFuture<String> cf = VertxFuture.of(future);

		cf.thenApply(s -> {
			printThreadContext("CompletableFuture.thenApply");
			context.verify(() -> assertEquals(tid.get(), Thread.currentThread().getId()));
			return s + " ==>> thenApply";
		}).thenCompose(v -> {
			printThreadContext("CompletableFuture.thenCompose");
			context.verify(() -> assertEquals(tid.get(), Thread.currentThread().getId()));
			return VertxFuture.succeededFuture(v);
		}).thenAcceptAsync(s -> {
			printThreadContext("CompletableFuture.thenAcceptAsync");
			context.verify(() -> {
				assertNotEquals(tid.get(), Thread.currentThread().getId());
				assertTrue(Thread.currentThread().getName().startsWith("vert.x-worker-thread-"));
			});
		}).thenCompose(v -> {
			printThreadContext("CompletableFuture.thenCompose");
			context.verify(() -> {
				assertNotEquals(tid.get(), Thread.currentThread().getId());
				assertTrue(Thread.currentThread().getName().startsWith("vert.x-worker-thread-"));
			});
			return VertxFuture.succeededFuture(v);
		}).thenComposeAsync(v -> {
			printThreadContext("CompletableFuture.thenComposeAsync");
			context.verify(() -> {
				assertNotEquals(tid.get(), Thread.currentThread().getId());
				assertTrue(Thread.currentThread().getName().startsWith("vert.x-worker-thread-"));
			});
			return CompletableFuture.completedFuture(null);
		}).thenCompose(v -> {
			printThreadContext("CompletableFuture.thenCompose");
			context.verify(() -> {
				assertNotEquals(tid.get(), Thread.currentThread().getId());
				assertTrue(Thread.currentThread().getName().startsWith("vert.x-worker-thread-"));
			});
			return VertxFuture.succeededFuture(v);
		});

		future.onComplete(context.succeedingThenComplete());
	}

	@Test
	void testVertxCompletableFutureGetCompleted() throws Exception {
		VertxFuture<String> future = VertxFuture.succeededFuture("Foo bar");
		assertEquals("Foo bar", future.get());
	}

	@Test
	void testVertxCompletableFutureGet(Vertx vertx, VertxTestContext context) throws Exception {
		var ctx = vertx.getOrCreateContext();

		Promise<String> promise = Promise.promise();
		ctx.runOnContext(v -> {
			try {
				TimeUnit.MILLISECONDS.sleep(1900);
				promise.complete("Foo bar");
			} catch (InterruptedException e) {
				promise.fail(e);
			}
		});

		VertxFuture<String> future = VertxFuture.of(promise.future());
		assertEquals("Foo bar", future.get());
		context.completeNow();
	}

	@Test
	void testVertxCompletableFutureGetInVertxContext(Vertx vertx, VertxTestContext context) {
		var ctx = vertx.getOrCreateContext();

		Promise<String> promise = Promise.promise();
		ctx.runOnContext(v -> {
			try {
				TimeUnit.MILLISECONDS.sleep(1900);
				promise.complete("Foo bar");
			} catch (InterruptedException e) {
				promise.fail(e);
			}
		});

		VertxFuture<String> future = VertxFuture.of(promise.future());
		ctx.runOnContext(v -> {
			printThreadContext("context.verify");

			IllegalStateException exception = assertThrows(IllegalStateException.class, future::get);
	        assertEquals("Cannot not be called on vertx thread or event loop thread", exception.getMessage());
			context.completeNow();
		});
	}
}