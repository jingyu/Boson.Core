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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple, file-based schema migration helper for Vert.x SQL clients.
 * <p>
 * Reads migration SQL files from a directory, detects the database flavor,
 * and applies pending migrations transactionally, recording versions in a schema_versions table.
 * </p>
 */
public class VersionedSchema implements VertxDatabase {
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
	 * @param appliedBy    user/process that applied the migration
	 * @param appliedAt    timestamp (ms) when started
	 * @param consumedTime duration (ms) spent applying
	 * @param success      whether migration succeeded
	 */
	public record SchemaVersion(int version, String description, String appliedBy, long appliedAt,
								   long consumedTime, boolean success) {}

	private static class Migration {
		private final int version;
		private String description;
		private final Path path;

		/**
		 * Internal holder for a parsed migration file.
		 *
		 * @param version     numeric version
		 * @param description textual description
		 * @param path        file path to the SQL script
		 */
		public Migration(int version, String description, Path path) {
			this.version = version;
			this.description = description;
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

	private VersionedSchema(SqlClient client, Path schemaPath) {
		this.client = client;
		this.schemaPath = schemaPath;
	}

	/**
	 * Initializes a new {@link VersionedSchema} instance.
	 *
	 * @param client     Vert.x SQL client
	 * @param schemaPath directory containing migration SQL files
	 * @return a new versioned schema helper
	 */
	public static VersionedSchema init(SqlClient client, Path schemaPath) {
		return new VersionedSchema(client, schemaPath);
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
		}).compose(v ->
				getSchemaVersion()
		).compose(v -> {
			int version = 0;
			if (v != null) {
				this.currentVersion = v;
				version = this.currentVersion.version();
			}

			try {
				return Future.succeededFuture(getNewMigrations(version));
			} catch (IOException | IllegalStateException e) {
				return Future.failedFuture(new IllegalStateException("Migration check failed", e));
			}
		}).compose(migrations -> {
			if (migrations.isEmpty())
				return Future.succeededFuture();

			Promise<Void> promise = Promise.promise();
			Future<Void> chain = Future.succeededFuture();
			for (Migration migration : migrations)
				chain = chain.compose(na ->
						applyMigration(migration).map(v -> {
							this.currentVersion = v;
							return null;
						})
				);

			chain.onComplete(promise);
			return promise.future();
		});
	}

	/**
	 * Reads the latest successful schema version from the database.
	 *
	 * @return a future with the last applied {@link SchemaVersion} or {@code null}
	 */
	private Future<SchemaVersion> getSchemaVersion() {
		return query(selectSchemaVersion())
				.execute()
				.map(VersionedSchema::mapToSchemaVersion);
	}

	private static SchemaVersion mapToSchemaVersion(RowSet<Row> rowSet) {
		if (rowSet.size() == 0)
			return null;

		// first row only
		Row row = rowSet.iterator().next();
		int version = row.getInteger("version");
		String description = row.getString("description");
		String appliedBy = row.getString("applied_by");
		long appliedAt = row.getLong("applied_at");
		long consumedTime = row.getLong("consumed_time");
		boolean success = row.getBoolean("success");

		return new SchemaVersion(version, description, appliedBy, appliedAt, consumedTime, success);
	}

	/**
	 * Scans {@code schemaPath} and returns migrations with the version greater than {@code currentVersion}.
	 * File names must follow: {@code <version>_<description>.sql}.
	 *
	 * @param currentVersion the latest applied version
	 * @return sorted list of pending migrations
	 * @throws IOException            when reading the directory fails
	 * @throws IllegalStateException  on duplicate versions or malformed names
	 */
	private List<Migration> getNewMigrations(int currentVersion) throws IOException, IllegalStateException {
		if (schemaPath == null) {
			log.warn("Migration check: skipping, no schema migration path set");
			return List.of();
		}

		log.info("Migration check: checking for new migrations from {} ...", schemaPath);

		List<Migration> migrations = new ArrayList<>();
		Files.walkFileTree(schemaPath, new SimpleFileVisitor<>() {
			@Override
			@SuppressWarnings("NullableProblems")
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				String name = file.getFileName().toString();
				if (!name.endsWith(".sql")) {
					log.warn("Migration check: ignore non-SQL file {}", name);
					return FileVisitResult.CONTINUE;
				}

				Migration migration;
				try {
					migration = parseFileName(file);
					if (migration.version <= currentVersion)
						return FileVisitResult.CONTINUE;
				} catch (IllegalStateException e) {
					log.warn("Migration check: ignore malformed file name {} - {}", name, e.getMessage());
					return FileVisitResult.CONTINUE;
				}

				migrations.add(migration);
				return FileVisitResult.CONTINUE;
			}
		});

		if (migrations.isEmpty()) {
			log.info("Migration check: no new migrations found");
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
	}

	/**
	 * Parses a migration file name into a {@link Migration}.
	 * Expected format: {@code <version>_<description>.sql}
	 *
	 * @param file path to the migration file
	 * @return parsed {@link Migration}
	 * @throws IllegalStateException if the name does not match the expected pattern
	 */
	private static Migration parseFileName(Path file) {
		String fileName = file.getFileName().toString();
		String[] parts = fileName.split("_", 2);
		if (parts.length != 2)
			throw new IllegalStateException("Migration file name must be in format <version>_<description>.sql");

		int version;
		try {
			version = Integer.parseInt(parts[0]);
		} catch (NumberFormatException e) {
			throw new IllegalStateException("Migration file name must be in format <version>_<description>.sql");
		}

		int dotIndex = parts[1].lastIndexOf('.');
		String baseName = (dotIndex == -1) ? parts[1] : parts[1].substring(0, dotIndex);
		if (baseName.isEmpty())
			throw new IllegalStateException("Migration file name must be in format <version>_<description>.sql");

		String description = baseName.replace('_', ' ');
		return new Migration(version, description, file);
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
					log.trace("Migration: executing statement {}", sql);
					chain = chain.compose(v -> connection.query(sql).execute().mapEmpty());
				}
			} catch (IOException e) {
				return Future.failedFuture(new IllegalStateException("Failed to read migration file", e));
			}

			chain.compose(v -> {
				long duration = System.currentTimeMillis() - begin;
				log.info("Migration: applied migration file {} in {} ms", migration.fileName(), duration);
				log.debug("Migration: updating schema version...");
				SchemaVersion newVersion = new SchemaVersion(migration.version, migration.description,
						"", begin, duration, true);
				return connection.preparedQuery(insertSchemaVersion()).execute(
						Tuple.of(newVersion.version,
								newVersion.description,
								newVersion.appliedBy,
								newVersion.appliedAt,
								newVersion.consumedTime,
								newVersion.success)
				).andThen(ar -> {
					if (ar.succeeded())
						log.debug("Migration: schema version updated to version {}", migration.version);
					else
						log.error("Migration: failed to update schema version", ar.cause());
				}).map(newVersion);
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
	protected String selectSchemaVersion() {
		return selectSchemaVersion;
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
				applied_by VARCHAR(128),
				applied_at BIGINT NOT NULL,
				consumed_time BIGINT DEFAULT 0,
				success BOOLEAN NOT NULL)
			""";

	private static final String selectSchemaVersion = """
			SELECT * FROM schema_versions
				WHERE success = TRUE
				ORDER BY version DESC
				LIMIT 1
			""";

	private static final String insertSchemaVersionWithQuestionMarks = """
			INSERT INTO schema_versions(version, description, applied_by, applied_at, consumed_time, success)
				VALUES(?, ?, ?, ?, ?, ?)
			""";

	private static final String insertSchemaVersionWithIndexedParameters = """
			INSERT INTO schema_versions(version, description, applied_by, applied_at, consumed_time, success)
				VALUES($1, $2, $3, $4, $5, $6)
			""";
}