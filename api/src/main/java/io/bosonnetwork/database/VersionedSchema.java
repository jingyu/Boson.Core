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

package io.bosonnetwork.database;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.file.FileProps;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.OpenOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.utils.Hex;

/**
 * A lightweight, file-based schema migration helper for Vert.x SQL clients.
 *
 * <p>This component applies versioned SQL migrations located in a directory,
 * records applied versions in a {@code schema_versions} table, and ensures
 * migration integrity via SHA-256 checksum validation.</p>
 *
 * <p>Features:
 * <ul>
 *   <li>Versioned migrations using {@code &lt;version&gt;_&lt;description&gt;.sql} naming</li>
 *   <li>Transactional execution of migrations</li>
 *   <li>Checksum verification to detect script tampering</li>
 *   <li>Optional PostgreSQL schema isolation via {@code SET search_path}</li>
 *   <li>Compatible with PostgreSQL and SQLite (Vert.x SQL clients)</li>
 * </ul>
 *
 * <p>This class is designed for application-managed schema migrations and
 * intentionally avoids external JDBC-based migration frameworks.</p>
 *
 * <p>Future extensions:
 * <ul>
 *   <li>Baseline support for existing schemas</li>
 *   <li>Repair support for checksum mismatch recovery</li>
 * </ul>
 * </p>
 */
public class VersionedSchema implements VertxDatabase {
	private static final SchemaVersion EMPTY_VERSION = new SchemaVersion(0, "",  null,"", 0, 0, true);
	private final Vertx vertx;
	private final SqlClient client;
	private final String schema;
	private final Path schemaPath;
	private String databaseProductName;
	private SchemaVersion currentVersion;

	private static final Logger log = LoggerFactory.getLogger(VersionedSchema.class);

	/**
	 * Immutable record representing an applied schema migration.
	 *
	 * @param version       numeric migration version
	 * @param description   human-readable description (from file name or SQL comment)
	 * @param hash          SHA-256 checksum of the migration script
	 * @param appliedBy     identifier of the user or process applying the migration
	 * @param appliedAt     timestamp in milliseconds when migration started
	 * @param consumedTime  duration in milliseconds spent applying the migration
	 * @param success       whether the migration completed successfully
	 */
	public record SchemaVersion(int version, String description, String hash, String appliedBy, long appliedAt,
								   long consumedTime, boolean success) {}

	private static class Migration {
		private final int version;
		private String description;
		private final String hash;
		private final Path path;

		/**
		 * Internal holder for a parsed migration file.
		 *
		 * @param version     numeric version
		 * @param description textual description
		 * @param hash        SHA-256 hash of the migration script
		 * @param path        file path to the SQL script
		 */
		public Migration(int version, String description, String hash, Path path) {
			this.version = version;
			this.description = description;
			this.hash = hash;
			this.path = path;
		}

		public void setDescription(String description) {
			this.description = description;
		}

		public String fileName() {
			return path.getFileName().toString();
		}

		public File file() {
			return path.toFile();
		}

		/*/
		public Path path() {
			return path;
		}
		*/
	}

	private VersionedSchema(Vertx vertx, SqlClient client, String schema, Path schemaPath) {
		this.vertx = vertx;
		this.client = client;
		this.schema = schema;
		this.schemaPath = schemaPath;
		this.currentVersion = EMPTY_VERSION;
	}

	/**
	 * Initializes a {@link VersionedSchema} using the database default schema.
	 *
	 * <p>Migrations will be applied using the database's default schema
	 * (for example {@code public} in PostgreSQL).</p>
	 *
	 * @param vertx      Vert.x instance
	 * @param client     Vert.x SQL client
	 * @param schemaPath directory containing migration SQL files
	 * @return a new {@link VersionedSchema} instance
	 */
	public static VersionedSchema init(Vertx vertx, SqlClient client, Path schemaPath) {
		return new VersionedSchema(vertx, client, null, schemaPath);
	}

	/**
	 * Initializes a new instance of {@link VersionedSchema}.
	 *
	 * @param vertx      the Vert.x instance used for database operations and event loops
	 * @param client     the SQL client used for executing migrations
	 * @param schema     the schema name where migrations will be applied
	 * @param schemaPath the path to the directory containing migration SQL files
	 * @return a new {@link VersionedSchema} instance configured for the provided parameters
	 */
	public static VersionedSchema init(Vertx vertx, SqlClient client, String schema, Path schemaPath) {
		if (schema != null && !schema.matches("[a-z][a-z0-9_]{0,31}"))
			throw new IllegalArgumentException("Invalid schema name");

		return new VersionedSchema(vertx, client, schema, schemaPath);
	}

	/**
	 * The underlying SQL client used for migrations.
	 *
	 * @return the client
	 */
	@Override
	public SqlClient getClient() {
		return client;
	}

	/**
	 * Returns the last successfully applied schema version, if any.
	 *
	 * @return the current version or {@code null} if none recorded
	 */
	public SchemaVersion getCurrentVersion() {
		return currentVersion;
	}

	/**
	 * Discovers and applies pending schema migrations.
	 *
	 * <p>The migration process consists of:
	 * <ol>
	 *   <li>Detecting the database product</li>
	 *   <li>Creating the target schema (PostgreSQL only, if configured)</li>
	 *   <li>Ensuring the {@code schema_versions} table exists</li>
	 *   <li>Loading applied migration history</li>
	 *   <li>Validating migration order and checksums</li>
	 *   <li>Applying new migrations transactionally</li>
	 * </ol>
	 *
	 * <p>If an already-applied migration differs in version or checksum,
	 * the migration process fails immediately.</p>
	 *
	 * @return a future that completes when all pending migrations have been applied,
	 *         or fails if validation or execution fails
	 */
	public Future<Void> migrate() {
		return getDatabaseProductName().compose(name -> {
			databaseProductName = name;
			log.debug("Migration check: target database product {}", name);

			if (!databaseProductName.toLowerCase().contains("postgres") && schema != null)
				return Future.failedFuture(new IllegalStateException("Schema migration with custom schema is not supported for " + databaseProductName));

			return Future.succeededFuture();
		}).compose(na -> {
			Future<List<SchemaVersion>> versionsFuture = withTransaction(c ->
					createSchema(c)
							.compose(v -> setSchema(c))
							.compose(v -> createSchemaVersionTable(c))
							.compose(v -> getSchemaVersions(c))
							.andThen(ar -> {
								if (ar.succeeded()) {
									List<SchemaVersion> versions = ar.result();
									if (!versions.isEmpty())
										this.currentVersion = versions.get(versions.size() - 1);
								} else {
									log.warn("Migration check: error init or reading schema versions - {}", ar.cause().getMessage());
								}
							})
			);

			Future<List<Migration>> migrationsFuture = getMigrations().andThen(ar -> {
				if (ar.failed())
					log.warn("Migration check: error reading migrations - {}", ar.cause().getMessage());
			});

			return Future.all(versionsFuture, migrationsFuture);
		}).compose(cf -> {
			List<SchemaVersion> versions = cf.resultAt(0);
			List<Migration> migrations = cf.resultAt(1);

			if (migrations.size() < versions.size()) {
				log.error("Migration check: database schema version mismatch: {} migrations found, {} recorded",
						migrations.size(), versions.size());
				return Future.failedFuture(new IllegalStateException("Database schema version mismatch"));
			}

			for (int i = 0; i < versions.size(); i++) {
				SchemaVersion v = versions.get(i);
				Migration m = migrations.get(i);
				if (v.version != m.version) {
					log.error("Migration check: database schema version mismatch: {} recorded, {} found - {}",
							v.version, m.version, m.fileName());
					return Future.failedFuture(new IllegalStateException("Database schema version mismatch"));
				}
				if (!v.hash.equals(m.hash)) {
					log.error("Migration check: database schema version {} hash mismatch: {} recorded, {} found - {}",
							v.version, v.hash, m.hash, m.fileName());
					return Future.failedFuture(new IllegalStateException("Database schema version mismatch"));
				}
			}

			if (versions.size() == migrations.size()) {
				log.info("Migration check: no new migrations found");
				return Future.succeededFuture();
			}

			Future<Void> chain = Future.succeededFuture();
			for (int i = versions.size(); i < migrations.size(); i++) {
				Migration migration = migrations.get(i);
				chain = chain.compose(na ->
						applyMigration(migration).map(v -> {
							this.currentVersion = v;
							return null;
						})
				);
			}

			return chain;
		});
	}

	private <T> Future<T> withSchemaTransaction(Function<SqlConnection, Future<T>> function) {
		return withTransaction(c -> setSchema(c).compose(v -> function.apply(c)));
	}

	/**
	 * Reads the current applied schema versions from the database.
	 *
	 * @return a future with the list of applied {@link SchemaVersion}, empty list if none
	 */
	private Future<List<SchemaVersion>> getSchemaVersions(SqlClient client) {
		return client.query(selectSchemaVersions())
						.execute()
						.map(VersionedSchema::mapToSchemaVersions);
	}

	private Future<Void> createSchemaVersionTable(SqlClient client) {
		return client.query(createSchemaVersion())
					.execute()
					.mapEmpty();
	}

	private Future<Void> createSchema(SqlClient client) {
		if (schema == null)
			return Future.succeededFuture();
		else
			return client.query("CREATE SCHEMA IF NOT EXISTS " + schema)
					.execute()
					.mapEmpty();
	}

	private Future<Void> setSchema(SqlClient client) {
		if (schema == null)
			return Future.succeededFuture();
		else
			return client.query("SET search_path TO " + schema/* + ", public" */)
					.execute()
					.mapEmpty();
	}


	/**
	 * Scans the migration directory and parses all migration scripts.
	 *
	 * <p>Migration files must follow the naming convention
	 * {@code &lt;version&gt;_&lt;description&gt;.sql}. Files are sorted by
	 * version in ascending order, and each file is checksummed using SHA-256.</p>
	 *
	 * @return a future containing the ordered list of migrations,
	 *         or an empty list if none are found
	 */
	private Future<List<Migration>> getMigrations() {
		if (schemaPath == null) {
			log.warn("Migration check: skipping, no schema migration path set");
			return Future.succeededFuture(List.of());
		}

		log.info("Migration check: checking for new migrations from {} ...", schemaPath);

		FileSystem fs = vertx.fileSystem();
		return fs.readDir(schemaPath.toString()).compose(files -> {
			List<Migration> migrations = new ArrayList<>();
			Future<Void> future = Future.succeededFuture();

			for (String f : files) {
				FileProps props = fs.propsBlocking(f);
				if (!props.isRegularFile()) {
					log.warn("Migration check: ignore non-regular file {}", f);
					continue;
				}

				Path file = Path.of(f);
				String name = file.getFileName().toString();
				if (!name.endsWith(".sql")) {
					log.warn("Migration check: ignore non-SQL file {}", name);
					continue;
				}

				future = future.compose(v -> buildMigration(file).andThen(ar -> {
					if (ar.succeeded())
						migrations.add(ar.result());
					else
						log.warn("Migration check: error build migration from {} - {}", name, ar.cause().getMessage());
				}).mapEmpty());
			}

			return future.map(migrations);
		}).map(migrations -> {
			if (migrations.isEmpty()) {
				log.warn("Migration check: no any migrations found");
				return List.of();
			}

			migrations.sort((m1, m2) -> {
				if (m1.version == m2.version) {
					log.error("Migration check: Migration file version must be unique. File names: {} and {}",
							m1.fileName(), m2.fileName());
					throw new IllegalStateException("Migration file version must be unique");
				}

				// noinspection ComparatorMethodParameterNotUsed
				return Integer.compare(m1.version, m2.version);
			});

			return migrations;
		});
	}

	/**
	 * Parses a migration file name and computes its checksum.
	 *
	 * <p>Expected file name format: {@code &lt;version&gt;_&lt;description&gt;.sql}</p>
	 *
	 * @param file path to the migration SQL file
	 * @return a future containing the parsed {@link Migration}
	 */
	private Future<Migration> buildMigration(Path file) {
		String fileName = file.getFileName().toString();
		String[] parts = fileName.split("_", 2);
		if (parts.length != 2)
			return Future.failedFuture(new IllegalStateException("Migration file name must be in format <version>_<description>.sql"));

		int version;
		try {
			version = Integer.parseInt(parts[0]);
		} catch (NumberFormatException e) {
			return Future.failedFuture(new IllegalStateException("Migration file name must be in format <version>_<description>.sql"));
		}

		int dotIndex = parts[1].lastIndexOf('.');
		String baseName = (dotIndex == -1) ? parts[1] : parts[1].substring(0, dotIndex);
		if (baseName.isEmpty())
			return Future.failedFuture(new IllegalStateException("Migration file name must be in format <version>_<description>.sql"));

		String description = baseName.replace('_', ' ');
		return sha256(file).map(hash -> new Migration(version, description, hash, file));
	}

	/**
	 * Reads the first non-empty line comment as a long description, if present.
	 *
	 * @param reader buffered reader positioned at the start of the file
	 * @return the description without the comment marker, or {@code null} if not present
	 * @throws IOException if reading fails
	 */
	private static String readDescriptionComment(BufferedReader reader) throws IOException {
		String description = null;

		reader.mark(4096);
		String line;
		while ((line = reader.readLine()) != null) {
			String trimmed = line.trim();
			if (trimmed.isEmpty())
				continue;

			if (trimmed.startsWith("--"))
				description = trimmed.substring(2).trim();

			break;
		}
		reader.reset();
		return description;
	}

	/**
	 * Reads the next full SQL statement from a reader, correctly handling:
	 * <ul>
	 *   <li>PostgreSQL dollar-quoted blocks ($$ or $tag$)</li>
	 *   <li>SQLite/PostgreSQL BEGIN...END; blocks (including nesting)</li>
	 *   <li>Line and block comments</li>
	 *   <li>Quoted strings and identifiers</li>
	 * </ul>
	 *
	 * @param reader buffered reader over a SQL script
	 * @return the next statement including the trailing semicolon, or {@code null} if EOF
	 * @throws IOException if reading fails
	 */
	private static String nextStatement(BufferedReader reader) throws IOException {
		StringBuilder statement = new StringBuilder();
		String line;
		String currentDollarTag = null;
		boolean inSingleQuote = false;
		boolean inDoubleQuote = false;
		boolean inBlockComment = false;
		int beginEndDepth = 0;

		while ((line = reader.readLine()) != null) {
			String trimmed = line.trim();
			if (trimmed.isEmpty())
				continue;

			if (trimmed.startsWith("--"))
				continue;

			int i = 0;
			while (i < line.length()) {
				char c = line.charAt(i);

				// Handle entering/exiting block comments
				if (!inSingleQuote && !inDoubleQuote && !inBlockComment && i + 1 < line.length()
						&& line.charAt(i) == '/' && line.charAt(i + 1) == '*') {
					inBlockComment = true;
					i += 2;
					continue;
				}
				if (inBlockComment) {
					if (i + 1 < line.length() && line.charAt(i) == '*' && line.charAt(i + 1) == '/') {
						inBlockComment = false;
						i += 2;
					} else {
						i++;
					}
					continue;
				}

				// Handle line comments
				if (!inSingleQuote && !inDoubleQuote && !inBlockComment && i + 1 < line.length()
						&& line.charAt(i) == '-' && line.charAt(i + 1) == '-') {
					// the rest of the line is a comment
					break;
				}

				// Handle entering/leaving quotes
				if (!inDoubleQuote && !inBlockComment && c == '\'' && currentDollarTag == null) {
					inSingleQuote = !inSingleQuote;
					i++;
					continue;
				}
				if (!inSingleQuote && !inBlockComment && c == '"' && currentDollarTag == null) {
					inDoubleQuote = !inDoubleQuote;
					i++;
					continue;
				}

				// Handle entering/leaving dollar-quoted blocks($$ or $tag$)
				if (!inSingleQuote && !inDoubleQuote && !inBlockComment && c == '$') {
					// Try to detect a tag like $tag$
					int j = i + 1;
					while (j < line.length() && Character.isLetterOrDigit(line.charAt(j))) j++;
					if (j < line.length() && line.charAt(j) == '$') {
						String tag = line.substring(i, j + 1);
						if (currentDollarTag == null) {
							currentDollarTag = tag; // entering
						} else if (currentDollarTag.equals(tag)) {
							currentDollarTag = null; // leaving
						}
						i = j + 1;
						continue;
					}
				}

				// Detect BEGIN/END keywords outside of quotes/comments
				if (!inSingleQuote && !inDoubleQuote && !inBlockComment && currentDollarTag == null) {
					// detect BEGIN
					if (startsKeyword(line, i, "BEGIN")) {
						beginEndDepth++;
					} else if (startsKeyword(line, i, "END")) {
						if (beginEndDepth > 0) {
							beginEndDepth--;
						}
					}
				}

				// Detect statement terminator only when safe
				if (!inSingleQuote && !inDoubleQuote && !inBlockComment && currentDollarTag == null && c == ';') {
					if (beginEndDepth == 0) {
						statement.append(line, 0, i + 1).append('\n');
						return statement.toString().trim();
					}
				}

				i++;
			}

			statement.append(line).append('\n');
		}

		if (statement.toString().trim().isEmpty())
			return null;
		else
			return statement.toString();
	}

	private static boolean startsKeyword(String line, int pos, String keyword) {
		int len = keyword.length();
		if (pos + len > line.length())
			return false;

		String sub = line.substring(pos, pos + len);
		if (!sub.equalsIgnoreCase(keyword))
			return false;

		// make sure not part of the longer word
		boolean beforeOk = (pos == 0) || !Character.isLetterOrDigit(line.charAt(pos - 1));
		boolean afterOk = (pos + len == line.length()) || !Character.isLetterOrDigit(line.charAt(pos + len));
		return beforeOk && afterOk;
	}

	/**
	 * Applies a single migration inside a database transaction.
	 *
	 * <p>The migration SQL file is split into individual statements, which are
	 * executed sequentially. Upon successful execution, a new entry is recorded
	 * in the {@code schema_versions} table.</p>
	 *
	 * @param migration the migration to apply
	 * @return a future completing with the applied {@link SchemaVersion}
	 */
	private Future<SchemaVersion> applyMigration(Migration migration) {
		log.info("Migration: applying migration version {} from {}...", migration.version, migration.fileName());

		long begin = System.currentTimeMillis();
		return withSchemaTransaction(connection -> {
			Promise<SchemaVersion> promise = Promise.promise();
			Future<Void> chain = Future.succeededFuture();
			try (BufferedReader reader = new BufferedReader(new FileReader(migration.file()))) {
				String longDescription = readDescriptionComment(reader);
				if (longDescription != null)
					migration.setDescription(longDescription);

				String statement;
				while ((statement = nextStatement(reader)) != null) {
					final String sql = statement;

					chain = chain.compose(vv -> {
						log.trace("Migration: executing statement {}", sql);
						return connection.query(sql).execute()
								.andThen(ar -> {
									if (ar.failed())
										log.error("Failed to execute SQL statement: {}", sql, ar.cause());
								}).mapEmpty();
					});
				}
			} catch (IOException e) {
				return Future.failedFuture(new IllegalStateException("Failed to read migration file", e));
			}

			chain.compose(vv -> {
				long duration = System.currentTimeMillis() - begin;
				log.info("Migration: applied migration file {} in {} ms", migration.fileName(), duration);
				log.debug("Migration: updating schema version...");
				SchemaVersion newVersion = new SchemaVersion(migration.version, migration.description,
						migration.hash, "", begin, duration, true);
				return connection.preparedQuery(insertSchemaVersion())
						.execute(
								Tuple.of(newVersion.version,
										newVersion.description,
										newVersion.hash,
										newVersion.appliedBy,
										newVersion.appliedAt,
										newVersion.consumedTime,
										newVersion.success))
						.map(newVersion);
			}).andThen(ar -> {
				if (ar.succeeded())
					log.debug("Migration: schema version updated to version {}", migration.version);
				else
					log.error("Migration: failed to update schema version.", ar.cause());
			}).onComplete(promise);

			return promise.future();
		});
	}

	private static List<SchemaVersion> mapToSchemaVersions(RowSet<Row> rowSet) {
		if (rowSet.size() == 0)
			return List.of();

		List<SchemaVersion> versions = new ArrayList<>(rowSet.size());
		for (Row row : rowSet) {
			int version = row.getInteger("version");
			String description = row.getString("description");
			String hash = row.getString("hash");
			String appliedBy = row.getString("applied_by");
			long appliedAt = row.getLong("applied_at");
			long consumedTime = row.getLong("consumed_time");
			boolean success = getBoolean(row, "success");

			SchemaVersion v = new SchemaVersion(version, description, hash, appliedBy, appliedAt, consumedTime, success);
			versions.add(v);
		}

		return versions;
	}

	private static boolean getBoolean(Row row, String columnName) {
		Object value = row.getValue(columnName);
		return value instanceof Boolean b ? b :
				(value instanceof Number n ? n.intValue() != 0 :
						(value instanceof String s && Boolean.parseBoolean(s)));
	}

	private Future<String> sha256(Path path) {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (Exception e) {
			return Future.failedFuture(e);
		}

		return vertx.fileSystem().open(path.toString(), new OpenOptions().setRead(true).setWrite(false)).compose(file -> {
			Promise<String> promise = Promise.promise();

			file.handler(buffer -> digest.update(buffer.getBytes()))
					.exceptionHandler(e -> {
						file.close();
						promise.fail(e);
					})
					.endHandler(v -> {
						String hash = Hex.encode(digest.digest());
						file.close();
						promise.complete(hash);
					});

			return promise.future();
		});
	}

	/**
	 * Creates schema_versions table if it does not exist.
	 *
	 * @return DDL for creating the schema_versions table if it does not exist
	 */
	protected String createSchemaVersion() {
		return createSchemaVersionTable;
	}

	/**
	 * Selects the latest successful schema version.
	 *
	 * @return SQL for selecting the latest successful schema version
	 */
	protected String selectSchemaVersions() {
		return selectSchemaVersions;
	}

	/**
	 * Inserts a new schema version record.
	 * Chooses the correct INSERT statement placeholder style for the detected database.
	 *
	 * @return parameterized INSERT SQL suitable for the target database
	 */
	protected String insertSchemaVersion() {
		if (databaseProductName.toLowerCase().contains("postgres"))
			return insertSchemaVersionWithIndexedParameters;
		else
			return insertSchemaVersionWithQuestionMarks;
	}

	private static final String createSchemaVersionTable = """
			CREATE TABLE IF NOT EXISTS schema_versions(
				version INTEGER PRIMARY KEY,
				description VARCHAR(512) UNIQUE DEFAULT NULL,
				hash VARCHAR(128) NOT NULL,
				applied_by VARCHAR(128),
				applied_at BIGINT NOT NULL,
				consumed_time BIGINT DEFAULT 0,
				success BOOLEAN NOT NULL)
			""";

	private static final String selectSchemaVersions = """
			SELECT * FROM schema_versions
				WHERE success = TRUE
				ORDER BY version ASC
			""";

	private static final String insertSchemaVersionWithQuestionMarks = """
			INSERT INTO schema_versions(version, description, hash, applied_by, applied_at, consumed_time, success)
				VALUES(?, ?, ?, ?, ?, ?, ?)
			""";

	private static final String insertSchemaVersionWithIndexedParameters = """
			INSERT INTO schema_versions(version, description, hash, applied_by, applied_at, consumed_time, success)
				VALUES($1, $2, $3, $4, $5, $6, $7)
			""";
}