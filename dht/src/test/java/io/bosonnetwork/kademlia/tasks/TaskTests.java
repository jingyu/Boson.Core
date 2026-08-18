package io.bosonnetwork.kademlia.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.impl.Network;
import io.bosonnetwork.kademlia.impl.TestKadContext;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.rpc.RpcCall;
import io.bosonnetwork.utils.Variable;

public class TaskTests {
	private static final Vertx vertx = Vertx.vertx();
	private KadContext context;
	private TestTask task;

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

	@BeforeEach
	void setUp() {
		context = new TestKadContext(vertx.getOrCreateContext(), new CryptoIdentity(), Network.IPv4);
		task = new TestTask(context);
	}

	@Test
	void testStateTransitions() {
		assertEquals(Task.State.INITIAL, task.getState());
		assertTrue(task.setState(Task.State.INITIAL, Task.State.QUEUED));
		assertEquals(Task.State.QUEUED, task.getState());
		assertTrue(task.setState(Task.State.QUEUED, Task.State.RUNNING));
		assertEquals(Task.State.RUNNING, task.getState());
		assertTrue(task.setState(Task.State.RUNNING, Task.State.COMPLETED));
		assertEquals(Task.State.COMPLETED, task.getState());
		assertTrue(task.isEnd());
		assertFalse(task.setState(Task.State.COMPLETED, Task.State.INITIAL));
	}

	@Test
	void testListenerNotifications() {
		Variable<Boolean> started = Variable.of(false);
		Variable<Boolean> completed = Variable.of(false);
		Variable<Boolean> canceled = Variable.of(false);
		Variable<Boolean> ended = Variable.of(false);

		TaskListener<TestTask> listener = new TaskListener<>() {
			@Override
			public void started(TestTask task) {
				started.set(true);
			}

			@Override
			public void completed(TestTask task) {
				completed.set(true);
			}

			@Override
			public void canceled(TestTask task) {
				canceled.set(true);
			}

			@Override
			public void ended(TestTask task) {
				ended.set(true);
			}
		};

		TestTask task = new TestTask(context).addListener(listener);

		task.start();
		assertTrue(started.get());
		assertFalse(completed.get());
		assertFalse(canceled.get());
		assertFalse(ended.get());
		task.complete();
		assertTrue(completed.get());
		assertFalse(canceled.get());
		assertTrue(ended.get());

		started.set(false);
		completed.set(false);
		canceled.set(false);
		ended.set(false);

		task = new TestTask(context).addListener(listener);

		task.start();
		assertTrue(started.get());
		assertFalse(completed.get());
		assertFalse(canceled.get());
		assertFalse(ended.get());
		task.cancel();
		assertFalse(completed.get());
		assertTrue(canceled.get());
		assertTrue(ended.get());
	}

	private void cancelCall(RpcCall call) {
		try {
			Method cancel = RpcCall.class.getDeclaredMethod("cancel");
			cancel.setAccessible(true);
			cancel.invoke(call);
		} catch (Exception e) {
			throw new RuntimeException("cancelCall failed", e);
		}
	}

	private void setCallResponse(RpcCall call, Message response) {
		try {
			Class<RpcCall> clazz = RpcCall.class;
			Method respond = clazz.getDeclaredMethod("respond", Message.class);
			respond.setAccessible(true);
			respond.invoke(call, response);
		} catch (Exception e) {
			throw new RuntimeException("setCallResponse failed", e);
		}
	}

	@Test
	void testRpcLimits() {
		task.start();
		assertTrue(task.canDoRequest());

		List<RpcCall> calls = new ArrayList<>();
		for (int i = 0; i < context.getAlpha(); i++) {
			NodeInfo node = NodeInfo.of(Id.random(), "192.168.1.8", Random.random().nextInt(1024, 65536));
			Message message = Message.pingRequest();
			task.sendCall(node, message, calls::add, null);
			assertEquals(i + 1, task.getInFlightCalls());
		}
		assertFalse(task.canDoRequest());
		RpcCall call = calls.get(0);
		Message response = Message.pingResponse(call.getTxid());
		setCallResponse(call, response);
		assertTrue(task.canDoRequest());
	}

	@Test
	void testLowPriorityRpcLimits() {
		task.lowPriority();
		task.start();
		assertTrue(task.canDoRequest());

		List<RpcCall> calls = new ArrayList<>();
		for (int i = 0; i < context.getLowPriorityAlpha(); i++) {
			NodeInfo node = NodeInfo.of(Id.random(), "192.168.1.8", Random.random().nextInt(1024, 65536));
			Message message = Message.pingRequest();
			task.sendCall(node, message, calls::add, null);
			assertEquals(i + 1, task.getInFlightCalls());
		}
		assertFalse(task.canDoRequest());
		RpcCall call = calls.get(0);
		Message response = Message.pingResponse(call.getTxid());
		setCallResponse(call, response);
		assertTrue(task.canDoRequest());
	}

	/**
	 * A task that cannot prepare itself has to end, not sit in RUNNING.
	 * <p>
	 * Iteration is driven only by call state changes, and a task that failed to prepare has sent no
	 * calls - so nothing will ever drive it again. TaskManager counts it against {@code concurrentTasks}
	 * from the moment it dequeues it and only releases that slot through the end handler, and callers
	 * wait on the same listener, so a task left running here costs a slot for the life of the node and
	 * strands whoever was waiting. This is the reason prepare() is handled apart from iteration, where
	 * surviving a failure is the right call.
	 * </p>
	 */
	@Test
	void testPrepareFailureEndsTheTask() {
		Variable<Boolean> ended = Variable.of(false);
		TestTask failing = new TestTask(context) {
			@Override
			protected void prepare() {
				throw new IllegalStateException("cannot prepare");
			}
		};
		// What TaskManager installs to reclaim the slot, and what callers wait on.
		failing.endHandler(t -> ended.set(true));

		failing.start();

		assertEquals(Task.State.CANCELED, failing.getState());
		assertTrue(failing.isEnd());
		assertTrue(ended.get(), "the end handler must fire, or the task's slot is never reclaimed");
	}

	/**
	 * An iteration that throws having sent nothing is in the same position as a failed prepare: no call
	 * exists to bring the task back, so it has to end rather than hold a slot for the life of the node.
	 */
	@Test
	void testIterationFailureWithNothingInFlightEndsTheTask() {
		Variable<Boolean> ended = Variable.of(false);
		TestTask failing = new TestTask(context) {
			@Override
			protected void iterate() {
				throw new IllegalStateException("cannot iterate");
			}
		};
		failing.endHandler(t -> ended.set(true));

		failing.start();

		assertEquals(0, failing.getInFlightCalls());
		assertEquals(Task.State.CANCELED, failing.getState());
		assertTrue(ended.get(), "the end handler must fire, or the task's slot is never reclaimed");
	}

	/**
	 * The counterpart, and the reason the check is on the in-flight set rather than on the failure: a
	 * task that got a call out before it threw is still reachable, because that call will respond or time
	 * out and drive the next iteration. Ending it there would abandon a lookup over one bad iteration.
	 */
	@Test
	void testIterationFailureWithACallInFlightLeavesTheTaskRunning() {
		TestTask failing = new TestTask(context) {
			@Override
			protected void iterate() {
				sendCall(NodeInfo.of(Id.random(), "192.168.1.8", 39001), Message.pingRequest());
				throw new IllegalStateException("cannot iterate");
			}
		};

		failing.start();

		assertEquals(1, failing.getInFlightCalls());
		assertEquals(Task.State.RUNNING, failing.getState());
	}

	/**
	 * A cancelled call has to leave the in-flight set like any other terminal state.
	 * <p>
	 * It is never going to be answered, and {@code isDone()} is {@code inFlight.isEmpty()}, so a call left
	 * behind means the task can never finish - it holds a manager slot and its caller's future for the
	 * life of the node. This was survivable only while the sole caller of {@code RpcCall.cancel()} ran
	 * after every task had already been cancelled itself, which is an ordering nothing states or enforces.
	 * </p>
	 */
	@Test
	void testACanceledCallLeavesTheInFlightSet() {
		task.start();

		List<RpcCall> calls = new ArrayList<>();
		NodeInfo node = NodeInfo.of(Id.random(), "192.168.1.8", 39001);
		task.sendCall(node, Message.pingRequest(), calls::add, null);
		assertEquals(1, task.getInFlightCalls());

		cancelCall(calls.get(0));

		assertEquals(0, task.getInFlightCalls(), "a canceled call must not hold the task open");
		assertTrue(task.canDoRequest(), "and its concurrency slot must come back");
	}

	/**
	 * A listener that throws out of {@code started()} leaves the task running having sent nothing, which
	 * is the same dead end as an iteration that throws before its first call.
	 */
	@Test
	void testAThrowingStartListenerEndsTheTask() {
		Variable<Boolean> ended = Variable.of(false);
		TestTask failing = new TestTask(context);
		failing.addListener(new TaskListener<>() {
			@Override
			public void started(TestTask task) {
				throw new IllegalStateException("cannot start");
			}

			@Override
			public void ended(TestTask task) {
			}
		});
		failing.endHandler(t -> ended.set(true));

		failing.start();

		assertEquals(0, failing.getInFlightCalls());
		assertEquals(Task.State.CANCELED, failing.getState());
		assertTrue(ended.get(), "the end handler must fire, or the task's slot is never reclaimed");
	}

	/**
	 * An unstarted task has no age, which is what its contract has always said.
	 * <p>
	 * Without the guard it answered with the time since the epoch - some 57 years - and every caller was
	 * shielded from that by a check of its own: {@code isOverdue()} by {@code isRunning()}, {@code
	 * toString()} by the start time being set. A contract that only holds because nobody exercises it is
	 * one deletion of a guard away from being false.
	 * </p>
	 */
	@Test
	void testAnUnstartedTaskHasNoAge() {
		assertEquals(Duration.ZERO, task.age());
	}

	/**
	 * And the reading that would have mattered: the bogus age was far past any deadline, so an unstarted
	 * task looked overdue to everything except the guard that happened to be in the way.
	 */
	@Test
	void testAnUnstartedTaskIsNotPastItsDeadline() {
		assertTrue(task.age().compareTo(task.deadline()) <= 0,
				"an unstarted task must not read as older than its own deadline");
		assertFalse(task.isOverdue());
	}

	@Test
	void testAStartedTaskAges() throws InterruptedException {
		task.start();
		Thread.sleep(5);

		assertTrue(task.age().toMillis() > 0);
	}

	/**
	 * The tie-break the ordering rests on, over the gap that used to invert it.
	 * <p>
	 * The old form read a difference above 2^31 as evidence the id counter had wrapped and returned "less
	 * than" for it, so for three tasks sharing a creation time and spread across that gap the comparator
	 * was not transitive: it ordered a below b and b below c while ordering c below a. Nothing sorted
	 * tasks, so nothing ever saw it.
	 * </p>
	 */
	@Test
	void testTheIdTieBreakIsTransitiveAcrossTheWholeIdRange() {
		long createTime = System.currentTimeMillis();
		Task<?> low = taskWith(createTime, 1L);
		Task<?> mid = taskWith(createTime, 1L + Integer.MAX_VALUE);
		Task<?> high = taskWith(createTime, 0xFFFFFFFFL);

		assertTrue(compare(low, mid) < 0);
		assertTrue(compare(mid, high) < 0);
		assertTrue(compare(low, high) < 0, "the comparator ordered the pair it had already ordered the other way");

		assertTrue(compare(mid, low) > 0);
		assertTrue(compare(high, low) > 0);
		assertEquals(0, compare(mid, mid));
	}

	@Test
	void testCreationTimeOutranksTheId() {
		Task<?> earlyWithHighId = taskWith(1_000L, 0xFFFFFFFFL);
		Task<?> lateWithLowId = taskWith(2_000L, 1L);

		assertTrue(compare(earlyWithHighId, lateWithLowId) < 0);
		assertTrue(compare(lateWithLowId, earlyWithHighId) > 0);
	}

	/**
	 * Tasks are identity objects: two of them are never the same task, whatever their fields say.
	 * <p>
	 * This is why {@code hashCode} is no longer overridden. The override was legal - equality is identity,
	 * so the contract held vacuously - but it made a {@code HashSet} of tasks iterate in creation order,
	 * which reads as a guarantee and is not one. {@code TaskManager} now asks for that order explicitly.
	 * </p>
	 */
	@Test
	void testTwoTasksAreNeverTheSameTask() {
		Task<?> a = taskWith(1_000L, 42L);
		Task<?> b = taskWith(1_000L, 42L);

		assertNotEquals(a, b);
		Set<Task<?>> set = new HashSet<>();
		set.add(a);
		set.add(b);
		assertEquals(2, set.size(), "two distinct tasks collapsed into one set entry");
	}

	/** Forces the two fields the comparator reads, which are otherwise assigned by the constructor. */
	private Task<?> taskWith(long createTime, long taskId) {
		TestTask t = new TestTask(context);
		set(t, "createTime", createTime);
		set(t, "taskId", taskId);
		return t;
	}

	private static void set(Task<?> task, String field, long value) {
		try {
			java.lang.reflect.Field f = Task.class.getDeclaredField(field);
			f.setAccessible(true);
			f.setLong(task, value);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("could not seed " + field, e);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static int compare(Task<?> a, Task<?> b) {
		return ((Comparable) a).compareTo(b);
	}
}
