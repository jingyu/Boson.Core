package io.bosonnetwork.kademlia.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;

import io.bosonnetwork.Network;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.rpc.RpcCall;

@ExtendWith(VertxExtension.class)
class TaskManagerTests {
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
		protected void sendCall(RpcCall call) {
			// do nothing
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
		this.kadContext = new KadContext(vertx, vertx.getOrCreateContext(), new CryptoIdentity(), Network.IPv4,null);
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

	@Test
	void testMaxActiveTasks(VertxTestContext context) {
		int max = TaskManager.MAX_ACTIVE_TASKS;
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
}