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

import java.util.List;
import java.util.function.Function;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PreparedQuery;
import io.vertx.sqlclient.Query;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.TransactionRollbackException;

/**
 * Abstraction over a Vert.x {@link SqlClient} providing convenience helpers for
 * querying, prepared querying, connection/transaction handling, and small mapping utilities.
 * <p>
 * Implementations must supply the underlying {@link #getClient()} which can be either a {@link SqlConnection}
 * or a {@link Pool}. All helpers delegate to this client in a safe, Vert.x-friendly way.
 * </p>
 */
public interface VertxDatabase {
	/**
	 * Returns the underlying Vert.x SQL client, either a {@link SqlConnection} or a {@link Pool}.
	 *
	 * @return the backing SQL client
	 */
	SqlClient getClient();

	/**
	 * Attempts to retrieve the database product name from the current connection.
	 * If the driver does not support reading metadata, returns {@code "Unknown"}.
	 *
	 * @return a future completing with the database product name
	 */
	default Future<String> getDatabaseProductName() {
		return withConnection(c -> {
			String name;
			try {
				name = c.databaseMetadata().productName();
			} catch (UnsupportedOperationException e) {
				name = "Unknown";
			}

			return Future.succeededFuture(name);
		});
	}

	/**
	 * Creates a simple text query using the underlying client.
	 *
	 * @param sql SQL text to execute
	 * @return a Vert.x {@link Query} for the provided SQL
	 */
	default Query<RowSet<Row>> query(String sql) {
		return getClient().query(sql);
	}

	/**
	 * Creates a prepared query using the underlying client.
	 *
	 * @param sql SQL text with placeholders
	 * @return a Vert.x {@link PreparedQuery} for the provided SQL
	 */
	default PreparedQuery<RowSet<Row>> preparedQuery(String sql) {
		return getClient().preparedQuery(sql);
	}

	/**
	 * Executes the provided function within a database transaction.
	 * <p>
	 * If the underlying client is a {@link Pool}, a connection is obtained and a transaction is started.
	 * If it is a {@link SqlConnection}, the transaction is started on that connection directly.
	 * The transaction is committed if the returned future succeeds, otherwise it is rolled back.
	 * </p>
	 *
	 * @param function a function receiving a {@link SqlConnection} and returning a future result
	 * @param <T>      result type
	 * @return a future completing with the function result after commit, or failing after rollback
	 */
	default <T> Future<T> withTransaction(Function<SqlConnection, Future<T>> function) {
		if (getClient() instanceof SqlConnection c) {
			return withTransaction(c, function);
		} else if (getClient() instanceof Pool p) {
			return p.withTransaction(function);
		} else {
			return Future.failedFuture(new IllegalStateException("Client must be an instance of SqlConnection or Pool"));
		}
	}

	private <T> Future<T> withTransaction(SqlConnection connection, Function<SqlConnection, Future<T>> function) {
		return connection.begin().compose(tx ->
				function.apply(connection).compose(
						res -> tx.commit().compose(v -> Future.succeededFuture(res)),
						err -> {
							if (err instanceof TransactionRollbackException) {
								return Future.failedFuture(err);
							} else {
								return tx.rollback().compose(
										v -> Future.failedFuture(err),
										failure -> Future.failedFuture(err));
							}
						}));
	}

	/**
	 * Executes the provided function with a {@link SqlConnection}.
	 * <p>
	 * If the underlying client is a {@link Pool}, a connection is acquired and automatically closed
	 * when the returned future completes.
	 * </p>
	 *
	 * @param function work to perform on a connection
	 * @param <T>      result type
	 * @return a future completing with the function result
	 */
	default <T> Future<T> withConnection(Function<SqlConnection, Future<T>> function) {
		if (getClient() instanceof SqlConnection c) {
			return function.apply(c);
		} else if (getClient() instanceof Pool p) {
			return p.getConnection().compose(c ->
					function.apply(c).onComplete(ar -> c.close())
			);
		} else {
			return Future.failedFuture(new IllegalStateException("Client must be an instance of SqlConnection or Pool"));
		}
	}

	/**
	 * Extracts the first boolean value from the first row or returns a default when empty.
	 *
	 * @param rowSet       result set
	 * @param defaultValue value to return when the set is empty
	 * @return the found boolean or the default
	 */
	static boolean findBoolean(RowSet<Row> rowSet, boolean defaultValue) {
		return rowSet.size() != 0 ? rowSet.iterator().next().getBoolean(0) : defaultValue;
	}

	/**
	 * Extracts the first boolean value from the first row, or {@code false} when empty.
	 *
	 * @param rowSet result set
	 * @return the found boolean or {@code false}
	 */
	static boolean findBoolean(RowSet<Row> rowSet) {
		return findBoolean(rowSet, false);
	}

	/**
	 * Extracts the first integer value from the first row or returns a default when empty.
	 *
	 * @param rowSet       result set
	 * @param defaultValue value to return when the set is empty
	 * @return the found integer or the default
	 */
	static int findInteger(RowSet<Row> rowSet, int defaultValue) {
		return rowSet.size() != 0 ? rowSet.iterator().next().getInteger(0) : defaultValue;
	}

	/**
	 * Extracts the first integer value from the first row, or {@code 0} when empty.
	 *
	 * @param rowSet result set
	 * @return the found integer or {@code 0}
	 */
	static int findInteger(RowSet<Row> rowSet) {
		return findInteger(rowSet, 0);
	}

	/**
	 * Extracts the first long value from the first row or returns a default when empty.
	 *
	 * @param rowSet       result set
	 * @param defaultValue value to return when the set is empty
	 * @return the found long or the default
	 */
	static long findLong(RowSet<Row> rowSet, long defaultValue) {
		return rowSet.size() != 0 ? rowSet.iterator().next().getLong(0) : defaultValue;
	}

	/**
	 * Extracts the first long value from the first row, or {@code 0L} when empty.
	 *
	 * @param rowSet result set
	 * @return the found long or {@code 0L}
	 */
	static long findLong(RowSet<Row> rowSet) {
		return findLong(rowSet, 0);
	}

	/**
	 * Maps the first row to a value using the provided mapper, or returns the given default when empty.
	 *
	 * @param rowSet       result set
	 * @param mapper       row mapper
	 * @param defaultValue value to return when the set is empty
	 * @param <T>          mapped type
	 * @return mapped value or the default
	 */
	static <T> T findUniqueOrDefault(RowSet<Row> rowSet, Function<Row, T> mapper, T defaultValue) {
		return rowSet.size() != 0 ? mapper.apply(rowSet.iterator().next()) : defaultValue;
	}

	/**
	 * Maps the first row to a value using the provided mapper, or returns {@code null} when empty.
	 *
	 * @param rowSet result set
	 * @param mapper row mapper
	 * @param <T>    mapped type
	 * @return mapped value or {@code null}
	 */
	static <T> T findUnique(RowSet<Row> rowSet, Function<Row, T> mapper) {
		return findUniqueOrDefault(rowSet, mapper, null);
	}

	/**
	 * Maps all rows in the given {@link RowSet} to a list using the provided mapper.
	 *
	 * @param rowSet result set
	 * @param mapper row mapper
	 * @param <T>    element type
	 * @return list of mapped values (possibly empty)
	 */
	static <T> List<T> findMany(RowSet<Row> rowSet, Function<Row, T> mapper) {
		return rowSet.stream().map(mapper).toList();
	}
}