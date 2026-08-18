package io.bosonnetwork.kademlia.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.impl.Network;
import io.bosonnetwork.kademlia.impl.TestKadContext;
import io.bosonnetwork.kademlia.rpc.RpcCall;

@ExtendWith(VertxExtension.class)
class TaskManagerTests {
	private static final int TEST_MAX_CONCURRENT_TASKS = 16;
	private Context vertxContext;
	private KadContext kadContext;
	private TaskManager manager;

	static class TestTask extends Task<TestTask> {
		private static final Logger log = LoggerFactory.getLogger(TestTask.class);

		public TestTask(KadContext context) {
			super(context);
		}

		@Override
		protected void iterate() {
		}

		@Override
		protected Future<RpcCall> sendCall(RpcCall call) {
			return Future.succeededFuture(call);
		}

		@Override
		protected boolean isDone() {
			return false;
		}

		@Override
		protected String getStatus() {
			return super.toString();
		}

		@Override
		protected Logger getLogger() {
			return log;
		}

		public void complete() {
			super.complete();
		}


	}

	static class TestTaskListener implements TaskListener<TestTask> {
		private static final Logger log = LoggerFactory.getLogger(TestTaskListener.class);

		public void started(TestTask task) {
			log.debug("Task {}:{} started", task.getName(), task.getId());
		}

		public void completed(TestTask task) {
			log.debug("Task {}:{} completed", task.getName(), task.getId());
		}

		public void canceled(TestTask task) {
			log.debug("Task {}:{} canceled", task.getName(), task.getId());
		}

		public void ended(TestTask task) {
			log.debug("Task {}:{} ended", task.getName(), task.getId());
		}
	}

	@BeforeEach
	void setUp(Vertx vertx, VertxTestContext context) {
		vertxContext = vertx.getOrCreateContext();
		this.kadContext = new TestKadContext(vertxContext, new CryptoIdentity(), Network.IPv4)
				.setConcurrentTasks(TEST_MAX_CONCURRENT_TASKS);
		this.manager = new TaskManager(kadContext);
		context.completeNow();
	}

	@Test
	void testTaskLifeCycle(VertxTestContext context) {
		CountDownLatch startedSignal = new CountDownLatch(1);
		CountDownLatch completeSignal = new CountDownLatch(1);

		TestTask task = new TestTask(kadContext)
				.setName("Foobar")
				.addListener(new TestTaskListener() {
					public void started(TestTask task) {
						super.started(task);
						startedSignal.countDown();
					}

					public void completed(TestTask task) {
						super.started(task);
						completeSignal.countDown();
					}
				});

		Promise<Void> promise = Promise.promise();
		kadContext.runOnContext(() -> {
			manager.add(task);
			promise.complete();
		});

		promise.future().onComplete(context.succeeding(unused -> {
			context.verify(() -> {
				assertTrue(manager.getQueuedTasks() == 1 || manager.getRunningTasks() == 1);
			});
		}));

		try {
			if (!startedSignal.await(5, TimeUnit.SECONDS))
				context.failNow("Timeout");
		} catch (InterruptedException e) {
			context.failNow(e);
		}

		assertEquals(1, manager.getRunningTasks());
		assertEquals(0, manager.getQueuedTasks());

		task.complete();

		try {
			if (!completeSignal.await(5, TimeUnit.SECONDS))
				context.failNow("Timeout");
		} catch (InterruptedException e) {
			context.failNow(e);
		}

		assertEquals(0, manager.getRunningTasks());
		assertEquals(0, manager.getQueuedTasks());

		context.completeNow();
	}

	@Test
	void testCancelAll(VertxTestContext context) {
		TestTask task1 = new TestTask(kadContext).setName("Panda").addListener(new TestTaskListener());
		TestTask task2 = new TestTask(kadContext).setName("Dragon").addListener(new TestTaskListener());
		TestTask task3 = new TestTask(kadContext).setName("Tiger").addListener(new TestTaskListener());

		kadContext.runOnContext(() -> {
			manager.add(task1);
			manager.add(task2);
			manager.add(task3);
		});

		try {
			while (!task3.isRunning())
				//noinspection BusyWait
				Thread.sleep(500);
		} catch (InterruptedException e) {
			context.failNow(e);
		}

		assertEquals(3, manager.getRunningTasks());
		assertEquals(0, manager.getQueuedTasks());

		Promise<Void> promise = Promise.promise();
		kadContext.runOnContext(() -> {
			manager.cancelAll();
			promise.complete();
		});

		promise.future().onComplete(context.succeeding(unused -> {
			context.verify(() -> {
				assertTrue(task1.isCanceled());
				assertTrue(task2.isCanceled());
				assertTrue(task3.isCanceled());
				assertEquals(0, manager.getRunningTasks());
				assertEquals(0, manager.getQueuedTasks());
			});
			context.completeNow();
		}));
	}

	/**
	 * The configured active-task ceiling must actually take effect.
	 * <p>
	 * {@link #testMaxActiveTasks} exercises only the default-constructed manager, so it passes whether
	 * the limit comes from the constructor argument or from the default constant. This test uses a
	 * ceiling that differs from the default, so a manager that ignores its argument fails here.
	 */
	@Test
	void testConfiguredMaxActiveTasksIsHonored(Vertx vertx, VertxTestContext context) {
		int configured = 2;
		KadContext kadContext = new TestKadContext(vertxContext, new CryptoIdentity(), Network.IPv4)
				.setConcurrentTasks(configured);
		TaskManager limited = new TaskManager(kadContext);

		List<TestTask> tasks = new ArrayList<>();
		for (int i = 0; i < configured + 3; i++) {
			TestTask t = new TestTask(kadContext).setName("LimitedTask" + i).addListener(new TestTaskListener());
			tasks.add(t);
			kadContext.runOnContext(() -> limited.add(t));
		}

		try {
			// wait for the manager to start as many as it is willing to
			while (tasks.get(configured - 1).isUnstarted())
				//noinspection BusyWait
				Thread.sleep(100);
		} catch (InterruptedException e) {
			context.failNow(e);
		}

		assertEquals(configured, limited.getRunningTasks());
		assertEquals(3, limited.getQueuedTasks());
		assertFalse(limited.isReady());

		limited.cancelAll();
		context.completeNow();
	}

	@Test
	void testMaxActiveTasks(VertxTestContext context) {
		int max = manager.getMaxActiveTasks();
		TestTask task = null;
		for (int i = 0; i < max; i++) {
			task = new TestTask(kadContext).setName("TestTask" + i).addListener(new TestTaskListener());
			TestTask t = task;
			kadContext.runOnContext(() -> manager.add(t));
		}

		try {
			// check the last task is started
			while (task.isUnstarted())
				//noinspection BusyWait
				Thread.sleep(500);
		} catch (InterruptedException e) {
			context.failNow(e);
		}

		assertEquals(max, manager.getRunningTasks());
		assertEquals(0, manager.getQueuedTasks());

		TestTask extraTask = new TestTask(kadContext).setName("ExtraTestTask").addListener(new TestTaskListener());
		Promise<Void> promise = Promise.promise();
		kadContext.runOnContext(() -> {
			manager.add(extraTask);
			promise.complete();
		});

		promise.future().onComplete(context.succeeding(unused -> {
			context.verify(() -> {
				assertEquals(max, manager.getRunningTasks());
				assertEquals(1, manager.getQueuedTasks());
			});
			context.completeNow();
		}));
	}

	/**
	 * A task the manager refuses must still reach a terminal state.
	 * <p>
	 * Callers wait on a task through its listener, and the state they guard while waiting tends to be a
	 * latch - an "already bootstrapping" flag, a per-bucket "maintenance in flight" entry. A task that
	 * leaves {@code add} without ever ending leaves that latch set for good, so one rejected task is
	 * really a mechanism disabled permanently. Rejection here means the task was not INITIAL, which is
	 * an internal error; the point is that the error path is not also a leak.
	 * </p>
	 */
	@Test
	void testRejectedTaskStillEnds(VertxTestContext context) {
		CountDownLatch endedSignal = new CountDownLatch(1);

		TestTask task = new TestTask(kadContext)
				.setName("AlreadyQueued")
				.addListener(new TestTaskListener() {
					public void ended(TestTask task) {
						super.ended(task);
						endedSignal.countDown();
					}
				});

		kadContext.runOnContext(() -> {
			// The first add leaves the task QUEUED, so the second finds it out of INITIAL and rejects it.
			manager.add(task);
			manager.add(task);
		});

		try {
			if (!endedSignal.await(5, TimeUnit.SECONDS))
				context.failNow("a rejected task never ended - its listeners would wait forever");
		} catch (InterruptedException e) {
			context.failNow(e);
		}

		context.verify(() -> assertTrue(task.isEnd(), "a rejected task must be in a terminal state"));
		context.completeNow();
	}

	/** A task that reports itself overdue; the sweep is driven directly rather than waited for. */
	static class OverdueTask extends TestTask {
		OverdueTask(KadContext context) {
			super(context);
		}

		@Override
		protected Duration deadline() {
			// Negative rather than ZERO, so the task is overdue at every age including zero. With ZERO
			// this test depended on at least one millisecond passing between the task starting and the
			// sweep running - isOverdue() is age > deadline, and both land in the same millisecond often
			// enough to fail under load.
			return Duration.ofMillis(-1);
		}
	}

	/**
	 * The safety net: a running task that outlives its deadline is cancelled, so its slot and its
	 * caller's listener come back.
	 * <p>
	 * {@code TestTask.isDone()} is permanently false and its {@code iterate()} sends nothing, which is
	 * exactly the shape of the stall this exists for - iteration is driven only by call state changes, so
	 * nothing will ever drive it again.
	 * </p>
	 */
	@Test
	void testAnOverdueTaskIsCanceled(VertxTestContext context) {
		CountDownLatch endedSignal = new CountDownLatch(1);
		OverdueTask task = new OverdueTask(kadContext);
		task.addListener(new TaskListener<>() {
			@Override
			public void ended(TestTask t) {
				endedSignal.countDown();
			}
		});

		kadContext.runOnContext(() -> manager.add(task));

		try {
			if (!waitFor(() -> task.getState() == Task.State.RUNNING))
				context.failNow("the task never started");

			kadContext.runOnContext(manager::checkDeadlines);

			if (!endedSignal.await(5, TimeUnit.SECONDS))
				context.failNow("an overdue task was left running, holding its slot forever");
		} catch (InterruptedException e) {
			context.failNow(e);
		}

		context.verify(() -> {
			assertEquals(Task.State.CANCELED, task.getState());
			assertEquals(0, manager.getRunningTasks(), "the slot must be reclaimed");
		});
		context.completeNow();
	}

	/**
	 * The counterpart, and the one that matters more: a task inside its deadline is left alone. A sweep
	 * that cancelled healthy work would truncate slow lookups on a bad network, which is worse than the
	 * stall it exists to bound.
	 */
	@Test
	void testATaskWithinItsDeadlineIsLeftAlone(VertxTestContext context) {
		TestTask task = new TestTask(kadContext);

		kadContext.runOnContext(() -> manager.add(task));

		try {
			if (!waitFor(() -> task.getState() == Task.State.RUNNING))
				context.failNow("the task never started");

			kadContext.runOnContext(manager::checkDeadlines);
			Thread.sleep(200);
		} catch (InterruptedException e) {
			context.failNow(e);
		}

		context.verify(() -> {
			assertEquals(Task.State.RUNNING, task.getState());
			assertEquals(1, manager.getRunningTasks());
		});
		context.completeNow();
	}

	private boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			if (condition.getAsBoolean())
				return true;
			Thread.sleep(20);
		}
		return false;
	}
}
