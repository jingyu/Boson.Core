package io.bosonnetwork.vertx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import io.vertx.core.Vertx;
import io.vertx.jdbcclient.JDBCConnectOptions;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import io.vertx.junit5.Timeout;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.utils.FileUtils;

@ExtendWith(VertxExtension.class)
public class VersionedSchemaTests {
	private static final Path testRoot = Path.of(System.getProperty("java.io.tmpdir"), "boson");
	private static final Path testDir = Path.of(testRoot.toString(), "utils", "VersionedSchemaTests");

	private static final List<Arguments> databases = new ArrayList<>();

	@BeforeAll
	static void setup(Vertx vertx, VertxTestContext context) throws Exception {
		Files.createDirectories(testDir);

		var sqliteURL = "jdbc:sqlite:" + testDir.resolve("test.db");
		JDBCConnectOptions sqliteConnectOptions = new JDBCConnectOptions()
				.setJdbcUrl(sqliteURL);
		// Single connection recommended for SQLite
		PoolOptions sqlitePoolOptions = new PoolOptions().setMaxSize(1);
		SqlClient sqliteClient = JDBCPool.pool(vertx, sqliteConnectOptions, sqlitePoolOptions);
		databases.add(Arguments.of("sqlite", sqliteClient));

		var postgresURL = "postgresql://jingyu:secret@localhost:5432/test";
		PgConnectOptions pgConnectOptions = PgConnectOptions.fromUri(postgresURL);
		PoolOptions pgPoolOptions = new PoolOptions().setMaxSize(8);
		SqlClient pgClient = PgBuilder.pool()
				.with(pgPoolOptions)
				.connectingTo(pgConnectOptions)
				.using(vertx)
				.build();
		// databases.add(Arguments.of("postgres", pgClient));

		context.completeNow();
	}

	@AfterAll
	static void teardown() throws Exception {
		FileUtils.deleteFile(testRoot);
	}

	static Stream<Arguments> testDatabaseProvider() {
		return databases.stream();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("testDatabaseProvider")
	@Timeout(value = 2, timeUnit = TimeUnit.MINUTES)
	void testMigrate(String name, SqlClient client, VertxTestContext context) {
		Path schemaPath = Path.of(getClass().getClassLoader().getResource("db/" + name).getPath());

		VersionedSchema schema = VersionedSchema.init(client, schemaPath);
		schema.migrate().onComplete(context.succeeding(v -> {
			context.verify(() -> {
				var sv = schema.getCurrentVersion();
				assertEquals(10, sv.version());
				assertEquals("Trigger: log message insertions into audit_log", sv.description());
			});

			context.completeNow();
		}));
	}
}