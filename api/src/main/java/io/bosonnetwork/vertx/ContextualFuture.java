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

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.internal.FutureInternal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/**
 * ContextualFuture is a {@link CompletableFuture}-compatible wrapper around Vert.x's {@link io.vertx.core.Future}.
 * <p>
 * It provides interoperability between the Vert.x asynchronous programming model and the Java
 * {@link CompletableFuture}/{@link CompletionStage} APIs. This allows developers to:
 * <ul>
 *   <li>Use familiar CompletableFuture-style composition methods with Vert.x futures</li>
 *   <li>Integrate Vert.x async results into standard Java concurrency flows</li>
 *   <li>Optionally use blocking-style methods (e.g., {@link #get()}) outside the Vert.x event loop</li>
 * </ul>
 *
 * <p>It honours the {@link CompletableFuture} contract for completing a future from outside:
 * {@link #complete(Object)}, {@link #completeExceptionally(Throwable)}, {@link #orTimeout(long, TimeUnit)},
 * {@link #completeOnTimeout(Object, long, TimeUnit)}, {@link #cancel(boolean)} and the {@code obtrude}
 * methods all act on this future, whatever produces its result. Completing it that way - a timeout, a
 * cancellation - does not stop the operation behind it; its result, when it comes, is ignored.
 *
 * <p>Dependent stages that are not {@code Async} run on the thread that completes the future: for an
 * operation of the Vert.x runtime, typically an event loop. Callers outside Vert.x that do blocking work in
 * them should use the {@code Async} variants with their own executor.
 *
 * <p><strong>Note:</strong> Blocking methods like {@code get()} and {@code join()} must never be called on
 * Vert.x event loop or worker threads, as this will block the reactive runtime.
 *
 * @param <T> the result type
 */

@NullUnmarked
public class ContextualFuture<T> extends CompletableFuture<T> implements java.util.concurrent.Future<T>, CompletionStage<T> {
	// Completes the futures of orTimeout and completeOnTimeout. A single daemon thread that only
	// completes futures; the dependent stages run where the future completes them, as for any completion.
	private static final ScheduledThreadPoolExecutor delayer;
	static {
		delayer = new ScheduledThreadPoolExecutor(1, r -> {
			Thread t = new Thread(r, "ContextualFuture-delayer");
			t.setDaemon(true);
			return t;
		});
		delayer.setRemoveOnCancelPolicy(true);
	}

	/**
	 * The Vert.x Future holding this future's state: a promise this future owns, which the wrapped future
	 * completes - so that the future can also be completed from outside, as a CompletableFuture can. Only
	 * the obtrude methods replace it.
	 */
	volatile @NonNull Future<T> future;

	// The exception this future was cancelled with, if it was. Dependent stages fail with it too, but are
	// not themselves cancelled - as with CompletableFuture - so cancellation is recognised by identity.
	private volatile @Nullable CancellationException cancellation;

	/**
	 * Wraps an existing Vert.x {@link Future} into a ContextualFuture.
	 * Updates the internal state of this CompletableFuture whenever the Vert.x Future completes.
	 *
	 * @param source the Vert.x Future to wrap
	 */
	protected ContextualFuture(@NonNull Future<T> source) {
		Future<T> own;
		if (source instanceof Promise<?>) {
			// Already a promise: completing it from outside works as it is.
			own = source;
		} else {
			// Anything else is completed by whatever produces it, and by nothing else. Own a promise it
			// completes, so that complete(), cancel() and the timeouts can complete this future first; the
			// source's result then arrives too late and is ignored. The promise is bound to the source's
			// context, as the source is: dependent stages then run on that context, whichever thread
			// completes the future - which is what keeps a call made on a context completing on it.
			Promise<T> promise = promiseOn(source);
			source.onComplete(ar -> {
				if (ar.succeeded())
					promise.tryComplete(ar.result());
				else
					promise.tryFail(ar.cause());
			});
			own = promise.future();
		}

		this.future = own;
		own.andThen(ar -> {
			// update the internal state of CompletableFuture
			if (ar.succeeded())
				super.complete(ar.result());
			else
				super.completeExceptionally(ar.cause());
		});
	}

	// A promise bound to the context of the given future, if it has one.
	@SuppressWarnings("unchecked")
	private static <T> Promise<T> promiseOn(Future<T> future) {
		if (future instanceof FutureInternal<?> internal) {
			ContextInternal context = internal.context();
			if (context != null)
				return context.promise();
		}
		return Promise.promise();
	}

	/**
	 * Creates a ContextualFuture wrapper around an existing Vert.x Future.
	 *
	 * @param future the Vert.x Future
	 * @param <T>    the type of the ContextualFuture result
	 * @return a new ContextualFuture wrapping the given future
	 */
	public static <T> @NonNull ContextualFuture<T> of(@NonNull Future<T> future) {
		return new ContextualFuture<>(future);
	}

	/**
	 * Converts a {@link CompletableFuture} into a {@link ContextualFuture}.
	 * If the provided {@link CompletableFuture} is already an instance of {@link ContextualFuture},
	 * it is directly returned after being cast to the appropriate type.
	 * Otherwise, a new {@link ContextualFuture} is created.
	 *
	 * @param <T>    the type of the result in the future
	 * @param future the {@link CompletableFuture} to be converted
	 * @return a {@link ContextualFuture} representing the same computation or result as the provided {@link CompletableFuture}
	 */
	@SuppressWarnings("unchecked")
	public static <T> @NonNull ContextualFuture<T> of(@NonNull CompletableFuture<T> future) {
		if (future instanceof ContextualFuture<?> vf)
			return (ContextualFuture<T>) vf;

		return new ContextualFuture<>(Future.fromCompletionStage(future));
	}

	/**
	 * Creates a failed ContextualFuture from a Throwable.
	 *
	 * @param cause the cause of the failure
	 * @param <U>   the type of the ContextualFuture Future result
	 * @return a new ContextualFuture with a failure cause
	 */
	public static <U> @NonNull ContextualFuture<U> failedFuture(@NonNull Throwable cause) {
		return new ContextualFuture<>(Future.failedFuture(cause));
	}

	/**
	 * Creates a failed ContextualFuture from an error message.
	 *
	 * @param cause the error message of the failure
	 * @param <U>   the type of the ContextualFuture Future result
	 * @return a new ContextualFuture with a failure cause as a String.
	 */
	public static <U> @NonNull ContextualFuture<U> failedFuture(@NonNull String cause) {
		return new ContextualFuture<>(Future.failedFuture(cause));
	}

	/**
	 * Creates a successfully completed ContextualFuture with a {@code null} result.
	 *
	 * @param <U> the type of the ContextualFuture Future result
	 * @return a new ContextualFuture with a {@code null} result.
	 */
	public static <U> @NonNull ContextualFuture<U> succeededFuture() {
		return new ContextualFuture<>(Future.succeededFuture());
	}

	/**
	 * Creates a successfully completed ContextualFuture with the given result.
	 *
	 * @param result the result of the future
	 * @param <U>    the type of the ContextualFuture Future result
	 * @return a new ContextualFuture with the given result
	 */
	public static <U> @NonNull ContextualFuture<U> succeededFuture(U result) {
		return new ContextualFuture<>(Future.succeededFuture(result));
	}

	/**
	 * Like {@link #either(Future)} but value-agnostic: settles on the first of the two to complete.
	 */
	private static @NonNull Future<Void> eitherSettled(@NonNull Future<?> a, @NonNull Future<?> b) {
		Promise<Void> settled = Promise.promise();
		a.onComplete(ar -> {
			if (ar.succeeded())
				settled.tryComplete();
			else
				settled.tryFail(ar.cause());
		});
		b.onComplete(ar -> {
			if (ar.succeeded())
				settled.tryComplete();
			else
				settled.tryFail(ar.cause());
		});
		return settled.future();
	}

	/**
	 * Returns a new ContextualFuture that is completed when all the given futures complete.
	 *
	 * @param futures the futures to wait for
	 * @return a new ContextualFuture that is completed when all the given futures complete
	 */
	public static @NonNull ContextualFuture<Void> allOf(@NonNull ContextualFuture<?>... futures) {
		List<? extends Future<?>> vfs = Arrays.stream(futures).map(f -> f.future).toList();
		Future<Void> cf = Future.all(vfs).mapEmpty();
		return of(cf);
	}

	/**
	 * Returns a new ContextualFuture that is completed when all the given futures complete.
	 *
	 * @param futures the collection of futures to wait for
	 * @return a new ContextualFuture that is completed when all the given futures complete
	 */
	public static @NonNull ContextualFuture<Void> allOf(@NonNull Collection<ContextualFuture<?>> futures) {
		List<? extends Future<?>> vfs = futures.stream().map(f -> f.future).toList();
		Future<Void> cf = Future.all(vfs).mapEmpty();
		return of(cf);
	}

	/**
	 * Returns a new ContextualFuture that is completed when any of the given futures succeed.
	 *
	 * @param futures the futures to wait for
	 * @return a new ContextualFuture that is completed when any of the given futures succeed
	 */
	public static @NonNull ContextualFuture<Void> anyOf(@NonNull ContextualFuture<?>... futures) {
		List<? extends Future<?>> vfs = Arrays.stream(futures).map(f -> f.future).toList();
		Future<Void> cf = Future.any(vfs).mapEmpty();
		return of(cf);
	}

	/**
	 * Returns a new ContextualFuture that is completed when any of the given futures succeed.
	 *
	 * @param futures the collection of futures to wait for
	 * @return a new ContextualFuture that is completed when any of the given futures succeed
	 */
	public static @NonNull ContextualFuture<Void> anyOf(@NonNull Collection<ContextualFuture<?>> futures) {
		List<? extends Future<?>> vfs = futures.stream().map(f -> f.future).toList();
		Future<Void> cf = Future.any(vfs).mapEmpty();
		return of(cf);
	}

	@Override
	public @NonNull Executor defaultExecutor() {
		Context context = Vertx.currentContext();
		if (context != null) {
			return (r) -> context.executeBlocking(() -> {
				r.run();
				return null;
			});
		} else {
			return super.defaultExecutor();
		}
	}

	@Override
	public <U> @NonNull ContextualFuture<U> thenApply(@NonNull Function<? super T, ? extends U> fn) {
		Future<U> mapper = future.map(fn::apply);
		return of(mapper);
	}

	@Override
	public <U> @NonNull ContextualFuture<U> thenApplyAsync(@NonNull Function<? super T, ? extends U> fn) {
		return thenApplyAsync(fn, defaultExecutor());
	}

	@Override
	public <U> @NonNull ContextualFuture<U> thenApplyAsync(@NonNull Function<? super T, ? extends U> fn, @NonNull Executor executor) {
		Future<U> composer = future.compose(t -> {
			Promise<U> promise = Promise.promise();
			executor.execute(() -> {
				try {
					U u = fn.apply(t);
					promise.complete(u);
				} catch (Throwable e) {
					promise.fail(e);
				}
			});
			return promise.future();
		});

		return of(composer);
	}

	@Override
	public @NonNull ContextualFuture<Void> thenAccept(@NonNull Consumer<? super T> action) {
		return thenApply(t -> {
			action.accept(t);
			return null;
		});
	}

	@Override
	public @NonNull ContextualFuture<Void> thenAcceptAsync(@NonNull Consumer<? super T> action) {
		return thenAcceptAsync(action, defaultExecutor());
	}

	@Override
	public @NonNull ContextualFuture<Void> thenAcceptAsync(@NonNull Consumer<? super T> action, @NonNull Executor executor) {
		return thenApplyAsync(t -> {
			action.accept(t);
			return null;
		}, executor);
	}

	@Override
	public @NonNull ContextualFuture<Void> thenRun(@NonNull Runnable action) {
		return thenApply(t -> {
			action.run();
			return null;
		});
	}

	@Override
	public @NonNull ContextualFuture<Void> thenRunAsync(@NonNull Runnable action) {
		return thenRunAsync(action, defaultExecutor());
	}

	@Override
	public @NonNull ContextualFuture<Void> thenRunAsync(@NonNull Runnable action, @NonNull Executor executor) {
		return thenApplyAsync(t -> {
			action.run();
			return null;
		}, executor);
	}

	@Override
	public <U, V> @NonNull ContextualFuture<V> thenCombine(@NonNull CompletionStage<? extends U> other,
	                                                       @NonNull BiFunction<? super T, ? super U, ? extends V> fn) {
		Future<? extends U> otherFuture = Future.fromCompletionStage(other);
		// The behavior of Future.all is similar to CompletableFuture.thenCombine...
		Future<V> mapper = Future.all(future, otherFuture).map(cf -> {
			T t = future.result();
			U u = otherFuture.result();
			return fn.apply(t, u);
		});

		return of(mapper);
	}

	@Override
	public <U, V> @NonNull ContextualFuture<V> thenCombineAsync(@NonNull CompletionStage<? extends U> other,
	                                                            @NonNull BiFunction<? super T, ? super U, ? extends V> fn) {
		return thenCombineAsync(other, fn, defaultExecutor());
	}

	@Override
	public <U, V> @NonNull ContextualFuture<V> thenCombineAsync(@NonNull CompletionStage<? extends U> other,
	                                                            @NonNull BiFunction<? super T, ? super U, ? extends V> fn,
	                                                            @NonNull Executor executor) {
		Future<? extends U> otherFuture = Future.fromCompletionStage(other);
		// The behavior of Future.all is similar to CompletableFuture.thenCombine...
		Future<V> composer = Future.all(future, otherFuture).compose(cf -> {
			Promise<V> promise = Promise.promise();
			executor.execute(() -> {
				try {
					T t = future.result();
					U u = otherFuture.result();
					V v = fn.apply(t, u);
					promise.complete(v);
				} catch (Throwable e) {
					promise.fail(e);
				}
			});
			return promise.future();
		});

		return of(composer);
	}

	@Override
	public <U> @NonNull ContextualFuture<Void> thenAcceptBoth(@NonNull CompletionStage<? extends U> other,
	                                                          @NonNull BiConsumer<? super T, ? super U> action) {
		return thenCombine(other, (t, u) -> {
			action.accept(t, u);
			return null;
		});
	}

	@Override
	public <U> @NonNull ContextualFuture<Void> thenAcceptBothAsync(@NonNull CompletionStage<? extends U> other,
	                                                               @NonNull BiConsumer<? super T, ? super U> action) {
		return thenAcceptBothAsync(other, action, defaultExecutor());
	}

	@Override
	public <U> @NonNull ContextualFuture<Void> thenAcceptBothAsync(@NonNull CompletionStage<? extends U> other,
	                                                               @NonNull BiConsumer<? super T, ? super U> action,
	                                                               @NonNull Executor executor) {
		return thenCombineAsync(other, (t, u) -> {
			action.accept(t, u);
			return null;
		}, executor);
	}

	@Override
	public @NonNull ContextualFuture<Void> runAfterBoth(@NonNull CompletionStage<?> other, @NonNull Runnable action) {
		Future<?> otherFuture = Future.fromCompletionStage(other);
		// The behavior of Future.all is similar to CompletableFuture.thenCombine...
		Future<Void> mapper = Future.all(future, otherFuture).map(cf -> {
			action.run();
			return null;
		});

		return of(mapper);
	}

	@Override
	public @NonNull ContextualFuture<Void> runAfterBothAsync(@NonNull CompletionStage<?> other, @NonNull Runnable action) {
		return runAfterBothAsync(other, action, defaultExecutor());
	}

	@Override
	public @NonNull ContextualFuture<Void> runAfterBothAsync(@NonNull CompletionStage<?> other, @NonNull Runnable action,
	                                                         @NonNull Executor executor) {
		Future<?> otherFuture = Future.fromCompletionStage(other);
		// The behavior of Future.all is similar to CompletableFuture.thenCombine...
		Future<Void> composer = Future.all(future, otherFuture).compose(cf -> {
			Promise<Void> promise = Promise.promise();
			executor.execute(() -> {
				try {
					action.run();
					promise.complete();
				} catch (Throwable e) {
					promise.fail(e);
				}
			});
			return promise.future();
		});

		return of(composer);
	}

	/**
	 * Completes with the outcome of whichever of {@code this}/{@code other} settles first - normally
	 * <em>or</em> exceptionally - matching {@link CompletableFuture}'s "either" semantics. (Note this
	 * differs from {@link Future#any} which waits for the first <em>success</em>.)
	 */
	private @NonNull Future<T> either(@NonNull Future<? extends T> other) {
		Promise<T> settled = Promise.promise();
		future.onComplete(ar -> {
			if (ar.succeeded())
				settled.tryComplete(ar.result());
			else
				settled.tryFail(ar.cause());
		});
		other.onComplete(ar -> {
			if (ar.succeeded())
				settled.tryComplete(ar.result());
			else
				settled.tryFail(ar.cause());
		});
		return settled.future();
	}

	@Override
	public <U> @NonNull ContextualFuture<U> applyToEither(@NonNull CompletionStage<? extends T> other, @NonNull Function<? super T, U> fn) {
		Future<? extends T> otherFuture = Future.fromCompletionStage(other);
		Future<U> mapper = either(otherFuture).map(fn);
		return of(mapper);
	}

	@Override
	public <U> @NonNull ContextualFuture<U> applyToEitherAsync(@NonNull CompletionStage<? extends T> other, @NonNull Function<? super T, U> fn) {
		return applyToEitherAsync(other, fn, defaultExecutor());
	}

	@Override
	public <U> @NonNull ContextualFuture<U> applyToEitherAsync(@NonNull CompletionStage<? extends T> other,
	                                                           @NonNull Function<? super T, U> fn, @NonNull Executor executor) {
		Future<? extends T> otherFuture = Future.fromCompletionStage(other);
		Future<U> composer = either(otherFuture).compose(t -> {
			Promise<U> promise = Promise.promise();
			executor.execute(() -> {
				try {
					promise.complete(fn.apply(t));
				} catch (Throwable e) {
					promise.fail(e);
				}
			});
			return promise.future();
		});

		return of(composer);
	}

	@Override
	public @NonNull ContextualFuture<Void> acceptEither(@NonNull CompletionStage<? extends T> other, @NonNull Consumer<? super T> action) {
		return applyToEither(other, (t) -> {
			action.accept(t);
			return null;
		});
	}

	@Override
	public @NonNull ContextualFuture<Void> acceptEitherAsync(@NonNull CompletionStage<? extends T> other, @NonNull Consumer<? super T> action) {
		return acceptEitherAsync(other, action, defaultExecutor());
	}

	@Override
	public @NonNull ContextualFuture<Void> acceptEitherAsync(@NonNull CompletionStage<? extends T> other,
	                                                         @NonNull Consumer<? super T> action,
	                                                         @NonNull Executor executor) {
		return applyToEitherAsync(other, (t) -> {
			action.accept(t);
			return null;
		}, executor);
	}

	@Override
	public @NonNull ContextualFuture<Void> runAfterEither(@NonNull CompletionStage<?> other, @NonNull Runnable action) {
		Future<?> otherFuture = Future.fromCompletionStage(other);
		Future<Void> mapper = eitherSettled(future, otherFuture).map(v -> {
			action.run();
			return null;
		});

		return of(mapper);
	}

	@Override
	public @NonNull ContextualFuture<Void> runAfterEitherAsync(@NonNull CompletionStage<?> other, @NonNull Runnable action) {
		return runAfterEitherAsync(other, action, defaultExecutor());
	}

	@Override
	public @NonNull ContextualFuture<Void> runAfterEitherAsync(@NonNull CompletionStage<?> other, @NonNull Runnable action,
	                                                           @NonNull Executor executor) {
		Future<?> otherFuture = Future.fromCompletionStage(other);
		Future<Void> composer = eitherSettled(future, otherFuture).compose(v -> {
			Promise<Void> promise = Promise.promise();
			executor.execute(() -> {
				try {
					action.run();
					promise.complete();
				} catch (Throwable e) {
					promise.fail(e);
				}
			});
			return promise.future();
		});

		return of(composer);
	}

	@Override
	public <U> @NonNull ContextualFuture<U> thenCompose(@NonNull Function<? super T, ? extends CompletionStage<U>> fn) {
		Future<U> composer = future.compose(t -> Future.fromCompletionStage(fn.apply(t)));
		return of(composer);
	}

	@Override
	public <U> @NonNull ContextualFuture<U> thenComposeAsync(@NonNull Function<? super T, ? extends CompletionStage<U>> fn) {
		return thenComposeAsync(fn, defaultExecutor());
	}

	@Override
	public <U> @NonNull ContextualFuture<U> thenComposeAsync(@NonNull Function<? super T, ? extends CompletionStage<U>> fn,
	                                                         @NonNull Executor executor) {
		Future<U> composer = future.compose(t -> {
			Promise<U> promise = Promise.promise();
			executor.execute(() -> {
				try {
					fn.apply(t).whenComplete((value, err) -> {
						if (err != null) {
							promise.fail(err);
						} else {
							promise.complete(value);
						}
					});
				} catch (Throwable e) {
					promise.fail(e);
				}
			});
			return promise.future();
		});

		return of(composer);
	}

	@Override
	public <U> @NonNull ContextualFuture<U> handle(@NonNull BiFunction<? super T, Throwable, ? extends U> fn) {
		Future<U> handle = future.transform(ar -> {
			U u = fn.apply(ar.result(), ar.cause());
			return Future.succeededFuture(u);
		});

		return of(handle);
	}

	@Override
	public <U> @NonNull ContextualFuture<U> handleAsync(@NonNull BiFunction<? super T, Throwable, ? extends U> fn) {
		return handleAsync(fn, defaultExecutor());
	}

	@Override
	public <U> @NonNull ContextualFuture<U> handleAsync(@NonNull BiFunction<? super T, Throwable, ? extends U> fn,
	                                                    @NonNull Executor executor) {
		Future<U> handle = future.transform(ar -> {
			Promise<U> promise = Promise.promise();
			executor.execute(() -> {
				try {
					U u = fn.apply(ar.result(), ar.cause());
					promise.complete(u);
				} catch (Throwable e) {
					promise.fail(e);
				}
			});
			return promise.future();
		});

		return of(handle);
	}

	@Override
	public @NonNull ContextualFuture<T> whenComplete(@NonNull BiConsumer<? super T, ? super Throwable> action) {
		// Reference: API doc of CompletableFuture.whenComplete
		//
		// Unlike method handle, this method is not designed to translate completion
		// outcomes, so the supplied action should not throw an exception. However, if
		// it does, the following rules apply: if this stage completed normally but the
		// supplied action throws an exception, then the returned stage completes
		// exceptionally with the supplied action's exception. Or, if this stage
		// completed exceptionally and the supplied action throws an exception, then the
		// returned stage completes exceptionally with this stage's exception.
		Future<T> handle = future.andThen(ar -> {
			try {
				action.accept(ar.result(), ar.cause());
			} catch (Throwable e) {
				Throwable cause = ar.failed() ? ar.cause() : e;
				if (cause instanceof Error ee)
					throw ee;
				else if (cause instanceof RuntimeException re)
					throw re;
				else
					throw new CompletionException(cause);
			}
		});

		return of(handle);
	}

	@Override
	public @NonNull ContextualFuture<T> whenCompleteAsync(@NonNull BiConsumer<? super T, ? super Throwable> action) {
		return whenCompleteAsync(action, defaultExecutor());
	}

	@Override
	public @NonNull ContextualFuture<T> whenCompleteAsync(@NonNull BiConsumer<? super T, ? super Throwable> action,
	                                                      @NonNull Executor executor) {
		// Reference: API doc of CompletableFuture.whenCompleteAsync
		//
		// Unlike method handle, this method is not designed to translate completion
		// outcomes, so the supplied action should not throw an exception. However, if
		// it does, the following rules apply: if this stage completed normally but the
		// supplied action throws an exception, then the returned stage completes
		// exceptionally with the supplied action's exception. Or, if this stage
		// completed exceptionally and the supplied action throws an exception, then the
		// returned stage completes exceptionally with this stage's exception.
		Future<T> handle = future.transform(ar -> {
			Promise<T> promise = Promise.promise();
			executor.execute(() -> {
				try {
					action.accept(ar.result(), ar.cause());
					if (ar.succeeded())
						promise.complete(ar.result());
					else
						promise.fail(ar.cause());
				} catch (Throwable e) {
					Throwable cause = ar.failed() ? ar.cause() : e;
					promise.fail(cause);
				}
			});
			return promise.future();
		});

		return of(handle);
	}

	@Override
	public @NonNull ContextualFuture<T> exceptionally(@NonNull Function<Throwable, ? extends T> fn) {
		Future<T> otherwise = future.otherwise(fn::apply);

		return of(otherwise);
	}

	@Override
	public @NonNull ContextualFuture<T> exceptionallyAsync(@NonNull Function<Throwable, ? extends T> fn) {
		return exceptionallyAsync(fn, defaultExecutor());
	}

	@Override
	public @NonNull ContextualFuture<T> exceptionallyAsync(@NonNull Function<Throwable, ? extends T> fn, @NonNull Executor executor) {
		Future<T> mapper = future.recover(e -> {
			Promise<T> promise = Promise.promise();
			executor.execute(() -> {
				try {
					T t = fn.apply(e);
					promise.complete(t);
				} catch (Throwable ee) {
					promise.fail(ee);
				}
			});
			return promise.future();
		});

		return of(mapper);
	}

	@Override
	public @NonNull ContextualFuture<T> exceptionallyCompose(@NonNull Function<Throwable, ? extends CompletionStage<T>> fn) {
		Future<T> mapper = future.recover(e -> Future.fromCompletionStage(fn.apply(e)));

		return of(mapper);
	}

	@Override
	public @NonNull ContextualFuture<T> exceptionallyComposeAsync(@NonNull Function<Throwable, ? extends CompletionStage<T>> fn) {
		return exceptionallyComposeAsync(fn, defaultExecutor());
	}

	@Override
	public @NonNull ContextualFuture<T> exceptionallyComposeAsync(@NonNull Function<Throwable, ? extends CompletionStage<T>> fn,
	                                                              @NonNull Executor executor) {
		Future<T> mapper = future.recover(e -> {
			Promise<T> promise = Promise.promise();
			executor.execute(() -> {
				try {
					fn.apply(e).whenComplete((value, err) -> {
						if (err != null) {
							promise.fail(err);
						} else {
							promise.complete(value);
						}
					});
				} catch (Throwable ee) {
					promise.fail(ee);
				}
			});
			return promise.future();
		});

		return of(mapper);
	}

	@Override
	public <U> @NonNull ContextualFuture<U> newIncompleteFuture() {
		Promise<U> promise = Promise.promise();
		return of(promise.future());
	}

	@Override
	public @NonNull CompletableFuture<T> toCompletableFuture() {
		return this;
	}

	/**
	 * Converts this wrapper back into the underlying Vert.x Future.
	 *
	 * @return the underlying Vert.x Future.
	 */
	public @NonNull Future<T> toVertxFuture() {
		return future;
	}

	/**
	 * Cancels this future, if it is not complete yet: it completes exceptionally with a
	 * {@link CancellationException}. As for a CompletableFuture, the operation that would have completed it
	 * is not interrupted; its result, when it comes, is ignored.
	 *
	 * @param mayInterruptIfRunning ignored, as for a CompletableFuture
	 * @return {@code true} if this future is now cancelled
	 */
	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		CancellationException ce = new CancellationException();
		Future<T> f = future;
		if (f instanceof Promise<?> promise && promise.tryFail(ce)) {
			cancellation = ce;
			return true;
		}
		return isCancelled();
	}

	@Override
	public boolean isCancelled() {
		Future<T> f = future;
		CancellationException ce = cancellation;
		return ce != null && f.failed() && f.cause() == ce;
	}

	@Override
	public boolean isDone() {
		return future.isComplete();
	}

	@Override
	public boolean isCompletedExceptionally() {
		return future.failed();
	}

	/**
	 * Blocks and waits for the result of this future.
	 * <p><strong>Warning:</strong> This method must not be called from a Vert.x event loop
	 * or worker thread, as it will block the reactive runtime.
	 *
	 * @return the result of the future or {@code null}
	 * @throws IllegalStateException if called on a Vert.x thread
	 * @throws ExecutionException    if the future completed exceptionally
	 * @throws InterruptedException  if the thread was interrupted
	 */
	@Override
	public T get() throws InterruptedException, ExecutionException {
		if (!future.isComplete()) {
			if (Context.isOnVertxThread() || Context.isOnEventLoopThread())
				throw new IllegalStateException("Cannot be called on a vertx thread or event loop thread");

			final CountDownLatch latch = new CountDownLatch(1);
			future.andThen(ar -> latch.countDown());
			latch.await();
		}

		return resultOrThrow();
	}

	/**
	 * Returns the result of the now-complete future, or throws its failure wrapped in an
	 * {@link ExecutionException}. A complete Vert.x future is always either succeeded or failed.
	 */
	private T resultOrThrow() throws ExecutionException {
		Future<T> f = future;
		if (f.succeeded())
			return f.result();
		else if (isCancelled())
			throw (CancellationException) f.cause();
		else
			throw new ExecutionException(f.cause());
	}

	/**
	 * Blocks and waits for the result of this future.
	 * <p><strong>Warning:</strong> This method must not be called from a Vert.x event loop
	 * or worker thread, as it will block the reactive runtime.
	 *
	 * @param timeout the maximum time to wait
	 * @param unit    the time unit of the timeout argument
	 * @return the result of the future or {@code null} if the specified waiting time elapses before
	 * @throws IllegalStateException if called on a Vert.x thread
	 * @throws ExecutionException    if the future completed exceptionally
	 * @throws InterruptedException  if the thread was interrupted
	 */
	@Override
	public T get(long timeout, @NonNull TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		if (!future.isComplete()) {
			if (Context.isOnVertxThread() || Context.isOnEventLoopThread())
				throw new IllegalStateException("Cannot be called on a vertx thread or event loop thread");

			final CountDownLatch latch = new CountDownLatch(1);
			future.andThen(ar -> latch.countDown());
			if (!latch.await(timeout, unit))
				throw new TimeoutException();
		}

		return resultOrThrow();
	}

	@Override
	public T join() {
		try {
			return get();
		} catch (ExecutionException e) {
			// As CompletableFuture.join: the failure itself, wrapped once.
			Throwable cause = e.getCause();
			throw cause instanceof CompletionException ce ? ce : new CompletionException(cause);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CompletionException(e);
		}
	}

	@Override
	public T getNow(T valueIfAbsent) {
		Future<T> f = future;
		if (!f.isComplete())
			return valueIfAbsent;
		if (f.succeeded())
			return f.result();
		if (isCancelled())
			throw (CancellationException) f.cause();

		Throwable cause = f.cause();
		throw cause instanceof CompletionException ce ? ce : new CompletionException(cause);
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean complete(T value) {
		// The state is always a promise this future owns (see the constructor), unless an obtrude method
		// replaced it with a completed future - which cannot be completed again. This relies on the Vert.x
		// contract that Promise.promise().future() returns the promise itself (true on Vert.x 4.x/5.x).
		Future<T> f = future;
		return f instanceof Promise<?> promise && ((Promise<T>) promise).tryComplete(value);
	}

	@Override
	public @NonNull ContextualFuture<T> completeAsync(@NonNull Supplier<? extends T> supplier, @NonNull Executor executor) {
		Objects.requireNonNull(supplier);
		Objects.requireNonNull(executor);
		executor.execute(() -> complete(supplier.get()));
		return this;
	}

	@Override
	public @NonNull ContextualFuture<T> completeAsync(@NonNull Supplier<? extends T> supplier) {
		return completeAsync(supplier, defaultExecutor());
	}

	@Override
	public boolean completeExceptionally(Throwable ex) {
		Objects.requireNonNull(ex);
		Future<T> f = future;
		return f instanceof Promise<?> promise && promise.tryFail(ex);
	}

	/**
	 * Completes this future with a {@link TimeoutException} if it is not complete within the given time,
	 * and returns it, as CompletableFuture does. The operation behind it is not stopped.
	 */
	@Override
	public @NonNull ContextualFuture<T> orTimeout(long timeout, @NonNull TimeUnit unit) {
		Objects.requireNonNull(unit);
		return completeAfter(timeout, unit, () -> completeExceptionally(new TimeoutException()));
	}

	/**
	 * Completes this future with the given value if it is not complete within the given time, and returns
	 * it, as CompletableFuture does. The operation behind it is not stopped.
	 */
	@Override
	public @NonNull ContextualFuture<T> completeOnTimeout(T value, long timeout, @NonNull TimeUnit unit) {
		Objects.requireNonNull(unit);
		return completeAfter(timeout, unit, () -> complete(value));
	}

	// Runs the completion after the delay unless this future completes first, in which case the timer is
	// dropped at once rather than kept until it would have fired.
	private ContextualFuture<T> completeAfter(long timeout, TimeUnit unit, Runnable completion) {
		if (!future.isComplete()) {
			ScheduledFuture<?> timer = delayer.schedule(completion, timeout, unit);
			future.onComplete(ar -> timer.cancel(false));
		}
		return this;
	}

	/**
	 * Forcibly sets the result of this future, whether or not it is already complete, as CompletableFuture
	 * does. Meant for error recovery; stages that already ran are not run again.
	 */
	@Override
	public void obtrudeValue(T value) {
		future = Future.succeededFuture(value);
		super.obtrudeValue(value);
	}

	/**
	 * Forcibly makes this future fail, whether or not it is already complete, as CompletableFuture does.
	 * Meant for error recovery; stages that already ran are not run again.
	 */
	@Override
	public void obtrudeException(Throwable ex) {
		Objects.requireNonNull(ex);
		future = Future.failedFuture(ex);
		super.obtrudeException(ex);
	}

	@Override
	public @NonNull ContextualFuture<T> copy() {
		Promise<T> promise = Promise.promise();
		future.andThen(promise);
		return of(promise.future());
	}

	@Override
	public @NonNull CompletionStage<T> minimalCompletionStage() {
		return new MinimalStage<>(future);
	}

	/**
	 * A reduced view of ContextualFuture that exposes only {@link CompletionStage} operations,
	 * disabling mutation methods such as {@code complete()}, {@code cancel()}, etc.
	 * <p>
	 * This is used by {@link #minimalCompletionStage()} to comply with the
	 * {@link CompletableFuture#minimalCompletionStage()} contract.
	 */
	static final class MinimalStage<T> extends ContextualFuture<T> {
		MinimalStage(@NonNull Future<T> future) {
			super(future);
		}

		@Override
		public T get() {
			throw new UnsupportedOperationException();
		}

		@Override
		public T get(long timeout, @NonNull TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public T getNow(T valueIfAbsent) {
			throw new UnsupportedOperationException();
		}

		@Override
		public T join() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean complete(T value) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean completeExceptionally(Throwable ex) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			throw new UnsupportedOperationException();
		}

		/*/
		@Override
		public void obtrudeValue(T value) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void obtrudeException(Throwable ex) {
			throw new UnsupportedOperationException();
		}
		*/

		@Override
		public boolean isDone() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isCancelled() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean isCompletedExceptionally() {
			throw new UnsupportedOperationException();
		}

		@Override
		public int getNumberOfDependents() {
			throw new UnsupportedOperationException();
		}

		@Override
		public @NonNull MinimalStage<T> completeAsync(@NonNull Supplier<? extends T> supplier, @NonNull Executor executor) {
			throw new UnsupportedOperationException();
		}

		@Override
		public @NonNull MinimalStage<T> completeAsync(@NonNull Supplier<? extends T> supplier) {
			throw new UnsupportedOperationException();
		}

		@Override
		public @NonNull MinimalStage<T> orTimeout(long timeout, @NonNull TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public @NonNull MinimalStage<T> completeOnTimeout(@NonNull T value, long timeout, @NonNull TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public @NonNull CompletableFuture<T> toCompletableFuture() {
			Promise<T> promise = Promise.promise();
			future.andThen(promise);
			return of(promise.future());
		}
	}
}
