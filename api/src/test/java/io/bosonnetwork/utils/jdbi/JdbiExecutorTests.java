package io.bosonnetwork.utils.jdbi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;

import io.vertx.core.Vertx;
import org.jdbi.v3.core.Jdbi;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.utils.FileUtils;
import io.bosonnetwork.utils.jdbi.async.JdbiExecutor;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(VertxExtension.class)
public class JdbiExecutorTests {
	private static final Path testDir = Path.of(System.getProperty("java.io.tmpdir"), "boson", "JdbiExecutorTests");
	private static JdbiExecutor je;

	private static long userThreadId;

	public static class User {
		private int id;
		private String name;
		private String email;

		public User() {}

		public int getId() {
			return id;
		}
		public void setId(int id) {
			this.id = id;
		}
		public String getName() {
			return name;
		}
		public void setName(String name) {
			this.name = name;
		}
		public String getEmail() {
			return email;
		}
		public void setEmail(String email) {
			this.email = email;
		}

		@Override
		public String toString() {
			return "User #" + id + " [name = " + name + ", email = " + String.valueOf(email);
		}
	}

	@BeforeAll
	static void setup(Vertx vertx, VertxTestContext context) {
		vertx.runOnContext((v) -> {
			userThreadId = Thread.currentThread().getId();
			System.out.println(">>>>>>>> Vertx/user thread: " +  userThreadId);

			try {
				if (Files.exists(testDir))
					FileUtils.deleteFile(testDir);

				Files.createDirectories(testDir);
			} catch (Exception e) {
				context.failNow(e);
			}

			Jdbi jdbi = Jdbi.create("jdbc:sqlite:" + testDir.resolve("test.db").toString());

			je = JdbiExecutor.create(jdbi, vertx);

			context.completeNow();
		});
	}

	@AfterAll
	static void teardown(Vertx vertx, VertxTestContext context) throws Exception {
		vertx.runOnContext((v) -> {
			var threadId = Thread.currentThread().getId();
			System.out.println(">>>>>>>> Vertx/user thread: " +  threadId);

			context.verify(() -> {
				assertEquals(userThreadId, threadId);
			});

			je = null;

			try {
				FileUtils.deleteFile(testDir);
			} catch (Exception e) {
				context.failNow(e);
			}

			context.completeNow();
		});
	}

	@Test
	@Order(1)
	void testCreateTable(Vertx vertx, VertxTestContext context) {
		je.withHandle(handle -> {
			System.out.println("???????? JDBI Executor thread: " +  Thread.currentThread().getId());
			return handle.execute("create table users(id integer primary key, name varchar(32), email varchar(128))");
		}).onComplete(context.succeeding(rc -> {
			var threadId = Thread.currentThread().getId();
			System.out.println(">>>>>>>> Future thread: " +  threadId);
			context.verify(() -> {
				assertEquals(userThreadId, threadId);
				assertEquals(0, rc);
				context.completeNow();
			});
		}));
	}

	@Test
	@Order(2)
	void testInsert(Vertx vertx, VertxTestContext context) {
		je.useTransaction(handle -> {
			System.out.println("???????? JDBI Executor thread: " +  Thread.currentThread().getId());
			handle.execute("insert into users values(0, \"Root\", \"root@localhost\")");
			handle.execute("insert into users values(1, \"Foo\", \"foo@localhost\")");
			handle.execute("insert into users values(2, \"Bar\", \"bar@localhost\")");
			handle.execute("insert into users values(3, \"Alice\", \"alice@localhost\")");
			handle.execute("insert into users values(4, \"Bob\", \"bob@localhost\")");
			handle.execute("insert into users values(5, \"Charlie\", \"charlie@localhost\")");
		}).onComplete(context.succeeding(rc -> {
			var threadId = Thread.currentThread().getId();
			System.out.println(">>>>>>>> Future thread: " +  threadId);
			context.verify(() -> {
				assertEquals(userThreadId, threadId);
				context.completeNow();
			});
		}));
	}

	@Test
	@Order(3)
	void testSelect(Vertx vertx, VertxTestContext context) {
		je.withHandle(handle -> {
			System.out.println("???????? JDBI Executor thread: " +  Thread.currentThread().getId());
			return handle.createQuery("select * from users order by id")
					.mapToBean(User.class).list();
		}).onComplete(context.succeeding(users -> {
			var threadId = Thread.currentThread().getId();
			System.out.println(">>>>>>>> Future thread: " +  threadId);
			context.verify(() -> {
				assertEquals(userThreadId, threadId);
				assertNotNull(users);
				assertEquals(6, users.size());
				users.forEach(System.out::println);
				context.completeNow();
			});
		}));
	}

	@Test
	@Order(4)
	void testUpdate(Vertx vertx, VertxTestContext context) {
		je.withHandle(handle -> {
			System.out.println("???????? JDBI Executor thread: " +  Thread.currentThread().getId());
			return handle.createUpdate("update users set email = null where id > 0")
					.execute();
		}).onComplete(context.succeeding(rc -> {
			var threadId = Thread.currentThread().getId();
			System.out.println(">>>>>>>> Future thread: " +  threadId);
			context.verify(() -> {
				assertEquals(userThreadId, threadId);
				assertEquals(5, rc);
				context.completeNow();
			});
		}));
	}

	@Test
	@Order(5)
	void testDelete(Vertx vertx, VertxTestContext context) {
		je.withHandle(handle -> {
			System.out.println("???????? JDBI Executor thread: " +  Thread.currentThread().getId());
			var batch = handle.prepareBatch("delete from users where name = ?");
			batch.bind(0, "Foo").add();
			batch.bind(0, "Bar").add();
			int[] rc = batch.execute();
			return IntStream.of(rc).sum();
		}).onComplete(context.succeeding(rc -> {
			var threadId = Thread.currentThread().getId();
			System.out.println(">>>>>>>> Future thread: " +  threadId);
			context.verify(() -> {
				assertEquals(userThreadId, threadId);
				assertEquals(2, rc);
				context.completeNow();
			});
		}));
	}

	@Test
	@Order(6)
	void testSelectAgain(Vertx vertx, VertxTestContext context) {
		je.withHandle(handle -> {
			System.out.println("???????? JDBI Executor thread: " +  Thread.currentThread().getId());
			return handle.createQuery("select * from users order by id")
					.mapToBean(User.class).list();
		}).onComplete(context.succeeding(users -> {
			var threadId = Thread.currentThread().getId();
			System.out.println(">>>>>>>> Future thread: " +  threadId);
			context.verify(() -> {
				assertEquals(userThreadId, threadId);
				assertNotNull(users);
				assertEquals(4, users.size());
				users.forEach(System.out::println);
				context.completeNow();
			});
		}));
	}
}