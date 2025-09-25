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

package io.bosonnetwork.utils.vertx;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
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

/**
 * VertxFuture is a {@link CompletableFuture}-compatible wrapper around Vert.x's {@link io.vertx.core.Future}.
 * <p>
 * It provides interoperability between the Vert.x asynchronous programming model and the Java
 * {@link CompletableFuture}/{@link CompletionStage} APIs. This allows developers to:
 * <ul>
 *   <li>Use familiar CompletableFuture-style composition methods with Vert.x futures</li>
 *   <li>Integrate Vert.x async results into standard Java concurrency flows</li>
 *   <li>Optionally use blocking-style methods (e.g., {@link #get()}) outside the Vert.x event loop</li>
 * </ul>
 *
 * <p><strong>Note:</strong> Blocking methods like {@code get()} and {@code join()} must never be called on
 * Vert.x event loop or worker threads, as this will block the reactive runtime.
 *
 * @param <T> the result type
 */
public class VertxFuture<T> extends CompletableFuture<T> implements java.util.concurrent.Future<T>, java.util.concurrent.CompletionStage<T> {
	/** The underlying Vert.x Future being wrapped. */
	Future<T> future;

	/**
	 * Wraps an existing Vert.x {@link Future} into a VertxFuture.
	 * Updates the internal state of this CompletableFuture whenever the Vert.x Future completes.
	 *
	 * @param future the Vert.x Future to wrap
	 */
	protected VertxFuture(Future<T> future) {
		this.future = future;

		future.onComplete(ar -> {
			// update the internal state of CompletableFuture
			if (ar.succeeded())
				super.complete(ar.result());
			else
				super.completeExceptionally(ar.cause());
		});
	}

	/**
	 * Creates a VertxFuture wrapper around an existing Vert.x Future.
	 *
	 * @param future the Vert.x Future
	 * @return a new VertxFuture wrapping the given future
	 * @param <T> the type of the VertxFuture result
	 */
	public static <T> VertxFuture<T> of(Future<T> future) {
		return new VertxFuture<>(future);
	}

	/**
	 * Creates a failed VertxFuture from a Throwable.
	 *
	 * @param cause the cause of the failure
	 * @return a new VertxFuture with a failure cause
	 * @param <U> the type of the VertxFuture Future result
	 */
	public static <U> VertxFuture<U> failedFuture(Throwable cause) {
		return new VertxFuture<>(Future.failedFuture(cause));
	}

	/**
	 * Creates a failed VertxFuture from an error message.
	 *
	 * @param cause the error message of the failure
	 * @return a new VertxFuture with a failure cause as a String.
	 * @param <U> the type of the VertxFuture Future result
	 */
	public static <U> VertxFuture<U> failedFuture(String cause) {
		return new VertxFuture<>(Future.failedFuture(cause));
	}

	/**
	 * Creates a successfully completed VertxFuture with a {@code null} result.
	 *
	 * @return a new VertxFuture with a {@code null} result.
	 * @param <U> the type of the VertxFuture Future result
	 */
	public static <U> VertxFuture<U> succeededFuture() {
		return new VertxFuture<>(Future.succeededFuture());
	}

	/**
	 * Creates a successfully completed VertxFuture with the given result.
	 *
	 * @param result the result of the future
	 * @return a new VertxFuture with the given result
	 * @param <U> the type of the VertxFuture Future result
	 */
	public static <U> VertxFuture<U> succeededFuture(U result) {
		return new VertxFuture<>(Future.succeededFuture(result));
	}

	@Override
	public Executor defaultExecutor() {
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
	public <U> VertxFuture<U> thenApply(Function<? super T, ? extends U> fn) {
		Future<U> mapper = future.map(fn::apply);
		return of(mapper);
	}

	@Override
	public <U> VertxFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn) {
		return thenApplyAsync(fn, defaultExecutor());
	}

	@Override
	public <U> VertxFuture<U> thenApplyAsync(Function<? super T, ? extends U> fn, Executor executor) {
		Future <U> composer = future.compose(t -> {
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
	public VertxFuture<Void> thenAccept(Consumer<? super T> action) {
		return thenApply(t -> {
			action.accept(t);
			return null;
		});
	}

	@Override
	public VertxFuture<Void> thenAcceptAsync(Consumer<? super T> action) {
		return thenAcceptAsync(action, defaultExecutor());
	}

	@Override
	public VertxFuture<Void> thenAcceptAsync(Consumer<? super T> action, Executor executor) {
		return thenApplyAsync(t -> {
			action.accept(t);
			return null;
		}, executor);
	}

	@Override
	public VertxFuture<Void> thenRun(Runnable action) {
		return thenApply(t -> {
			action.run();
			return null;
		});
	}

	@Override
	public VertxFuture<Void> thenRunAsync(Runnable action) {
		return thenRunAsync(action, defaultExecutor());
	}

	@Override
	public VertxFuture<Void> thenRunAsync(Runnable action, Executor executor) {
		return thenApplyAsync(t -> {
			action.run();
			return null;
		}, executor);
	}

	@Override
	public <U, V> VertxFuture<V> thenCombine(CompletionStage<? extends U> other,
			BiFunction<? super T, ? super U, ? extends V> fn) {
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
	public <U, V> VertxFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
			BiFunction<? super T, ? super U, ? extends V> fn) {
		return thenCombineAsync(other, fn, defaultExecutor());
	}

	@Override
	public <U, V> VertxFuture<V> thenCombineAsync(CompletionStage<? extends U> other,
			BiFunction<? super T, ? super U, ? extends V> fn, Executor executor) {
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
	public <U> VertxFuture<Void> thenAcceptBoth(CompletionStage<? extends U> other,
			BiConsumer<? super T, ? super U> action) {
		return thenCombine(other, (t, u) -> {
			action.accept(t, u);
			return null;
		});
	}

	@Override
	public <U> VertxFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
			BiConsumer<? super T, ? super U> action) {
		return thenAcceptBothAsync(other, action, defaultExecutor());
	}

	@Override
	public <U> VertxFuture<Void> thenAcceptBothAsync(CompletionStage<? extends U> other,
			BiConsumer<? super T, ? super U> action, Executor executor) {
		return thenCombineAsync(other, (t, u) -> {
			action.accept(t, u);
			return null;
		}, executor);
	}

	@Override
	public VertxFuture<Void> runAfterBoth(CompletionStage<?> other, Runnable action) {
		Future<?> otherFuture = Future.fromCompletionStage(other);
		// The behavior of Future.all is similar to CompletableFuture.thenCombine...
		Future<Void> mapper = Future.all(future, otherFuture).map(cf -> {
			action.run();
			return null;
		});

		return of(mapper);
	}

	@Override
	public VertxFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action) {
		return runAfterBothAsync(other, action, defaultExecutor());
	}

	@Override
	public VertxFuture<Void> runAfterBothAsync(CompletionStage<?> other, Runnable action, Executor executor) {
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

	@Override
	public <U> VertxFuture<U> applyToEither(CompletionStage<? extends T> other, Function<? super T, U> fn) {
		Future<? extends T> otherFuture = Future.fromCompletionStage(other);
		Future<U> mapper = Future.any(future, otherFuture).map(cf -> {
			for (int i = 0; i < cf.size(); i++) {
		        if (cf.succeeded(i)) {
		            T t = cf.resultAt(i);
					return fn.apply(t);
		        }
		    }

			// This should never happen
			throw new IllegalStateException("No successful result");
		});

		return of(mapper);
	}

	@Override
	public <U> VertxFuture<U> applyToEitherAsync(CompletionStage<? extends T> other, Function<? super T, U> fn) {
		return applyToEitherAsync(other, fn, defaultExecutor());
	}

	@Override
	public <U> VertxFuture<U> applyToEitherAsync(CompletionStage<? extends T> other,
			Function<? super T, U> fn, Executor executor) {
		Future<? extends T> otherFuture = Future.fromCompletionStage(other);
		Future<U> composer = Future.any(future, otherFuture).compose(cf -> {
			Promise<U> promise = Promise.promise();
			executor.execute(() -> {
				try {
					for (int i = 0; i < cf.size(); i++) {
				        if (cf.succeeded(i)) {
				            T t = cf.resultAt(i);
							U u = fn.apply(t);
							promise.complete(u);
							return;
				        }
				    }

					// This should never happen
					promise.fail(new IllegalStateException("No successful result"));
				} catch (Throwable e) {
					promise.fail(e);
				}
			});
			return promise.future();
		});

		return of(composer);
	}

	@Override
	public VertxFuture<Void> acceptEither(CompletionStage<? extends T> other, Consumer<? super T> action) {
		return applyToEither(other, (t) -> {
			action.accept(t);
			return null;
		});
	}

	@Override
	public VertxFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action) {
		return acceptEitherAsync(other, action, defaultExecutor());
	}

	@Override
	public VertxFuture<Void> acceptEitherAsync(CompletionStage<? extends T> other, Consumer<? super T> action,
			Executor executor) {
		return applyToEitherAsync(other, (t) -> {
			action.accept(t);
			return null;
		}, executor);
	}

	@Override
	public VertxFuture<Void> runAfterEither(CompletionStage<?> other, Runnable action) {
		Future<?> otherFuture = Future.fromCompletionStage(other);
		// The behavior of Future.any is similar to CompletableFuture.thenCombine...
		Future<Void> mapper = Future.any(future, otherFuture).map(cf -> {
			action.run();
			return null;
		});

		return of(mapper);
	}

	@Override
	public VertxFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action) {
		return runAfterEitherAsync(other, action, defaultExecutor());
	}

	@Override
	public VertxFuture<Void> runAfterEitherAsync(CompletionStage<?> other, Runnable action, Executor executor) {
		Future<?> otherFuture = Future.fromCompletionStage(other);
		Future<Void> composer = Future.any(future, otherFuture).compose(cf -> {
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
	public <U> VertxFuture<U> thenCompose(Function<? super T, ? extends CompletionStage<U>> fn) {
		Future<U> composer = future.compose(t -> Future.fromCompletionStage(fn.apply(t)));
		return of(composer);
	}

	@Override
	public <U> VertxFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn) {
		return thenComposeAsync(fn, defaultExecutor());
	}

	@Override
	public <U> VertxFuture<U> thenComposeAsync(Function<? super T, ? extends CompletionStage<U>> fn,
			Executor executor) {
		Future <U> composer = future.compose(t -> {
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
	public <U> VertxFuture<U> handle(BiFunction<? super T, Throwable, ? extends U> fn) {
		Future<U> handle = future.transform(ar -> {
			U u = fn.apply(ar.result(), ar.cause());
			return Future.succeededFuture(u);
		});

		return of(handle);
	}

	@Override
	public <U> VertxFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn) {
		return handleAsync(fn, defaultExecutor());
	}

	@Override
	public <U> VertxFuture<U> handleAsync(BiFunction<? super T, Throwable, ? extends U> fn, Executor executor) {
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
	public VertxFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
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
	public VertxFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action) {
		return whenCompleteAsync(action, defaultExecutor());
	}

	@Override
	public VertxFuture<T> whenCompleteAsync(BiConsumer<? super T, ? super Throwable> action, Executor executor) {
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
	public VertxFuture<T> exceptionally(Function<Throwable, ? extends T> fn) {
		Future<T> otherwise = future.otherwise(fn::apply);

		return of(otherwise);
	}

	@Override
	public VertxFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> fn) {
		return exceptionallyAsync(fn, defaultExecutor());
	}

	@Override
	public VertxFuture<T> exceptionallyAsync(Function<Throwable, ? extends T> fn, Executor executor) {
		Future <T> mapper = future.recover(e -> {
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
	public VertxFuture<T> exceptionallyCompose(Function<Throwable, ? extends CompletionStage<T>> fn) {
		Future <T> mapper = future.recover(e -> Future.fromCompletionStage(fn.apply(e)));

		return of(mapper);
	}

	@Override
	public VertxFuture<T> exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> fn) {
		return exceptionallyComposeAsync(fn, defaultExecutor());
	}

	@Override
	public VertxFuture<T> exceptionallyComposeAsync(Function<Throwable, ? extends CompletionStage<T>> fn,
			Executor executor) {
		Future <T> mapper = future.recover(e -> {
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
	public <U> VertxFuture<U> newIncompleteFuture() {
		Promise<U> promise = Promise.promise();
		return of(promise.future());
	}

	@Override
	public CompletableFuture<T> toCompletableFuture() {
		return this;
	}

	/**
	 * Converts this wrapper back into the underlying Vert.x Future.
	 *
	 * @return the underlying Vert.x Future.
	 */
	public Future<T> toVertxFuture() {
		return future;
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		// not support cancel
		return false;
	}

	@Override
	public boolean isCancelled() {
		return false;
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
	 *
	 * @throws IllegalStateException if called on a Vert.x thread
	 * @throws ExecutionException if the future completed exceptionally
	 * @throws InterruptedException if the thread was interrupted
	 */
	@Override
	public T get() throws InterruptedException, ExecutionException {
		if (Context.isOnVertxThread() || Context.isOnEventLoopThread())
			throw new IllegalStateException("Cannot not be called on vertx thread or event loop thread");

		final CountDownLatch latch = new CountDownLatch(1);
		future.onComplete(ar -> latch.countDown());

		latch.await();

		if (future.succeeded())
			return future.result();
		else if (future.failed())
			throw new ExecutionException(future.cause());
		else
			throw new InterruptedException("Context closed");
	}

	/**
	 * Blocks and waits for the result of this future.
	 * <p><strong>Warning:</strong> This method must not be called from a Vert.x event loop
	 * or worker thread, as it will block the reactive runtime.
	 *
	 * @param timeout the maximum time to wait
	 * @param unit the time unit of the timeout argument
	 * @return the result of the future or {@code null} if the specified waiting time elapses before
	 *
	 * @throws IllegalStateException if called on a Vert.x thread
	 * @throws ExecutionException if the future completed exceptionally
	 * @throws InterruptedException if the thread was interrupted
	 */
	@Override
	public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		if (Context.isOnVertxThread() || Context.isOnEventLoopThread())
			throw new IllegalStateException("Cannot not be called on vertx thread or event loop thread");

		final CountDownLatch latch = new CountDownLatch(1);
		future.onComplete(ar -> latch.countDown());

		if (!latch.await(timeout, unit))
			throw new TimeoutException();

		if (future.succeeded())
			return future.result();
		else if (future.failed())
			throw new ExecutionException(future.cause());
		else
			throw new InterruptedException("Context closed");
	}

	@Override
	public T join() {
		 try {
			 return get();
		 } catch (InterruptedException | ExecutionException e) {
			 throw new CompletionException(e);
		 }
	 }

	@Override
	public T getNow(T valueIfAbsent) {
		if (future.isComplete()) {
			if (future.succeeded()) {
				return future.result();
			} else {
				Throwable cause = future.cause();
				if (cause instanceof CompletionException ce) {
					throw ce;
				} if (cause instanceof CancellationException ce) {
					throw ce;
				} else {
					throw new CompletionException(cause);
				}
			}
		} else {
			return valueIfAbsent;
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public boolean complete(T value) {
		if (future instanceof Promise<?>) {
			Promise<T> promise = (Promise<T>) future;
			return promise.tryComplete(value);
		} else
			throw new IllegalStateException();
	}

	@Override
	public VertxFuture<T> completeAsync(Supplier<? extends T> supplier, Executor executor) {
		if (supplier == null || executor == null)
			throw new NullPointerException();
		executor.execute(() -> complete(supplier.get()));
		return this;
	}

	@Override
	public VertxFuture<T> completeAsync(Supplier<? extends T> supplier) {
		return completeAsync(supplier, defaultExecutor());
	}

	@Override
	public boolean completeExceptionally(Throwable ex) {
		if (ex == null)
			throw new NullPointerException();

		if (future instanceof Promise<?> promise)
			return promise.tryFail(ex);
		else
			throw new IllegalStateException();
	}

	@Override
	public VertxFuture<T> orTimeout(long timeout, TimeUnit unit) {
		if (unit == null)
			throw new NullPointerException();
		Future<T> f = future.timeout(timeout, unit);
		return f == future ? this : of(f);
	}

	@Override
	public VertxFuture<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
		if (unit == null)
			throw new NullPointerException();

		Future<T> f = future.timeout(timeout, unit).recover(e -> {
			if (e instanceof TimeoutException)
				return Future.succeededFuture(value);
			else
				return Future.failedFuture(e);
		});

		return of(f);
	}

	@Override
	public void obtrudeValue(T value) {
		throw new UnsupportedOperationException();
	}

	@Override
	public void obtrudeException(Throwable ex) {
		throw new UnsupportedOperationException();
	}

	@Override
	public VertxFuture<T> copy() {
		Promise<T> promise = Promise.promise();
		future.onComplete(promise);
		return of(promise.future());
	}

	@Override
	public CompletionStage<T> minimalCompletionStage() {
		return new MinimalStage<>(future);
	}

	/**
	 * A reduced view of VertxFuture that exposes only {@link CompletionStage} operations,
	 * disabling mutation methods such as {@code complete()}, {@code cancel()}, etc.
	 * <p>
	 * This is used by {@link #minimalCompletionStage()} to comply with the
	 * {@link CompletableFuture#minimalCompletionStage()} contract.
	 */
	static final class MinimalStage<T> extends VertxFuture<T> {
		MinimalStage(Future<T> future) {
			super(future);
		}

		@Override
		public T get() {
			throw new UnsupportedOperationException();
		}

		@Override
		public T get(long timeout, TimeUnit unit) {
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

		@Override
		public void obtrudeValue(T value) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void obtrudeException(Throwable ex) {
			throw new UnsupportedOperationException();
		}

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
		public MinimalStage<T> completeAsync(Supplier<? extends T> supplier, Executor executor) {
			throw new UnsupportedOperationException();
		}

		@Override
		public MinimalStage<T> completeAsync(Supplier<? extends T> supplier) {
			throw new UnsupportedOperationException();
		}

		@Override
		public MinimalStage<T> orTimeout(long timeout, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public MinimalStage<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
			throw new UnsupportedOperationException();
		}

		@Override
		public CompletableFuture<T> toCompletableFuture() {
			Promise<T> promise = Promise.promise();
			future.onComplete(promise);
			return of(promise.future());
		}
	}
}