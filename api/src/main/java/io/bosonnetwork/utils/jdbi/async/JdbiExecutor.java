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

package io.bosonnetwork.utils.jdbi.async;

import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.extension.ExtensionCallback;
import org.jdbi.v3.core.extension.ExtensionConsumer;
import org.jdbi.v3.core.extension.ExtensionFactory;
import org.jdbi.v3.core.transaction.TransactionIsolationLevel;

import io.vertx.core.Future;
import io.vertx.core.Vertx;

/**
 * Interface that provides methods for managing Jdbi handles and executing
 * database operations asynchronously. All operations are executed in a thread
 * supplied by the underlying executor.
 *
 * The interface supports methods for executing with and without transactions,
 * as well as adopting specific transaction isolation levels. It also provides
 * mechanisms for working with Jdbi extensions to encapsulate behavior and reuse
 * logic.
 */
public interface JdbiExecutor {
	/**
	 * Create a {@link JdbiExecutor}.
	 *
	 * <p>
	 * The executor to pass in needs to be sized to the use case. A rule of thumb is
	 * to have the max number of workers be equal to the max number of connections
	 * in the connection pool. The worker queue in the executor should probably be
	 * bounded, unless the caller(s) already has a bound for the number of
	 * outstanding requests. Making the queue bounded will mean you are blocking the
	 * calling thread when the queue fills up. Whether that is acceptable
	 * depends on if and how your service limits incoming requests
	 * </p>
	 *
	 * @param jdbi     the initialized Jdbi class
	 * @param vertx    the Vertx instance to use
	 * @return a new JdbiExecutor instance that backed by the given Jdbi and Vertx
	 */
	static JdbiExecutor create(Jdbi jdbi, Vertx vertx) {
		return new JdbiExecutorImpl(jdbi, vertx);
	}

	/**
	 * A convenience function which manages the lifecycle of a handle and yields it
	 * to a callback for use by clients.
	 *
	 * <p>
	 * The callback will be executed in a thread supplied by the executor
	 * </p>
	 *
	 * @param callback A callback which will receive an open Handle
	 * @param <R>      type returned by the callback
	 * @param <X>      exception type thrown by the callback, if any.
	 * @return a completion stage which completes when the callback returns a value
	 *         or throws an exception
	 */
	<R, X extends Exception> Future<R> withHandle(HandleCallback<R, X> callback);

	/**
	 * A convenience function which manages the lifecycle of a handle and yields it
	 * to a callback for use by clients. The handle will be in a transaction when
	 * the callback is invoked, and that transaction will be committed if the
	 * callback finishes normally, or rolled back if the callback raises an
	 * exception.
	 *
	 * <p>
	 * The callback will be executed in a thread supplied by the executor
	 * </p>
	 *
	 * @param callback A callback which will receive an open Handle, in a
	 *                 transaction
	 * @param <R>      type returned by the callback
	 * @param <X>      exception type thrown by the callback, if any.
	 * @return a completion stage which completes when the callback returns a value
	 *         or throws an exception
	 */
	<R, X extends Exception> Future<R> inTransaction(HandleCallback<R, X> callback);

	/**
	 * A convenience function which manages the lifecycle of a handle and yields it
	 * to a callback for use by clients. The handle will be in a transaction when
	 * the callback is invoked, and that transaction will be committed if the
	 * callback finishes normally, or rolled back if the callback raises an
	 * exception.
	 *
	 * <p>
	 * This form accepts a transaction isolation level which will be applied to the
	 * connection for the scope of this transaction, after which the original
	 * isolation level will be restored.
	 * </p>
	 *
	 * <p>
	 * The callback will be executed in a thread supplied by the executor
	 * </p>
	 *
	 * @param level    the transaction isolation level which will be applied to the
	 *                 connection for the scope of this transaction, after which the
	 *                 original isolation level will be restored.
	 * @param callback A callback which will receive an open Handle, in a
	 *                 transaction
	 * @param <R>      type returned by the callback
	 * @param <X>      exception type thrown by the callback, if any.
	 * @return a completion stage which completes when the callback returns a value
	 *         or throws an exception
	 */
	<R, X extends Exception> Future<R> inTransaction(TransactionIsolationLevel level, HandleCallback<R, X> callback);

	/**
	 * A convenience function which manages the lifecycle of a handle and yields it
	 * to a callback for use by clients.
	 *
	 * <p>
	 * The callback will be executed in a thread supplied by the executor
	 * </p>
	 *
	 * @param consumer A callback which will receive an open Handle
	 * @param <X>      exception type thrown by the callback, if any.
	 * @return a completion stage which completes when the callback returns or
	 *         throws an exception
	 */
	<X extends Exception> Future<Void> useHandle(HandleConsumer<X> consumer);

	/**
	 * A convenience function which manages the lifecycle of a handle and yields it
	 * to a callback for use by clients. The handle will be in a transaction when
	 * the callback is invoked, and that transaction will be committed if the
	 * callback finishes normally, or rolled back if the callback raises an
	 * exception.
	 *
	 * <p>
	 * The callback will be executed in a thread supplied by the executor
	 * </p>
	 *
	 * @param callback A callback which will receive an open Handle, in a
	 *                 transaction
	 * @param <X>      exception type thrown by the callback, if any.
	 * @return a completion stage which completes when the callback returns or
	 *         throws an exception
	 */
	<X extends Exception> Future<Void> useTransaction(HandleConsumer<X> callback);

	/**
	 * A convenience function which manages the lifecycle of a handle and yields it
	 * to a callback for use by clients. The handle will be in a transaction when
	 * the callback is invoked, and that transaction will be committed if the
	 * callback finishes normally, or rolled back if the callback raises an
	 * exception.
	 *
	 * <p>
	 * This form accepts a transaction isolation level which will be applied to the
	 * connection for the scope of this transaction, after which the original
	 * isolation level will be restored.
	 * </p>
	 *
	 * <p>
	 * The callback will be executed in a thread supplied by the executor
	 * </p>
	 *
	 * @param level    the transaction isolation level which will be applied to the
	 *                 connection for the scope of this transaction, after which the
	 *                 original isolation level will be restored.
	 * @param callback A callback which will receive an open Handle, in a
	 *                 transaction
	 * @param <X>      exception type thrown by the callback, if any.
	 * @return a completion stage which completes when the callback returns or
	 *         throws an exception
	 */
	<X extends Exception> Future<Void> useTransaction(TransactionIsolationLevel level, HandleConsumer<X> callback);

	/**
	 * A convenience method which opens an extension of the given type, yields it to
	 * a callback, and returns the result of the callback. A handle is opened if
	 * needed by the extension, and closed before returning to the caller.
	 *
	 * <p>
	 * The callback will be executed in a thread supplied by the executor
	 * </p>
	 *
	 * @param extensionType the type of extension.
	 * @param callback      a callback which will receive the extension.
	 * @param <R>           the return type
	 * @param <E>           the extension type
	 * @param <X>           the exception type optionally thrown by the callback
	 * @return a completion stage which completes when the callback returns a value
	 *         or throws an exception, or will complete with
	 *         NoSuchExtensionException if no {@link ExtensionFactory} is registered
	 *         which supports the given extension type.
	 */
	<R, E, X extends Exception> Future<R> withExtension(Class<E> extensionType, ExtensionCallback<R, E, X> callback);

	/**
	 * A convenience method which opens an extension of the given type, and yields
	 * it to a callback. A handle is opened if needed by the extension, and closed
	 * before returning to the caller.
	 *
	 * <p>
	 * The callback will be executed in a thread supplied by the executor
	 * </p>
	 *
	 * @param extensionType the type of extension
	 * @param callback      a callback which will receive the extension
	 * @param <E>           the extension type
	 * @param <X>           the exception type optionally thrown by the callback
	 * @return a completion stage which completes when the callback returns or
	 *         throws an exception, or will complete with NoSuchExtensionException
	 *         if no {@link ExtensionFactory} is registered which supports the given
	 *         extension type.
	 */
	<E, X extends Exception> Future<Void> useExtension(Class<E> extensionType, ExtensionConsumer<E, X> callback);
}