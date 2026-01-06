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
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteDataSource;

public class SQLiteStorage extends DatabaseStorage implements DataStorage {
	protected static final String STORAGE_URI_PREFIX = "jdbc:sqlite:";

	private final String connectionUri;
	private final int poolSize;
	private Pool client;
	private SqlDialect sqlDialect;

	private static final Logger log = LoggerFactory.getLogger(SQLiteStorage.class);

	protected SQLiteStorage(String connectionUri, int poolSize) {
		this.connectionUri = connectionUri;
		this.poolSize = poolSize > 0 ? poolSize : 1;
	}

	protected SQLiteStorage(String connectionUri) {
		this(connectionUri, 0);
	}

	@Override
	protected void init(Vertx vertx) {
		// Vert.x 5.x style
		SQLiteDataSource dataSource = new SQLiteDataSource();
		dataSource.setUrl(connectionUri);
		dataSource.setJournalMode("WAL");
		dataSource.setEnforceForeignKeys(true);
		dataSource.setBusyTimeout(5000);
		dataSource.setLockingMode("NORMAL");
		dataSource.setSharedCache(false);
		dataSource.setFullSync(true);

		// Single connection recommended for SQLite
		PoolOptions poolOptions = new PoolOptions().setMaxSize(poolSize);
		client = JDBCPool.pool(vertx, dataSource, poolOptions);
		sqlDialect = new SqlDialect() {};

		/*/
		// Vert.x 4.x style
		SQLiteDataSource ds = new SQLiteDataSource();
		ds.setUrl(connectionUri);
		client = JDBCPool.pool(vertx, ds);
		*/
	}

	@Override
	protected Path getMigrationPath() {
		URL migrationResource = getClass().getResource("/db/kadnode/sqlite");
		if (migrationResource == null || migrationResource.getPath() == null)
			throw new IllegalStateException("Migration path not exists");

		return Path.of(migrationResource.getPath());
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