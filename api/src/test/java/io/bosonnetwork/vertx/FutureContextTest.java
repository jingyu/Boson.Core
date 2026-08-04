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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

/**
 * Pins the Vert.x completion-context contract that cross-context handoffs depend on.
 * <p>
 * When a promise created on verticle A is completed from verticle B's context, where the
 * {@code onComplete} handler runs depends entirely on how the promise was created:
 * </p>
 * <ul>
 *   <li>a bare {@link Promise#promise()} carries no context, so the handler runs inline on
 *       B's event loop - work intended for A silently leaks onto a foreign event loop;</li>
 *   <li>a context-bound promise ({@link BosonVerticle#promise()}) dispatches the handler back
 *       onto A's context, which is almost always what the caller intends.</li>
 * </ul>
 * <p>
 * This is third-party behavior we rely on rather than behavior we implement, so it is pinned
 * here: if a Vert.x upgrade changes it, these tests fail instead of the DHT quietly walking a
 * sibling's single-threaded routing table from the wrong event loop.
 * </p>
 */
@ExtendWith(VertxExtension.class)
public class FutureContextTest {
	/**
	 * A minimal verticle that exposes its own context and its context-bound promise factory.
	 */
	private static class TestVerticle extends BosonVerticle {
		@Override
		protected Future<Void> deploy() {
			return Future.succeededFuture();
		}

		@Override
		protected Future<Void> undeploy() {
			return Future.succeededFuture();
		}

		Context context() {
			return vertxContext;
		}

		<T> Promise<T> boundPromise() {
			return promise();
		}
	}

	/**
	 * Creates a promise on verticle A, completes it from verticle B's context, and asserts which
	 * context the completion handler ran on.
	 *
	 * @param vertx           the Vert.x instance.
	 * @param testContext     the test context.
	 * @param promiseFactory  builds the promise under test, given verticle A.
	 * @param expectRunOnA    true if the handler is expected on A's context, false for B's.
	 */
	private void assertCompletionContext(Vertx vertx, VertxTestContext testContext,
			Function<TestVerticle, Promise<String>> promiseFactory, boolean expectRunOnA) {
		TestVerticle a = new TestVerticle();
		TestVerticle b = new TestVerticle();

		// Deploy sequentially: both contexts must exist before the handoff is set up.
		vertx.deployVerticle(a)
				.compose(unused -> vertx.deployVerticle(b))
				.onFailure(testContext::failNow)
				.onSuccess(unused -> a.context().runOnContext(v -> {
					Context contextA = a.context();
					Context contextB = b.context();

					testContext.verify(() -> {
						assertNotNull(contextA);
						assertNotNull(contextB);
						assertSame(contextA, Vertx.currentContext(), "setup must run on A");
					});

					Promise<String> promise = promiseFactory.apply(a);

					promise.future().onComplete(ar -> testContext.verify(() -> {
						assertSame(expectRunOnA ? contextA : contextB, Vertx.currentContext(),
								"completion handler ran on the wrong context");
						testContext.completeNow();
					}));

					// Complete from B's context - the whole point of the test.
					contextB.runOnContext(unused2 -> {
						testContext.verify(() -> assertSame(contextB, Vertx.currentContext()));
						promise.complete("done");
					});
				}));
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void testFreePromiseCompletesOnCompletingContext(Vertx vertx, VertxTestContext testContext) {
		// A bare Promise.promise() has no context: the handler runs on whoever completes it.
		assertCompletionContext(vertx, testContext, a -> Promise.promise(), false);
	}

	@Test
	@Timeout(value = 30, unit = TimeUnit.SECONDS)
	void testBoundPromiseCompletesOnOwningContext(Vertx vertx, VertxTestContext testContext) {
		// BosonVerticle.promise() binds the promise to the verticle's own context.
		assertCompletionContext(vertx, testContext, TestVerticle::boundPromise, true);
	}
}
