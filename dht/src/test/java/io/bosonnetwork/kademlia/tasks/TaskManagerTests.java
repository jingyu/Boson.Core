package io.bosonnetwork.kademlia.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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

	/**
	 * Builds {@code pairs} parent tasks, each with a nested task, and returns both halves in the order
	 * they were created so the caller can register every one of them.
	 * <p>
	 * <b>Half the pairs are built parent-first and half nested-first, and that is what makes the tests
	 * using this mean anything.</b> {@code cancelAll} clears each task's end handler as it reaches it, so
	 * a parent reached <em>after</em> its own nested task cascades into a task that can no longer route
	 * itself back into the manager - the hazard simply does not arise. Whether a parent is reached first
	 * is down to iteration order, and neither collection's is arbitrary: {@code Task.hashCode()} is the
	 * sequential task id, so the running set walks in creation order, and the queue is a list that walks
	 * in insertion order. Building every pair the same way therefore fixes the answer for all of them at
	 * once. Alternating fixes it the other way for half, so no single iteration order can defuse them all.
	 * </p>
	 *
	 * @param context the context the tasks belong to
	 * @param pairs   how many parent/nested pairs to build
	 * @return every task built, parents and nested alike
	 */
	private static List<TestTask> nestedPairs(KadContext context, int pairs) {
		List<TestTask> tasks = new ArrayList<>();
		for (int i = 0; i < pairs; i++) {
			TestTask parent;
			TestTask nested;
			if (i % 2 == 0) {
				parent = new TestTask(context).setName("Parent" + i);
				nested = new TestTask(context).setName("Nested" + i);
			} else {
				nested = new TestTask(context).setName("Nested" + i);
				parent = new TestTask(context).setName("Parent" + i);
			}
			parent.setNestedTask(nested);

			// Added in creation order, so the queue's insertion order matches the running set's.
			if (i % 2 == 0) {
				tasks.add(parent);
				tasks.add(nested);
			} else {
				tasks.add(nested);
				tasks.add(parent);
			}
		}
		return tasks;
	}

	/**
	 * Runs {@code cancelAll} on the event loop and reports what it threw, if anything.
	 * <p>
	 * Left to throw inside a plain {@code runOnContext} block, the cause would be swallowed by the
	 * context's exception handler and the test would fail as a timeout, naming nothing. This carries it
	 * out to the assertion.
	 * </p>
	 */
	private static Future<Void> cancelAllReportingThrows(KadContext context, TaskManager manager) {
		Promise<Void> promise = Promise.promise();
		context.runOnContext(() -> {
			try {
				manager.cancelAll();
				promise.complete();
			} catch (Throwable t) {
				promise.fail(t);
			}
		});
		return promise.future();
	}

	/**
	 * Cancelling everything must survive a nested task that is itself registered with the manager.
	 * <p>
	 * {@code cancelAll} clears the end handler of each task before cancelling it, which stops that task
	 * routing itself back into the manager - but {@code Task.cancel()} cascades to the task's nested task,
	 * and the nested task's handler is not the one that was cleared. A registered nested task therefore
	 * removes itself from the set the parent is being iterated out of, which is a
	 * {@code ConcurrentModificationException} on the running set.
	 * </p>
	 * <p>
	 * Six pairs rather than one, and built in alternating order - see {@link #nestedPairs}. A
	 * {@code HashMap} iterator checks the modification count in {@code next()} and not in
	 * {@code hasNext()}, so a lone parent that landed last would let the loop finish and the test would
	 * pass on luck; and a parent reached after its own nested task cannot trigger the hazard at all.
	 * </p>
	 */
	@Test
	void testCancelAllSurvivesARegisteredNestedTask(VertxTestContext context) {
		List<TestTask> tasks = nestedPairs(kadContext, 6);

		kadContext.runOnContext(() -> tasks.forEach(manager::add));

		try {
			if (!waitFor(() -> manager.getRunningTasks() == tasks.size()))
				context.failNow("the tasks never started");
		} catch (InterruptedException e) {
			context.failNow(e);
		}

		cancelAllReportingThrows(kadContext, manager).onComplete(context.succeeding(unused -> {
			context.verify(() -> {
				assertEquals(0, manager.getRunningTasks());
				assertEquals(0, manager.getQueuedTasks());
				for (TestTask task : tasks)
					assertTrue(task.isCanceled(), task.getName() + " was left uncancelled");
			});
			context.completeNow();
		}));
	}

	/**
	 * The same hazard on the queue, where it does not announce itself.
	 * <p>
	 * {@code queuedTasks} is a {@code LinkedList}, whose iterator answers {@code hasNext()} from the
	 * current size. A nested task removing itself mid-walk shortens that list, so the loop can end an
	 * element early instead of throwing - leaving a task uncancelled, still registered, and still holding
	 * the handler that was supposed to be cleared. Whoever waits on that task waits for good, which is why
	 * this asserts on every task rather than only on the absence of an exception.
	 * </p>
	 */
	@Test
	void testCancelAllSurvivesARegisteredNestedQueuedTask(VertxTestContext context) {
		// One slot, so the first task admitted runs and everything after it stays queued.
		KadContext limitedContext = new TestKadContext(vertxContext, new CryptoIdentity(), Network.IPv4)
				.setConcurrentTasks(1);
		TaskManager limited = new TaskManager(limitedContext);

		TestTask filler = new TestTask(limitedContext).setName("Filler");
		List<TestTask> queued = nestedPairs(limitedContext, 6);

		limitedContext.runOnContext(() -> {
			limited.add(filler);
			queued.forEach(limited::add);
		});

		try {
			if (!waitFor(() -> limited.getQueuedTasks() == queued.size()))
				context.failNow("the tasks never queued: queued=" + limited.getQueuedTasks());
		} catch (InterruptedException e) {
			context.failNow(e);
		}

		cancelAllReportingThrows(limitedContext, limited).onComplete(context.succeeding(unused -> {
			context.verify(() -> {
				assertEquals(0, limited.getRunningTasks());
				assertEquals(0, limited.getQueuedTasks());
				assertTrue(filler.isCanceled(), "the running task was left uncancelled");
				for (TestTask task : queued)
					assertTrue(task.isCanceled(), task.getName() + " was left uncancelled");
			});
			context.completeNow();
		}));
	}

	private boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
		for (int i = 0; i < 100; i++) {
			if (condition.getAsBoolean())
				return true;
			Thread.sleep(20);
		}
		return false;
	}

	/**
	 * Captures what a logger emitted, with that logger forced to DEBUG.
	 * <p>
	 * Forced on purpose: the module's test configuration is INFO, so a message downgraded to DEBUG would
	 * be indistinguishable from a message deleted. Raising the level lets these tests assert both halves -
	 * that the report is still made, and that it is no longer made at a level that says the node is broken.
	 * </p>
	 */
	private static final class CapturedLog implements AutoCloseable {
		private final ch.qos.logback.classic.Logger logger;
		private final ch.qos.logback.classic.Level previousLevel;
		private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

		CapturedLog(Class<?> owner) {
			logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(owner);
			previousLevel = logger.getLevel();
			logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
			appender.start();
			logger.addAppender(appender);
		}

		List<ILoggingEvent> atOrAbove(ch.qos.logback.classic.Level level) {
			return appender.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(level)).toList();
		}

		boolean hasDebug() {
			return appender.list.stream().anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.DEBUG);
		}

		@Override
		public void close() {
			logger.detachAppender(appender);
			appender.stop();
			logger.setLevel(previousLevel);
		}
	}

	private static String render(List<ILoggingEvent> events) {
		return events.stream().map(e -> e.getLevel() + " " + e.getFormattedMessage()).toList().toString();
	}

	/**
	 * Cancelling a task that has already ended must not read as a defect.
	 * <p>
	 * It is reachable from three ordinary places - {@code add}'s rejection recovery, {@code cancelAll}
	 * reaching a task a nested cascade already cancelled, and a publish cancelling an announce whose
	 * lookup cancelled it first - and in every one of them the caller is written to expect it.
	 * </p>
	 */
	@Test
	void testCancellingAnEndedTaskIsNotAnAlarm(VertxTestContext context) {
		TestTask task = new TestTask(kadContext).setName("AlreadyGone");
		task.cancel();

		try (CapturedLog log = new CapturedLog(TestTask.class)) {
			task.cancel();

			context.verify(() -> {
				assertTrue(log.atOrAbove(ch.qos.logback.classic.Level.WARN).isEmpty(),
						"a routine double cancel was announced as a problem: "
								+ render(log.atOrAbove(ch.qos.logback.classic.Level.WARN)));
				assertTrue(log.hasDebug(), "the refusal must still be reported, only quietly");
			});
		}
		context.completeNow();
	}

	/**
	 * The same for a task that was cancelled while queued and then reached {@code start}, which is the
	 * race the finding was filed for. One benign outcome used to produce three lines, one of them ERROR.
	 */
	/**
	 * The race the finding was filed for, collapsed to the point where it shows.
	 * <p>
	 * A queued task is started by {@code dequeue} scheduling {@code start} on the event loop, so anything
	 * that cancels it in between - {@code cancelAll}, a caller dropping the work - leaves {@code start}
	 * running against a task that has already ended. Nothing is wrong when that happens, and it used to
	 * be announced as an invalid state transition.
	 * </p>
	 * <p>
	 * Driven directly rather than through the manager: the two-step is what makes the race, and
	 * {@code TaskManager.add} asserts its input is not already ended, so the manager cannot be used to
	 * stage it under test.
	 * </p>
	 */
	@Test
	void testStartingACancelledTaskIsNotAnAlarm(VertxTestContext context) {
		TestTask task = new TestTask(kadContext).setName("CancelledWhileQueued");
		task.cancel();

		try (CapturedLog log = new CapturedLog(TestTask.class)) {
			task.start();

			context.verify(() -> {
				assertTrue(log.atOrAbove(ch.qos.logback.classic.Level.WARN).isEmpty(),
						"a task overtaken by its own cancellation was reported as a defect: "
								+ render(log.atOrAbove(ch.qos.logback.classic.Level.WARN)));
				assertTrue(log.hasDebug(), "the refusal must still be reported, only quietly");
				assertEquals(Task.State.CANCELED, task.getState(), "start must not revive an ended task");
			});
		}
		context.completeNow();
	}

	/**
	 * The rule has two halves, and this is the half that keeps it from being a blanket mute: a task found
	 * in a live but unexpected state has been overtaken by nothing, so it is still a defect. Starting a
	 * task that is already running is the plainest example.
	 */
	@Test
	void testAnUnexpectedLiveStateIsStillAnAlarm(VertxTestContext context) {
		TestTask task = new TestTask(kadContext).setName("StartedTwice");
		task.start();

		try (CapturedLog log = new CapturedLog(TestTask.class)) {
			task.start();

			context.verify(() -> assertFalse(log.atOrAbove(ch.qos.logback.classic.Level.WARN).isEmpty(),
					"a task in a live but wrong state must still be a warning"));
		}
		context.completeNow();
	}

	/**
	 * And the counterpart that keeps this from being a blanket mute: a task added twice while it is alive
	 * has been overtaken by nothing, so it is a caller bug and still says so.
	 */
	@Test
	void testAddingTheSameLiveTaskTwiceIsStillAnAlarm(VertxTestContext context) {
		TestTask task = new TestTask(kadContext).setName("AddedTwice");

		CapturedLog managerLog = new CapturedLog(TaskManager.class);
		CapturedLog taskLog = new CapturedLog(TestTask.class);

		kadContext.runOnContext(() -> {
			try {
				// The first add leaves the task QUEUED, so the second finds it alive and already ours.
				manager.add(task);
				manager.add(task);

				context.verify(() -> {
					assertFalse(managerLog.atOrAbove(ch.qos.logback.classic.Level.ERROR).isEmpty(),
							"a double add is a caller bug and must still be reported as one");
					assertFalse(taskLog.atOrAbove(ch.qos.logback.classic.Level.WARN).isEmpty(),
							"an unexpected live state must still be a warning");
				});
				context.completeNow();
			} finally {
				taskLog.close();
				managerLog.close();
			}
		});
	}

	/**
	 * The running set iterates in the order tasks were admitted.
	 * <p>
	 * It did so before as well, but only because {@code Task.hashCode} was its sequential id, so a
	 * {@code HashSet} happened to bucket them in creation order. That is not a property, and leaning on it
	 * cost the {@code cancelAll} tests above their meaning once already. The order is now the collection's
	 * doing and can be asserted.
	 * </p>
	 */
	@Test
	void testTheRunningSetKeepsAdmissionOrder(VertxTestContext context) {
		List<TestTask> tasks = new ArrayList<>();
		for (int i = 0; i < 6; i++)
			tasks.add(new TestTask(kadContext).setName("Ordered" + i));

		kadContext.runOnContext(() -> tasks.forEach(manager::add));

		try {
			if (!waitFor(() -> manager.getRunningTasks() == tasks.size()))
				context.failNow("the tasks never started");
		} catch (InterruptedException e) {
			context.failNow(e);
		}

		String rendered = manager.toString();
		int previous = -1;
		for (TestTask task : tasks) {
			int at = rendered.indexOf(task.getName());
			int found = at;
			int before = previous;
			context.verify(() -> {
				assertTrue(found > before, "the running set lost its admission order");
			});
			previous = at;
		}
		context.completeNow();
	}
}
