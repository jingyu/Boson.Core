package io.bosonnetwork.kademlia.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.Network;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.rpc.RpcCall;
import io.bosonnetwork.utils.Variable;

public class TaskTests {
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

	@BeforeEach
	void setUp() {
		context = new KadContext(null, null, new CryptoIdentity(), Network.IPv4, null);
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

	private void setCallResponse(RpcCall call, Message<?> response) {
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
		for (int i = 0; i < TaskManager.MAX_CONCURRENT_TASK_REQUESTS; i++) {
			NodeInfo node = new NodeInfo(Id.random(), "192.168.1.8", Random.random().nextInt(1024, 65536));
			Message<?> message = Message.pingRequest();
			task.sendCall(node, message, calls::add);
			assertEquals(i + 1, task.getInFlightCalls());
		}
		assertFalse(task.canDoRequest());
		RpcCall call = calls.get(0);
		Message<?> response = Message.pingResponse(call.getTxid());
		setCallResponse(call, response);
		assertTrue(task.canDoRequest());
	}

	@Test
	void testLowPriorityRpcLimits() {
		task.lowPriority();
		task.start();
		assertTrue(task.canDoRequest());

		List<RpcCall> calls = new ArrayList<>();
		for (int i = 0; i < TaskManager.MAX_CONCURRENT_TASK_REQUESTS_LOW_PRIORITY; i++) {
			NodeInfo node = new NodeInfo(Id.random(), "192.168.1.8", Random.random().nextInt(1024, 65536));
			Message<?> message = Message.pingRequest();
			task.sendCall(node, message, calls::add);
			assertEquals(i + 1, task.getInFlightCalls());
		}
		assertFalse(task.canDoRequest());
		RpcCall call = calls.get(0);
		Message<?> response = Message.pingResponse(call.getTxid());
		setCallResponse(call, response);
		assertTrue(task.canDoRequest());
	}
}