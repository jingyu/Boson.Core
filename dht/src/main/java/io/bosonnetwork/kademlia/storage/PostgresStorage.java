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

package io.bosonnetwork.kademlia.storage;

import java.net.URL;
import java.nio.file.Path;

import io.vertx.core.Vertx;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostgresStorage extends DatabaseStorage implements DataStorage {
	protected static final String STORAGE_URI_PREFIX = "postgresql://";

	private final String connectionUri;
	private Pool client;
	private SqlDialect sqlDialect;

	private static final Logger log = LoggerFactory.getLogger(PostgresStorage.class);

	protected PostgresStorage(String connectionUri) {
		this.connectionUri = connectionUri;
	}

	// postgresql://[user[:password]@][host][:port][,...][/dbname][?param1=value1&...]
	@Override
	protected void init(Vertx vertx) {
		PgConnectOptions connectOptions = PgConnectOptions.fromUri(connectionUri);
		PoolOptions poolOptions = new PoolOptions().setMaxSize(8);
		client = PgBuilder.pool()
				.with(poolOptions)
				.connectingTo(connectOptions)
				.using(vertx)
				.build();
		sqlDialect = new SqlDialect() {};
	}

	@Override
	protected Path getSchemaPath() {
		URL schemaPath = getClass().getClassLoader().getResource("db/postgres");
		if (schemaPath == null || schemaPath.getPath() == null)
			throw new IllegalStateException("Migration path not exists");

		return Path.of(schemaPath.getPath());
	}

	@Override
	public SqlClient getClient() {
		return client;
	}

	@Override
	protected SqlDialect getDialect() {
		return sqlDialect;
	}

	@Override
	protected Logger getLogger() {
		return log;
	}
}