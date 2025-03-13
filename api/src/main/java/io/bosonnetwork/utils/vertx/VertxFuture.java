package io.bosonnetwork.utils.vertx;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.impl.future.FutureInternal;

public class VertxFuture<T> extends CompletableFuture<T> implements CompletionStage<T> {
	private ContextInternal context;
	private final Future<T> future;

	/**
	 *
	 * @param future
	 */
	protected VertxFuture(Future<T> future) {
		this.context = ((FutureInternal<T>)future).context();
		this.future = future;

		future.onComplete(ar -> {
			if (ar.succeeded())
				complete(ar.result());
			else
				completeExceptionally(ar.cause());
		});
	}

	protected VertxFuture(ContextInternal context) {
		this.context = context;
		this.future = null;
	}


	public static <T> VertxFuture<T> of(Future<T> future) {
		return new VertxFuture<>(future);
	}

	public Future<T> future() {
		return future;
	}

	@Override
	public Executor defaultExecutor() {
		// Prioritize the current Vert.x WorkerPool executor first.
		if (Vertx.currentContext() != null)
			return ((ContextInternal)Vertx.currentContext()).workerPool().executor();

		// Then the Vert.x WorkerPool executor from the future's context.
		if (context != null)
			return context.workerPool().executor();

		// Fall back to the ForkJoinPool executor.
		return super.defaultExecutor();
	}

	@Override
	public int getNumberOfDependents() {
		if (future instanceof CompositeFuture cf)
			return cf.size();
		else
			return 0;
	}

	@Override
	public <U> CompletableFuture<U> newIncompleteFuture() {
		return new VertxFuture<>(context);
	}

	public static <T> VertxFuture<T> failedFuture(Throwable cause) {
		return new VertxFuture<>(Future.failedFuture(cause));
	}

	public static <T> VertxFuture<T> failedFuture(String cause) {
		return new VertxFuture<>(Future.failedFuture(cause));
	}

	public static <T> VertxFuture<T> succeededFuture() {
		return new VertxFuture<>(Future.succeededFuture());
	}

	public static <T> VertxFuture<T> succeededFuture(T result) {
		return new VertxFuture<>(Future.succeededFuture(result));
	}
}
