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

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.file.FileProps;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.OpenOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.utils.Hex;

/**
 * Simple, file-based schema migration helper for Vert.x SQL clients.
 * <p>
 * Reads migration SQL files from a directory, detects the database flavor,
 * and applies pending migrations transactionally, recording versions in a schema_versions table.
 * </p>
 */
public class VersionedSchema implements VertxDatabase {
	private static final SchemaVersion EMPTY_VERSION = new SchemaVersion(0, "",  null,"", 0, 0, true);
	private final Vertx vertx;
	private final SqlClient client;
	private final Path schemaPath;
	private String databaseProductName;
	private SchemaVersion currentVersion;

	private static final Logger log = LoggerFactory.getLogger(VersionedSchema.class);

	/**
	 * Immutable record of a migration application state.
	 *
	 * @param version      schema version number
	 * @param description  human-readable description
	 * @param hash         SHA-256 hash of the migration script
	 * @param appliedBy    user/process that applied the migration
	 * @param appliedAt    timestamp (ms) when started
	 * @param consumedTime duration (ms) spent applying
	 * @param success      whether migration succeeded
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

	private VersionedSchema(Vertx vertx, SqlClient client, Path schemaPath) {
		this.vertx = vertx;
		this.client = client;
		this.schemaPath = schemaPath;
		this.currentVersion = EMPTY_VERSION;
	}

	/**
	 * Initializes a new {@link VersionedSchema} instance.
	 *
	 * @param vertx      Vert.x instance
	 * @param client     Vert.x SQL client
	 * @param schemaPath directory containing migration SQL files
	 * @return a new versioned schema helper
	 */
	public static VersionedSchema init(Vertx vertx, SqlClient client, Path schemaPath) {
		return new VersionedSchema(vertx, client, schemaPath);
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
	 * Discovers and applies pending migrations found under {@code schemaPath}.
	 * <ol>
	 *   <li>Ensures the schema_versions table exists.</li>
	 *   <li>Reads the latest applied version.</li>
	 *   <li>Parses and sorts new migration files.</li>
	 *   <li>Applies each migration in order, transactionally.</li>
	 * </ol>
	 *
	 * @return a future completed when all pending migrations are applied
	 */
	public Future<Void> migrate() {
		return getDatabaseProductName().compose(name -> {
			databaseProductName = name;
			log.debug("Migration check: target database product {}", name);
			return query(createSchemaVersionTable()).execute();
		}).compose(v -> {
			Future<List<SchemaVersion>> versionsFuture = getSchemaVersions().andThen(ar -> {
				if (ar.succeeded()) {
					List<SchemaVersion> versions = ar.result();
					if (!versions.isEmpty())
						this.currentVersion = versions.get(versions.size() - 1);
				} else {
					log.warn("Migration check: error reading schema versions - {}", ar.cause().getMessage());
				}
			});

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

	/**
	 * Reads the current applied schema versions from the database.
	 *
	 * @return a future with the list of applied {@link SchemaVersion}, empty list if none
	 */
	private Future<List<SchemaVersion>> getSchemaVersions() {
		return query(selectSchemaVersions())
				.execute()
				.map(VersionedSchema::mapToSchemaVersions);
	}

	private static boolean getBoolean(Row row, String columnName) {
		Object value = row.getValue(columnName);
		return value instanceof Boolean b ? b :
				(value instanceof Number n ? n.intValue() != 0 :
						(value instanceof String s && Boolean.parseBoolean(s)));
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

	/**
	 * Scans {@code schemaPath} and returns all the migrations.
	 * File names must follow: {@code <version>_<description>.sql}.
	 *
	 * @return a future with the list of migrations, empty list if none
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
	 * Build a migration file name into a {@link Migration}.
	 * Expected format: {@code <version>_<description>.sql}
	 *
	 * @param file path to the migration file
	 * @return a future with the parsed migration file
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
	 * Applies a single migration inside a transaction and persists the new schema version.
	 *
	 * @param migration the migration to apply
	 * @return a future completing with the new {@link SchemaVersion} when done
	 */
	private Future<SchemaVersion> applyMigration(Migration migration) {
		log.info("Migration: applying migration version {} from {}...", migration.version, migration.fileName());

		long begin = System.currentTimeMillis();
		return withTransaction(connection -> {
			Promise<SchemaVersion> promise = Promise.promise();
			Future<Void> chain = Future.succeededFuture();
			try (BufferedReader reader = new BufferedReader(new FileReader(migration.file()))) {
				String longDescription = readDescriptionComment(reader);
				if (longDescription != null)
					migration.setDescription(longDescription);

				String statement;
				while ((statement = nextStatement(reader)) != null) {
					final String sql = statement;

					chain = chain.compose(v -> {
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

			chain.compose(v -> {
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

	/**
	 * Creates schema_versions table if it does not exist.
	 *
	 * @return DDL for creating the schema_versions table if it does not exist
	 */
	protected String createSchemaVersionTable() {
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