package io.bosonnetwork.utils.jdbi.async;

import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.jdbi.v3.core.transaction.TransactionIsolationLevel;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

class JdbiExecutorImpl implements JdbiExecutor {
	private final Jdbi jdbi;
	private final Vertx vertx;

	JdbiExecutorImpl(Jdbi jdbi, Vertx vertx) {
		this.jdbi = jdbi;
		this.vertx = vertx;
	}

	/**
	 * Single method through which all other with* methods converge.
	 *
	 * @param callback the callback that takes a Jdbi instance and returns a value
	 * @param <T>      type returned by the callback
	 * @return a completion stage that will complete when the handler returns a
	 *         value or throws an exception
	 */
	protected <T> Future<T> withExecute(final CheckedFunction<Jdbi, T> callback) {
		return vertx.executeBlocking(() -> callback.apply(jdbi));
	}

	/**
	 * Single method through which all other use* methods converge.
	 *
	 * @param callback the callback that takes a Jdbi instance
	 * @return a completion stage that will complete when the handler returns or
	 *         throws an exception
	 */
	protected Future<Void> useExecute(CheckedConsumer<Jdbi> callback) {
		return vertx.executeBlocking(() -> {
				callback.accept(jdbi);
				return null;
		});
	}

	@Override
	public <R, X extends Exception> Future<R> withHandle(HandleCallback<R, X> callback) {
		return withExecute(jdbi -> jdbi.withHandle(callback));
	}

	@Override
	public <R, X extends Exception> Future<R> inTransaction(HandleCallback<R, X> callback) {
		return withExecute(jdbi -> jdbi.inTransaction(callback));
	}

	@Override
	public <R, X extends Exception> Future<R> inTransaction(TransactionIsolationLevel level, HandleCallback<R, X> callback) {
		return withExecute(jdbi -> jdbi.inTransaction(level, callback));
	}

	@Override
	public <X extends Exception> Future<Void> useHandle(HandleConsumer<X> consumer) {
		return useExecute(jdbi -> jdbi.useHandle(consumer));
	}

	@Override
	public <X extends Exception> Future<Void> useTransaction(HandleConsumer<X> callback) {
		return useExecute(jdbi -> jdbi.useTransaction(callback));
	}

	@Override
	public <X extends Exception> Future<Void> useTransaction(TransactionIsolationLevel level, HandleConsumer<X> callback) {
		return useExecute(jdbi -> jdbi.useTransaction(level, callback));
	}

	@Override
	public <R, E, X extends Exception> Future<R> withExtension(Class<E> extensionType, ExtensionCallback<R, E, X> callback) {
		return withExecute(jdbi -> jdbi.withExtension(extensionType, callback));
	}

	@Override
	public <E, X extends Exception> Future<Void> useExtension(Class<E> extensionType, ExtensionConsumer<E, X> callback) {
		return useExecute(jdbi -> jdbi.useExtension(extensionType, callback));
	}

	@FunctionalInterface
	protected interface CheckedFunction<X, T> {
	    T apply(X x) throws Exception;
	}

	@FunctionalInterface
	protected interface CheckedConsumer<T> {
	    void accept(T t) throws Exception;
	}
}