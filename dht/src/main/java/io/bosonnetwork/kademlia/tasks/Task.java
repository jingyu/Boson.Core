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

import java.net.StandardProtocolFamily;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import io.vertx.core.Future;
import org.slf4j.Logger;

import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.rpc.RpcCall;
import io.bosonnetwork.kademlia.rpc.RpcServer;

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
	private static final AtomicInteger nextTaskId = new AtomicInteger(0);
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
	/** How many calls this task has put on the wire, for telling a productive iteration from an empty one. */
	private long callsSent;
	/** True while an iteration is running, so a call that fails on this stack cannot start another. */
	private boolean iterating;
	/** Set when an iteration was asked for while one was running, and is owed once it finishes. */
	private boolean iterationPending;
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
		this.taskId = Integer.toUnsignedLong(nextTaskId.incrementAndGet());
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
	 * Returns how many calls this task has sent since it started.
	 * <p>
	 * Counts every call handed to the transport, including one whose send then failed, and never
	 * decreases - so a subclass can compare it across a region to ask whether anything was attempted
	 * there. That is a different question from {@link #getInFlightCalls()}, which answers how many are
	 * outstanding right now.
	 * </p>
	 *
	 * @return the number of calls sent
	 */
	protected long getCallsSent() {
		return callsSent;
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
	 * Starts the task, transitioning it to the RUNNING state and running the first iteration.
	 * <p>
	 * Neither failure here leaves the task running with nothing to drive it: a task that fails to prepare
	 * is cancelled, and an iteration that throws ends the task when no call went out to bring it back.
	 * </p>
	 */
	@SuppressWarnings("unchecked")
	public void start() {
		if (setState(UNSTARTED_STATES, State.RUNNING)) {
			getLogger().debug("{}#{} starting...", name, taskId);
			startTime = System.currentTimeMillis();

			try {
				prepare();
			} catch (Exception e) {
				// Terminal, unlike an iteration failure, so it gets its own handling. Nothing has been
				// queued and no call has been sent, and iteration is driven only by call state changes -
				// so there is no future iteration for the task to be kept alive for. Left in RUNNING it
				// would hold one of the manager's slots for the life of the node and never notify its
				// listener, and callers wait on this task through that listener. Same reasoning as the
				// failed state transition in TaskManager.add.
				getLogger().error("{}#{} prepare failed", name, taskId, e);
				cancel();
				return;
			}

			try {
				if (listener != null)
					listener.started((S) this);

				tryIterate();
			} catch (Exception e) {
				// Not the iteration failing: tryIterate handles that itself, ending the task when nothing
				// is left to drive it. What reaches here is the listener callback or the completion checks
				// around it, and a task holding calls in flight is left running, because those still bring
				// it back.
				//
				// A listener.started() that throws leaves the task RUNNING having sent nothing, so the
				// same rule applies here as inside the iteration: with nothing in flight, nothing can bring
				// this task back.
				getLogger().error("{}#{} start failed", name, taskId, e);
				if (inFlight.isEmpty() && !isDone())
					cancel();
			}
		}
	}

	/**
	 * Drives the task, running one iteration at a time and never one inside another.
	 * <p>
	 * Iteration is what the whole task is: it decides whether the task is finished and, if not, sends the
	 * next round of requests. Every path that can advance a task comes through here.
	 * </p>
	 */
	private void tryIterate() {
		// Reached re-entrantly on an ordinary path, not only under overload. A call can fail on the stack
		// that sent it - our own outbound throttle refuses a call rather than parking it past the RPC
		// timeout, and two admission limits do the same - which fails the call, which delivers a state
		// change, which arrives here while the iteration that sent it is still inside its send loop.
		//
		// Running an iteration there is not a smaller version of the same thing. The nested run can decide
		// the task is done and complete it while the outer loop is still sending, so calls go out after
		// the listener has already been told the task is over and the second completion is rejected as an
		// invalid transition; and since each nested send can be refused the same way, the nesting is
		// bounded by nothing but the queue being drained.
		//
		// The request is deferred rather than dropped, because the state change that brought us here may
		// be the last event this task will ever get - dropping it is the stall that the task deadline
		// exists to catch, and there is no reason to need catching.
		if (iterating) {
			iterationPending = true;
			return;
		}

		iterating = true;
		try {
			do {
				iterationPending = false;
				iterateOnce();
			} while (iterationPending && !isEnd());
		} finally {
			iterating = false;
		}
	}

	/**
	 * Runs one iteration: completes the task if it is done, otherwise sends what it can and checks again.
	 * <p>
	 * A failing iteration is contained here rather than allowed to escape. It reaches this class through
	 * {@code RpcCall.updateState}, so a throw would unwind the RPC receive path and skip the bookkeeping
	 * that follows a response - the routing-table update, the RTT sample, the metrics - for a packet that
	 * has nothing to do with this task.
	 * </p>
	 */
	private void iterateOnce() {
		getLogger().debug("{}#{} iterate...", name, taskId);
		getLogger().trace(getStatus());

		if (isDone()) {
			complete();
			return;
		}

		if (canDoRequest() && !isEnd()) {
			try {
				iterate();
			} catch (Exception e) {
				getLogger().error("{}#{} iterate failed", name, taskId, e);

				// The backstop, and it should stay unreachable. A send that fails for one queued node does
				// not come through here at all - sendCall reports it to that node's own handler - so what
				// arrives is a failure of the iteration itself, which consumed nothing and would repeat if
				// iterated again. Iteration is driven only by call state changes, so with nothing in
				// flight there is no event left that can ever bring this task back. Left in RUNNING it
				// would hold one of the manager's slots for the life of the node and never notify its
				// listener, which is how a caller learns the task is over. Same reasoning as the failed
				// prepare() in start().
				//
				// Where a call did go out before the throw the task is still reachable, so it keeps
				// running: that call responds or times out, and the iteration is attempted again.
				if (inFlight.isEmpty() && !isDone()) {
					cancel();
					return;
				}
			}

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

			// A cancelled task is not going to read any of its answers, and every call still outstanding
			// holds this::onCallStateChange - so leaving them to time out keeps the task, its candidate
			// set and everything they reach alive for the length of a call timeout after the task is gone.
			// Cancelling them also releases the slot each one holds in the RPC server.
			//
			// Snapshot first, and clear before cancelling: cancelling notifies listeners, and one of them
			// is this task's own, which reaches back into this map. It happens to return early here
			// because the state above is already terminal, but walking a map that a callback may write to
			// is not something to leave resting on that.
			if (!inFlight.isEmpty()) {
				List<RpcCall> outstanding = new ArrayList<>(inFlight.values());
				inFlight.clear();
				for (RpcCall call : outstanding)
					call.cancel();
			}

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
	 * How long this task may run before the manager gives up on it.
	 * <p>
	 * <b>An outer bound, not a service level.</b> Reaching it means something is wrong with this node -
	 * a task that cannot be driven any more, holding one of the manager's slots and a caller's future
	 * forever - so it is sized so that no healthy task can reach it, and a task that does is cancelled
	 * and logged rather than quietly retried. Sizing it as a quality-of-service timer instead would
	 * truncate slow but progressing work on a bad network, which is worse than the stall it prevents.
	 * </p>
	 * <p>
	 * The default is the serial worst case for a task that drains a queue of nodes: one bucket's worth
	 * of contacts and replacements, each taking the longest an RPC may take before it is declared dead.
	 * Real tasks run {@code alpha} calls at a time and time out well below the maximum, so this leaves a
	 * wide margin on purpose. {@link LookupTask} overrides it, being bounded by its iteration budget
	 * rather than by a queue.
	 * </p>
	 *
	 * @return the maximum running time for this task.
	 */
	protected Duration deadline() {
		return Duration.ofMillis((long) (getContext().getK() + getContext().getReplacements())
				* RpcServer.RPC_CALL_TIMEOUT_MAX);
	}

	/**
	 * Whether this task has been running longer than its deadline allows.
	 *
	 * @return true if the task is running and overdue, false otherwise.
	 */
	boolean isOverdue() {
		return isRunning() && age().compareTo(deadline()) > 0;
	}

	/**
	 * Checks if the task can issue additional RPC requests based on concurrency limits.
	 *
	 * @return true if requests can be sent, false otherwise
	 */
	protected boolean canDoRequest() {
		int limit = lowPriority ? getContext().getLowPriorityAlpha() : getContext().getAlpha();
		return isRunning() && (inFlight.size() < limit);
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
			case CANCELED:
				// A cancelled call is never going to be answered, so it has to leave the in-flight set
				// like any other terminal state. Without this the set never empties, isDone() is
				// inFlight.isEmpty(), and the task cannot finish - it was harmless only while the one
				// caller of RpcCall.cancel() ran after every task had already been cancelled itself.
				inFlight.remove(call.getTxid());
				callCanceled(call);
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
	 * @return true if the call was accepted for sending, false if concurrency limits prevent it
	 */
	protected boolean sendCall(NodeInfo node, Message request) {
		return sendCall(node, request, null, null);
	}

	/**
	 * Sends an RPC call to a specified node, with optional callbacks around the send.
	 * <p>
	 * <b>Nothing the network can do makes this throw.</b> A send that fails is reported through
	 * {@code sendFailed} instead, which is what lets a queue-draining {@code iterate()} treat one
	 * unreachable node as one lost node rather than as a failed iteration. The two statements that could
	 * still raise - narrowing the address and building the call - are guarded rather than caught:
	 * {@code narrowDown} is reached only when the node is known to hold an address of this family, and
	 * the constructor rejects only null arguments, which is a programming error and belongs at the
	 * backstop in {@link #tryIterate()}.
	 * </p>
	 * <p>
	 * <b>{@code beforeSend} owns the caller's bookkeeping</b> - taking the node off a todo queue, marking
	 * a candidate as sent - and runs before the send is attempted, so a failed send can never leave the
	 * node at the head of the queue for the next iteration to pick again. A handler that throws is logged
	 * and the send proceeds, which costs at most a duplicate request; it cannot cost progress.
	 * </p>
	 * <p>
	 * <b>{@code sendFailed} reports, it does not repair.</b> It may run synchronously, before this method
	 * returns - the DHT fails a call immediately when it is not running - so it must not re-enter the
	 * task. It may also run <em>in addition to</em> {@link #callError(RpcCall)}: a send that reaches the
	 * socket and fails there also fails the call, and both report the same node. First report wins where
	 * a subclass records outcomes.
	 * </p>
	 * <p>
	 * One thing the failure path deliberately does not do is drive the task. It removes the call from the
	 * in-flight set and returns, leaving the surrounding {@code iterate()} loop to carry on to the next
	 * node and {@link #tryIterate()} to run the completion check once the loop ends. That holds because
	 * the DHT's own refusal is synchronous; a failure delivered later is answered by the call's state
	 * change, which does drive the task.
	 * </p>
	 *
	 * @param node       the target node
	 * @param request    the RPC request message
	 * @param beforeSend optional callback run before the send is attempted, on the calling thread
	 * @param sendFailed optional callback run when the call could not be sent, possibly before this
	 *                   method returns
	 * @return true if the call was accepted for sending, false if concurrency limits prevent it
	 */
	protected boolean sendCall(NodeInfo node, Message request,
							   Consumer<RpcCall> beforeSend, BiConsumer<RpcCall, Throwable> sendFailed) {
		if (!canDoRequest())
			return false;

		// Ensure the target node only use a single address compatible with current network family.
		// Narrowing is asked for only when the family is present, so it cannot fail here: hasMultiAddresses
		// alone was an argument that it cannot, and this is the same statement the method itself tests. A
		// node holding addresses but not this one is passed through and fails at the socket instead, which
		// is a per-node failure and already has a channel.
		StandardProtocolFamily family = getContext().getNetwork().protocolFamily();
		final NodeInfo target = node.hasMultiAddresses() && node.hasAddress(family) ? node.narrowDown(family) : node;

		RpcCall call = new RpcCall(target, request)
				.addListener(this::onCallStateChange);

		if (beforeSend != null) {
			try {
				beforeSend.accept(call);
			} catch (Exception ie) {
				getLogger().error("{}#{} invoke before send handler failed", name, taskId, ie);
			}
		}

		inFlight.put(call.getTxid(), call);
		callsSent++;

		getLogger().trace("{}#{} sending {} call to {}...", name, taskId, call.getRequest().getMethod(), target);
		try {
			sendCall(call).onFailure(e -> sendFailed(call, target, e, sendFailed));
		} catch (Exception e) {
			// The transport hook is overridable, so a throw is possible where a failed future is expected.
			// Same outcome either way, which is why both paths land in one place.
			sendFailed(call, target, e, sendFailed);
		}
		return true;
	}

	/**
	 * Retires a call that never left this node, and tells the caller which node it lost.
	 *
	 * @param call    the call that could not be sent
	 * @param target  the node it was addressed to, for the log
	 * @param cause   why the send failed
	 * @param handler the caller's handler, may be null
	 */
	private void sendFailed(RpcCall call, NodeInfo target, Throwable cause,
							BiConsumer<RpcCall, Throwable> handler) {
		getLogger().debug("{}#{} send {} call to {} failed", name, taskId,
				call.getRequest().getMethod(), target, cause);
		inFlight.remove(call.getTxid(), call);

		if (handler == null)
			return;

		try {
			handler.accept(call, cause);
		} catch (Exception ie) {
			getLogger().error("{}#{} invoke send failed handler failed", name, taskId, ie);
		}
	}

	/**
	 * Hands the call to the DHT to be sent.
	 * <p>
	 * Overridable so a test can observe or divert what a task sends without a socket; production code
	 * has no reason to.
	 * </p>
	 * <p>
	 * Nothing is enqueued here. {@code DHT.sendCall} drops the call if the DHT has been undeployed and
	 * moves it onto the DHT's context if this is not already running there, so a task neither has to
	 * check whether the transport is still alive nor which thread it is on.
	 * </p>
	 *
	 * @param call the RPC request message
	 */
	protected Future<RpcCall> sendCall(RpcCall call) {
		return context.getDHT().sendCall(call);
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
	 * Called when an RPC call was cancelled before it could be answered.
	 * <p>
	 * Distinct from {@link #callTimeout(RpcCall)} rather than folded into it, because the two mean
	 * opposite things about the node: a timeout is evidence the node did not answer, and a cancellation is
	 * evidence about us. Treating one as the other would have a shutting-down RPC server charge every
	 * outstanding node with a failure it never earned.
	 * </p>
	 *
	 * @param call the RPC call
	 */
	@SuppressWarnings("unused")
	protected void callCanceled(RpcCall call) {
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
	 * Returns a detailed string representation of the task's state.
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
