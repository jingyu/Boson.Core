package io.bosonnetwork.utils.vertx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.bosonnetwork.utils.Variable;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

@ExtendWith(VertxExtension.class)
public class VertxFutureTests {
	private static void printThreadContext(String prefix) {
		System.out.printf("%s: %s:%d\n", String.valueOf(prefix),
				Thread.currentThread().getName(), Thread.currentThread().getId());
	}

	@Test
	void testFuture(Vertx vertx, VertxTestContext context) {
		ContextInternal ctx = (ContextInternal)vertx.getOrCreateContext();

		Promise<String> promise = ctx.promise();

		ctx.runOnContext(v -> {
			try {
				TimeUnit.SECONDS.sleep(1);
			} catch (InterruptedException e) {
				promise.fail(e);
			}

			printThreadContext("Vertx.runOnContext");
			promise.complete("Hello future");
		});

		promise.future().andThen(ar -> {
			printThreadContext("Future.andThen");
		}).map(s -> {
			printThreadContext("Future.map");
			return s + " ==>> mapped";
		}).compose(s -> {
			printThreadContext("Future.compose");
			return Future.succeededFuture(s + " ==>> composed");
		}).onComplete(context.succeedingThenComplete());
	}

	@Test
	void testCompletableFuture() {
		CompletableFuture<String> future = CompletableFuture.completedFuture("Foo bar");

		future.thenApply(s -> {
			printThreadContext("Future.thenApply");
			return s + " ==>> applied";
		}).thenCompose(s -> {
			printThreadContext("Future.thenCompose");
			return CompletableFuture.completedFuture(s + " ==>> composed");
		}).thenAccept(s -> {
			printThreadContext("Future.thenAccept");
		}).thenRun(() -> {
			printThreadContext("Future.thenRun");
		}).thenApplyAsync(s -> {
			printThreadContext("Future.thenApplyAsync");
			return s + " ==>> appliedAsync";
		}).thenComposeAsync(s -> {
			printThreadContext("Future.thenComposeAsync");
			return CompletableFuture.completedFuture(s + " ==>> composedAsync");
		}).thenRunAsync(() -> {
			printThreadContext("Future.thenRunAsync");
		}).join();
	}

	@Test
	void testVertxCompletableFuture(Vertx vertx, VertxTestContext context) {
		ContextInternal ctx = (ContextInternal)vertx.getOrCreateContext();
		Promise<String> promise = ctx.promise();

		Variable<Long> tid = new Variable<>();

		vertx.runOnContext(v -> {
			try {
				TimeUnit.SECONDS.sleep(1);
			} catch (InterruptedException e) {
				promise.fail(e);
			}

			printThreadContext("Vertx.runOnContext");
			tid.set(Thread.currentThread().getId());
			promise.complete("Hello future");
		});

		Future<String> future = promise.future().andThen(ar -> {
			printThreadContext("Future.andThen");
			context.verify(() -> {
				assertEquals(tid.get(), Thread.currentThread().getId());
			});
		}).map(s -> {
			printThreadContext("Future.map");
			context.verify(() -> {
				assertEquals(tid.get(), Thread.currentThread().getId());
			});
			return s + " ==>> mapped";
		}).compose(s -> {
			printThreadContext("Future.compose");
			context.verify(() -> {
				assertEquals(tid.get(), Thread.currentThread().getId());
			});
			Promise<String> p = ctx.promise();
			vertx.runOnContext(v -> {
				printThreadContext("Future.compose::vertx.runOnContext");
				p.complete(s + " ==>> composed");
			});
			return p.future();
		});

		CompletableFuture<String> cf = VertxFuture.of(future);

		cf.thenApply(s -> {
			printThreadContext("CompletableFuture.thenApply");
			context.verify(() -> {
				assertEquals(tid.get(), Thread.currentThread().getId());
			});
			return s + " ==>> thenApply";
		}).thenCompose(v -> {
			printThreadContext("CompletableFuture.thenCompose");
			context.verify(() -> {
				assertEquals(tid.get(), Thread.currentThread().getId());
			});
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
		Promise<String> promise = Promise.promise();
		vertx.runOnContext(v -> {
			try {
				TimeUnit.SECONDS.sleep(3);
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
	void testVertxCompletableFutureGetInVertxContext(Vertx vertx, VertxTestContext context) throws Exception {
		Promise<String> promise = Promise.promise();
		vertx.runOnContext(v -> {
			try {
				TimeUnit.SECONDS.sleep(3);
				promise.complete("Foo bar");
			} catch (InterruptedException e) {
				promise.fail(e);
			}
		});

		VertxFuture<String> future = VertxFuture.of(promise.future());
		vertx.runOnContext(v -> {
			printThreadContext("context.verify");

			IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
				future.get();
	        });
	        assertEquals("Cannot be called on a Vert.x event-loop thread", exception.getMessage());
			context.completeNow();
		});
	}
}
