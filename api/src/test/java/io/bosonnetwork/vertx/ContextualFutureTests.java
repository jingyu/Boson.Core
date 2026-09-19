package io.bosonnetwork.vertx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletionException;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.utils.Variable;

@ExtendWith(VertxExtension.class)
public class ContextualFutureTests {
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

		// NOTICE:
		// The Java SE API for CompletableFuture explicitly states this non-determinism for non-async methods:
		//   Actions supplied for dependent completions of non-async methods may be performed by the thread
		//   that completes the current CompletableFuture, or by any other thread.

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
			// main thread or ForkJoinPool.commonPool-worker-*
			assertTrue(threadId == Thread.currentThread().getId() || Thread.currentThread().getName().startsWith("ForkJoinPool.commonPool-worker-"));
			return CompletableFuture.completedFuture(s + " ==>> composed");
		}).thenComposeAsync(s -> {
			printThreadContext("Future.thenComposeAsync");
			assertNotEquals(threadId, Thread.currentThread().getId());
			return CompletableFuture.completedFuture(s + " ==>> composedAsync");
		}).thenCompose(s -> {
			printThreadContext("Future.thenCompose");
			// main thread or ForkJoinPool.commonPool-worker-*
			assertTrue(threadId == Thread.currentThread().getId() || Thread.currentThread().getName().startsWith("ForkJoinPool.commonPool-worker-"));
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

		CompletableFuture<String> cf = ContextualFuture.of(future);

		cf.thenApply(s -> {
			printThreadContext("CompletableFuture.thenApply");
			context.verify(() -> assertEquals(tid.get(), Thread.currentThread().getId()));
			return s + " ==>> thenApply";
		}).thenCompose(v -> {
			printThreadContext("CompletableFuture.thenCompose");
			context.verify(() -> assertEquals(tid.get(), Thread.currentThread().getId()));
			return ContextualFuture.succeededFuture(v);
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
			return ContextualFuture.succeededFuture(v);
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
			return ContextualFuture.succeededFuture(v);
		});

		future.onComplete(context.succeedingThenComplete());
	}

	@Test
	void testVertxCompletableFutureGetCompleted() throws Exception {
		ContextualFuture<String> future = ContextualFuture.succeededFuture("Foo bar");
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

		ContextualFuture<String> future = ContextualFuture.of(promise.future());
		assertEquals("Foo bar", future.get());
		context.completeNow();
	}

	@Test
	void testWhenCompleteContract() {
		// (1) success + action does not throw -> value passes through; action observes (value, null)
		var observed = new java.util.concurrent.atomic.AtomicReference<String>();
		Future<String> ok = ContextualFuture.<String>succeededFuture("v")
				.whenComplete((val, err) -> observed.set(val + "/" + err))
				.toVertxFuture();
		assertTrue(ok.succeeded());
		assertEquals("v", ok.result());
		assertEquals("v/null", observed.get());

		// (2) failure + action does not throw -> original cause propagates
		RuntimeException boom = new RuntimeException("boom");
		Future<String> failed = ContextualFuture.<String>failedFuture(boom)
				.whenComplete((val, err) -> { })
				.toVertxFuture();
		assertTrue(failed.failed());
		assertEquals(boom, failed.cause());

		// (3) success + action throws -> result fails with the action's exception
		RuntimeException actionEx = new RuntimeException("action");
		Future<String> successActionThrows = ContextualFuture.<String>succeededFuture("v")
				.whenComplete((val, err) -> { throw actionEx; })
				.toVertxFuture();
		assertTrue(successActionThrows.failed());
		assertEquals(actionEx, successActionThrows.cause());

		// (4) failure + action throws -> result keeps the ORIGINAL cause, not the action's
		Future<String> failureActionThrows = ContextualFuture.<String>failedFuture(boom)
				.whenComplete((val, err) -> { throw actionEx; })
				.toVertxFuture();
		assertTrue(failureActionThrows.failed());
		assertEquals(boom, failureActionThrows.cause());
	}

	@Test
	void testVertxCompletableFutureGetInVertxContext(Vertx vertx, VertxTestContext context) {
		var ctx = vertx.getOrCreateContext();

		Promise<String> promise = Promise.promise();
		vertx.setTimer(2000, id -> promise.complete("Foo bar"));

		ContextualFuture<String> future = ContextualFuture.of(promise.future());
		ctx.runOnContext(v -> {
			printThreadContext("context.verify");

			context.verify(() -> {
				IllegalStateException exception = assertThrows(IllegalStateException.class, future::get);
				assertEquals("Cannot be called on a vertx thread or event loop thread", exception.getMessage());
				context.completeNow();
			});
		});

		ContextualFuture<String> completedFuture = ContextualFuture.succeededFuture("Foo bar");
		ctx.runOnContext(v -> {
			context.verify(() -> assertEquals("Foo bar", completedFuture.join()));
		});
	}

	// A pending future that is not a promise: completed only by what produces it, like an HTTP call's.
	private static Future<String> pendingSource(Promise<String> producer) {
		Future<String> source = producer.future().map(v -> v);
		assertFalse(source instanceof Promise<?>, "the test needs a source that is not a promise");
		return source;
	}

	@Test
	void completeFromOutsideWinsOverTheSource() throws Exception {
		Promise<String> producer = Promise.promise();
		ContextualFuture<String> f = ContextualFuture.of(pendingSource(producer));
		CompletableFuture<String> dependent = f.thenApply(v -> v + "!");

		assertTrue(f.complete("outside"));
		assertTrue(f.isDone());
		assertEquals("outside", f.get());
		assertEquals("outside", f.getNow("pending"));
		assertEquals("outside!", dependent.get(5, TimeUnit.SECONDS));

		// The source's own result comes too late, and is ignored.
		producer.complete("source");
		assertEquals("outside", f.join());
		assertFalse(f.complete("again"));
	}

	@Test
	void completeExceptionallyFromOutside() {
		ContextualFuture<String> f = ContextualFuture.of(pendingSource(Promise.promise()));
		IllegalStateException failure = new IllegalStateException("outside");
		assertTrue(f.completeExceptionally(failure));
		assertTrue(f.isCompletedExceptionally());
		ExecutionException e = assertThrows(ExecutionException.class, f::get);
		assertSame(failure, e.getCause());
		CompletionException ce = assertThrows(CompletionException.class, f::join);
		assertSame(failure, ce.getCause());
	}

	@Test
	void orTimeoutFailsThisFuture() {
		ContextualFuture<String> f = ContextualFuture.of(pendingSource(Promise.promise()));
		assertSame(f, f.orTimeout(200, TimeUnit.MILLISECONDS));
		// The caller keeps waiting on the future it holds, not on a new one.
		ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
		assertInstanceOf(java.util.concurrent.TimeoutException.class, e.getCause());
	}

	@Test
	void completeOnTimeoutCompletesThisFuture() throws Exception {
		ContextualFuture<String> f = ContextualFuture.of(pendingSource(Promise.promise()));
		assertSame(f, f.completeOnTimeout("fallback", 200, TimeUnit.MILLISECONDS));
		assertEquals("fallback", f.get(5, TimeUnit.SECONDS));
	}

	@Test
	void aTimeoutDoesNotOverrideAnEarlierResult() throws Exception {
		Promise<String> producer = Promise.promise();
		ContextualFuture<String> f = ContextualFuture.of(pendingSource(producer))
				.orTimeout(300, TimeUnit.MILLISECONDS);
		producer.complete("in time");
		assertEquals("in time", f.get(5, TimeUnit.SECONDS));
		TimeUnit.MILLISECONDS.sleep(500);
		assertEquals("in time", f.get());
		assertFalse(f.isCompletedExceptionally());
	}

	@Test
	void cancelCancelsTheFuture() throws Exception {
		Promise<String> producer = Promise.promise();
		ContextualFuture<String> f = ContextualFuture.of(pendingSource(producer));
		CompletableFuture<String> dependent = f.thenApply(v -> v);

		assertTrue(f.cancel(false));
		assertTrue(f.isCancelled());
		assertTrue(f.isDone());
		assertThrows(java.util.concurrent.CancellationException.class, f::get);
		assertThrows(java.util.concurrent.CancellationException.class, f::join);
		ExecutionException e = assertThrows(ExecutionException.class, () -> dependent.get(5, TimeUnit.SECONDS));
		assertInstanceOf(java.util.concurrent.CancellationException.class, e.getCause());

		// Still cancelled after the operation behind it finishes; cancelling again reports so.
		producer.complete("late");
		assertTrue(f.isCancelled());
		assertTrue(f.cancel(true));

		// A future that already completed cannot be cancelled.
		ContextualFuture<String> done = ContextualFuture.succeededFuture("done");
		assertFalse(done.cancel(true));
		assertFalse(done.isCancelled());
		assertEquals("done", done.get());
	}

	@Test
	void obtrudeReplacesTheResult() throws Exception {
		ContextualFuture<String> f = ContextualFuture.succeededFuture("first");
		f.obtrudeValue("second");
		assertEquals("second", f.get());
		assertEquals("second", f.join());

		IllegalStateException failure = new IllegalStateException("third");
		f.obtrudeException(failure);
		assertTrue(f.isCompletedExceptionally());
		ExecutionException e = assertThrows(ExecutionException.class, f::get);
		assertSame(failure, e.getCause());
	}

	@Test
	void composedStagesComeBackToTheSourceContext(Vertx vertx, VertxTestContext testContext) {
		Context caller = vertx.getOrCreateContext();
		Context other = vertx.getOrCreateContext();
		caller.runOnContext(v -> {
			// A source bound to the caller's context, as any future made on it is, and not a promise.
			Promise<String> producer = ((ContextInternal) caller).promise();
			ContextualFuture<String> f = ContextualFuture.of(producer.future().map(r -> r));

			// The composed step finishes on another context - another component's, say - and the stage after
			// it still runs on the caller's: the Vert.x contract that keeps a call on its caller's context.
			f.thenCompose(r -> {
				Promise<String> elsewhere = ((ContextInternal) other).promise();
				other.runOnContext(x -> elsewhere.complete(r + " and back"));
				return ContextualFuture.of(elsewhere.future().map(x -> x));
			}).thenApply(r -> {
				testContext.verify(() -> assertSame(caller, Vertx.currentContext(),
						"the stage after the composed step runs on the caller's context"));
				return r;
			}).whenComplete((r, e) -> testContext.verify(() -> {
				assertEquals("there and back", r);
				testContext.completeNow();
			}));

			producer.complete("there");
		});
	}
}
