/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

package io.bosonnetwork.kademlia.tasks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;

import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.rpc.RpcCall;

/**
 * Abstract base class for Kademlia tasks executed in a single-threaded Vert.x event loop.
 * This class provides a framework for managing asynchronous RPC-based tasks, such as node lookups,
 * value lookups, or peer refreshes in a Kademlia distributed hash table (DHT). Subclasses implement
 * specific task logic by overriding {@link #iterate()} and other protected methods. Tasks support
 * state transitions, concurrent RPC call management, listener notifications, and nested tasks.
 *
 * @param <S> the specific task type, enabling method chaining for fluent interfaces
 */
public abstract class Task<S extends Task<S>> implements Comparable<Task<S>> {
	private static final AtomicInteger nextTaskId = new AtomicInteger(1);
	private static final String NONAME = "";

	private static final EnumSet<State> UNSTARTED_STATES = EnumSet.of(State.INITIAL, State.QUEUED);
	private static final EnumSet<State> INCOMPLETE_STATES = EnumSet.of(
			State.INITIAL, State.QUEUED, State.RUNNING);

	private final KadContext context;

	private final long taskId;
	private String name;
	private boolean lowPriority;
	private State state;

	private Task<?> nested;

	private final Map<Long, RpcCall> inFlight;
	private TaskListener<S> listener;
	// Shortcut to the task manager for efficiency and to ensure the task manager is
	// notified first when the task ends
	private Consumer<Task<S>> endHandler;

	private final long createTime;
	private long startTime;
	private long endTime;

	/**
	 * Enumerates the possible states of a task.
	 */
	public enum State {
		INITIAL, QUEUED, RUNNING, CANCELED, COMPLETED
	}

	/**
	 * Constructs a new task with the given Kademlia context.
	 *
	 * @param context the Kademlia context, must not be null
	 */
	protected Task(KadContext context) {
		assert (context != null) : "Invalid context";
		this.context = context;

		// Use AtomicInteger for task ID generation; tasks are short-lived, so overflow is unlikely
		this.name = NONAME;
		this.taskId = Integer.toUnsignedLong(nextTaskId.getAndIncrement());
		this.state = State.INITIAL;
		// Initialize with small capacity for inFlight map to optimize memory
		this.inFlight = new HashMap<>(8);

		this.createTime = System.currentTimeMillis();
	}

	/**
	 * Returns the unique identifier of this task.
	 *
	 * @return the task ID
	 */
	public long getId() {
		return taskId;
	}

	/**
	 * Returns the Kademlia context associated with this task.
	 *
	 * @return the context
	 */
	protected KadContext getContext() {
		return context;
	}

	/**
	 * Sets the name of the task for logging and debugging purposes.
	 *
	 * @param name the task name, or null to use an empty string
	 * @return this task for method chaining
	 */
	@SuppressWarnings("unchecked")
	public S setName(String name) {
		this.name = name != null ? name : NONAME;
		return (S) this;
	}

	/**
	 * Marks the task as low priority, limiting the number of concurrent RPC requests.
	 *
	 * @return this task for method chaining
	 */
	@SuppressWarnings("unchecked")
	public S lowPriority() {
		this.lowPriority = true;
		return (S) this;
	}

	/**
	 * Returns the name of the task.
	 *
	 * @return the task name, or an empty string if not set
	 */
	public String getName() {
		return name;
	}

	/**
	 * Attempts to transition the task from an expected state to a new state.
	 * Logs a warning if the transition is invalid.
	 *
	 * @param expected the expected current state
	 * @param newState the new state to set
	 * @return true if the transition was successful, false otherwise
	 */
	@SuppressWarnings("SameParameterValue")
	protected boolean setState(State expected, State newState) {
		if (expected != state) {
			getLogger().warn("{}#{} invalid state transition: expected {}, but was {}",
					name, taskId, expected, state);
			return false;
		}

		if (isEnd()) {
			getLogger().warn("{}#{} invalid state transition: task already ended: {}", name, taskId, state);
			return false;
		}

		state = newState;
		return true;
	}

	/**
	 * Attempts to transition the task from one of the expected states to a new state.
	 *
	 * @param expected the set of expected current states
	 * @param newState the new state to set
	 * @return true if the transition was successful, false otherwise
	 */
	protected boolean setState(Set<State> expected, State newState) {
		assert (expected != null && !expected.isEmpty()) : "Invalid expected states";
		assert (newState != null) : "Invalid new state";

		if (!expected.contains(state)) {
			getLogger().warn("{}#{} invalid state transition: expected one of {}, but was {}",
					name, taskId, expected, state);
			return false;
		}

		state = newState;
		return true;
	}

	/**
	 * Returns the current state of the task.
	 *
	 * @return the task state
	 */
	public State getState() {
		return state;
	}

	/**
	 * Sets a nested task to be executed as part of this task's lifecycle.
	 *
	 * @param nested the nested task
	 * @return this task for method chaining
	 */
	@SuppressWarnings("unchecked")
	public S setNestedTask(Task<?> nested) {
		this.nested = nested;
		return (S) this;
	}

	/**
	 * Returns the nested task, if any.
	 *
	 * @return the nested task, or null if none
	 */
	public Task<?> getNestedTask() {
		return nested;
	}

	/**
	 * Returns the number of RPC calls currently in-flight for this task.
	 *
	 * @return the number of in-flight calls
	 */
	public int getInFlightCalls() {
		return inFlight.size();
	}

	/**
	 * Sets the end handler to be called when the task reaches a terminal state.
	 * Used by the TaskManager to track task completion.
	 *
	 * @param endHandler the handler to call on task completion or cancellation
	 */
	@SuppressWarnings("unchecked")
	void endHandler(Consumer<Task<S>> endHandler) {
		this.endHandler = endHandler;

		if (endHandler != null && isEnd())
			endHandler.accept(this);
	}

	/**
	 * Adds a listener to receive task lifecycle events (e.g., started, completed, canceled).
	 *
	 * @param listener the listener to add
	 * @return this task for method chaining
	 */
	@SuppressWarnings("unchecked")
	public S addListener(TaskListener<S> listener) {
		assert(listener != null) : "Invalid listener";

		if (this.listener == null) {
			this.listener = listener;
		} else {
			if (this.listener instanceof ListenerArray<S> listeners) {
				listeners.add(listener);
			} else {
				ListenerArray<S> listeners = new ListenerArray<>();
				listeners.add(this.listener);
				listeners.add(listener);
				this.listener = listeners;
			}
		}

		// listener is added after the task already terminated, thus it won't get the
		// event, trigger it manually
		if (isCanceled()) {
			listener.canceled((S) this);
			listener.ended((S) this);
		} else if (isComplete()) {
			listener.completed((S) this);
			listener.ended((S) this);
		}

		return (S) this;
	}

	/**
	 * Starts the task, transitioning it to the RUNNING state and initiating iteration.
	 * If an error occurs during iteration, the task continues to allow subsequent iterations.
	 */
	@SuppressWarnings("unchecked")
	public void start() {
		if (setState(UNSTARTED_STATES, State.RUNNING)) {
			getLogger().debug("{}#{} starting...", name, taskId);
			startTime = System.currentTimeMillis();

			prepare();
			if (listener != null)
				listener.started((S) this);

			try {
				tryIterate();
			} catch (Exception e) {
				// Log error but do not cancel task to allow future iterations
				getLogger().error("{}#{} start failed", name, taskId, e);
			}
		}
	}

	/**
	 * Attempts to perform one iteration of the task, completing it if done.
	 */
	private void tryIterate() {
		getLogger().debug("{}#{} iterate...", name, taskId);
		getLogger().trace(getStatus());

		if (isDone()) {
			complete();
			return;
		}

		if (canDoRequest() && !isEnd()) {
			iterate();

			// Check again in case todo-queue has been drained by update()
			if (isDone())
				complete();
		}
	}

	/**
	 * Cancels the task, transitioning it to the CANCELED state and canceling any nested tasks.
	 */
	@SuppressWarnings("unchecked")
	public void cancel() {
		if (setState(INCOMPLETE_STATES, State.CANCELED)) {
			endTime = System.currentTimeMillis();

			if (nested != null)
				nested.cancel();

			getLogger().debug("{}#{} canceled", name, taskId);

			if (endHandler != null)
				endHandler.accept(this);

			if (listener != null) {
				listener.canceled((S) this);
				listener.ended((S) this);
			}
		}
	}

	/**
	 * Marks the task as completed, transitioning it to the COMPLETED state.
	 */
	@SuppressWarnings("unchecked")
	protected void complete() {
		if (setState(INCOMPLETE_STATES, State.COMPLETED)) {
			endTime = System.currentTimeMillis();
			getLogger().debug("{}#{} completed", name, taskId);

			if (endHandler != null)
				endHandler.accept(this);

			if (listener != null) {
				listener.completed((S) this);
				listener.ended((S) this);
			}
		}
	}

	/**
	 * Checks if the task is in an unstarted state (INITIAL or QUEUED).
	 *
	 * @return true if unstarted, false otherwise
	 */
	public boolean isUnstarted() {
		return state == State.INITIAL || state == State.QUEUED;
	}

	/**
	 * Checks if the task is in the RUNNING state.
	 *
	 * @return true if running, false otherwise
	 */
	public boolean isRunning() {
		return state == State.RUNNING;
	}

	/**
	 * Checks if the task is in the COMPLETED state.
	 *
	 * @return true if completed, false otherwise
	 */
	public boolean isComplete() {
		return state == State.COMPLETED;
	}

	/**
	 * Checks if the task is in the CANCELED state.
	 *
	 * @return true if canceled, false otherwise
	 */
	public boolean isCanceled() {
		return state == State.CANCELED;
	}

	/**
	 * Checks if the task is in a terminal state (COMPLETED or CANCELED).
	 *
	 * @return true if ended, false otherwise
	 */
	public boolean isEnd() {
		return state == State.COMPLETED || state == State.CANCELED;
	}

	/**
	 * Returns the start time of the task.
	 *
	 * @return the start time in milliseconds, or 0 if not started
	 */
	public long getStartTime() {
		return startTime;
	}

	/**
	 * Returns the end time of the task.
	 *
	 * @return the end time in milliseconds, or 0 if not ended
	 */
	public long getEndTime() {
		return endTime;
	}

	/**
	 * Returns the duration between the task's start and end times.
	 *
	 * @return the lead time, or Duration.ZERO if not started or ended
	 */
	public Duration getLeadTime() {
		if (startTime == 0 || endTime == 0)
			return Duration.ZERO;

		return Duration.ofMillis(endTime - startTime);
	}

	/**
	 * Returns the duration since the task started.
	 *
	 * @return the age, or Duration.ZERO if not started
	 */
	public Duration age() {
		return Duration.ofMillis(System.currentTimeMillis() - startTime);
	}

	/**
	 * Checks if the task can issue additional RPC requests based on concurrency limits.
	 *
	 * @return true if requests can be sent, false otherwise
	 */
	protected boolean canDoRequest() {
		return isRunning() && (inFlight.size() < (lowPriority ? TaskManager.MAX_CONCURRENT_TASK_REQUESTS_LOW_PRIORITY
				: TaskManager.MAX_CONCURRENT_TASK_REQUESTS));
	}

	// Internal listener for RPC call state changes, updating the task's state and triggering iteration.
	private void onCallStateChange(RpcCall call, RpcCall.State previous, RpcCall.State state) {
		getLogger().trace("{}#{} call to {} state changed: {} -> {}", name, taskId, call.getTargetId(), previous, state);

		// Ignore if the task is already in a terminal state
		if (isEnd()) {
			getLogger().debug("{}#{} call to {} state changed ignored due to the task is terminated",
					name, taskId, call.getTargetId());
			return;
		}

		switch (state) {
			case SENT:
				callSent(call);
				break;
			case RESPONDED:
				inFlight.remove(call.getTxid());
				callResponded(call);
				break;
			case ERROR:
				inFlight.remove(call.getTxid());
				callError(call);
				break;
			case TIMEOUT:
				inFlight.remove(call.getTxid());
				callTimeout(call);
				break;
		}

		if (state.ordinal() >= RpcCall.State.STALLED.ordinal())
			tryIterate();
	}

	/**
	 * Sends an RPC call to a specified node.
	 *
	 * @param node    the target node
	 * @param request the RPC request message
	 * @return true if the call was sent, false if concurrency limits prevent it
	 */
	protected boolean sendCall(NodeInfo node, Message<?> request) {
		return sendCall(node, request, null);
	}

	/**
	 * Sends an RPC call to a specified node with an optional pre-send callback.
	 *
	 * @param node       the target node
	 * @param request    the RPC request message
	 * @param beforeSend optional callback to execute before sending the call
	 * @return true if the call was sent, false if concurrency limits prevent it
	 */
	protected boolean sendCall(NodeInfo node, Message<?> request, Consumer<RpcCall> beforeSend) {
		if (!canDoRequest())
			return false;

		RpcCall call = new RpcCall(node, request)
				.addListener(this::onCallStateChange);

		if (beforeSend != null)
			beforeSend.accept(call);

		inFlight.put(call.getTxid(), call);

		getLogger().trace("{}#{} sending call to {}...", name, taskId, node);
		sendCall(call);
		return true;
	}

	/**
	 * Enqueue the send call action to the event loop of current vertx context.
	 *
	 * @param call the RPC request message
	 */
	// for testing purposes only
	protected void sendCall(RpcCall call) {
		// context.runOnContext(unused -> context.getDHT().getRpcServer().sendCall(call));
		context.getDHT().getRpcServer().sendCall(call);
	}

	/**
	 * Prepares the task before starting. Subclasses may override to perform initialization.
	 */
	protected void prepare() {
	}

	/**
	 * Called when an RPC call is sent. Subclasses may override to handle this event.
	 *
	 * @param call the RPC call
	 */
	@SuppressWarnings("unused")
	protected void callSent(RpcCall call) {
	}

	/**
	 * Called when an RPC call receives a response. Subclasses may override to handle this event.
	 *
	 * @param call the RPC call
	 */
	@SuppressWarnings("unused")
	protected void callResponded(RpcCall call) {
	}

	/**
	 * Called when an RPC call encounters an error. Subclasses may override to handle this event.
	 *
	 * @param call the RPC call
	 */
	@SuppressWarnings("unused")
	protected void callError(RpcCall call) {
	}

	/**
	 * Called when an RPC call times out. Subclasses may override to handle this event.
	 *
	 * @param call the RPC call
	 */
	@SuppressWarnings("unused")
	protected void callTimeout(RpcCall call) {
	}

	/**
	 * Performs one iteration of the task's logic, such as issuing RPC calls or processing responses.
	 * Subclasses must implement this method to define task-specific behavior.
	 */
	protected abstract void iterate();

	/**
	 * Checks if the task is done (i.e., no further iterations or RPC calls are needed).
	 *
	 * @return true if the task is done, false otherwise
	 */
	protected boolean isDone() {
		return inFlight.isEmpty();
	}

	/**
	 * Returns a detailed string representation of the task’s state.
	 *
	 * @return the status string
	 */
	protected abstract String getStatus();

	/**
	 * Returns the logger for this task. Subclasses must implement this to provide a logger.
	 *
	 * @return the logger instance
	 */
	protected abstract Logger getLogger();

	/**
	 * Compares tasks for ordering, first by creation time and then by task ID with wraparound handling.
	 *
	 * @param t the task to compare with
	 * @return a negative integer, zero, or a positive integer as this task is less than, equal to,
	 *         or greater than the specified task
	 */
	@Override
	public int compareTo(Task t) {
		// Compare createTime first (earlier tasks come first)
		if (this.createTime != t.createTime) {
			return Long.compare(this.createTime, t.createTime);
		}

		// If createTime is equal, compare taskId with wraparound logic
		long diff = this.taskId - t.taskId;
		return (diff > Integer.MAX_VALUE) ? -1 :
				(diff < Integer.MIN_VALUE) ? 1 : Long.compareUnsigned(this.taskId, t.taskId);
	}

	/**
	 * Returns the hash code of the task based on its ID.
	 *
	 * @return the hash code
	 */
	@Override
	public int hashCode() {
		return (int) (taskId & 0xFFFFFFFFL);
	}

	/**
	 * Returns a string representation of the task for debugging.
	 *
	 * @return the string representation
	 */
	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder(100);

		repr.append(getName()).append('#').append(getId());

		if (this instanceof LookupTask<?, ?> t)
			repr.append(", target: ").append(t.getTarget());

		repr.append(", network: ").append(context.getNetwork());

		repr.append(", state: ").append(state);
		if (startTime != 0) {
			if (endTime == 0)
				repr.append(", age: ").append(age());
			else if (endTime > 0)
				repr.append(", leadTime: ").append(Duration.ofMillis(endTime - startTime));
		}

		return repr.toString();
	}

	/**
	 * Internal class to manage multiple task listeners.
	 */
	private static class ListenerArray<S extends Task<S>> extends ArrayList<TaskListener<S>> implements TaskListener<S> {
		private static final long serialVersionUID = 954787434033254562L;

		public ListenerArray() {
			super(4);
		}

		@Override
		public void started(S task) {
			for (TaskListener<S> l : this)
				l.started(task);
		}

		@Override
		public void completed(S task) {
			for (TaskListener<S> l : this)
				l.completed(task);
		}

		@Override
		public void canceled(S task) {
			for (TaskListener<S> l : this)
				l.canceled(task);
		}

		@Override
		public void ended(S task) {
			for (TaskListener<S> l : this)
				l.ended(task);
		}
	}
}