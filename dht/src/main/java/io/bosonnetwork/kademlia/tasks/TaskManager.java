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

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.rpc.RpcServer;

/**
 * A class for managing Kademlia tasks, handling queuing, execution, removal, and cancellation.
 * Enforces limits on active tasks and concurrent RPC requests to prevent overload in a single-threaded
 * Vert.x event loop. Integrated with {@link KadContext} for task scheduling. Designed for single-threaded
 * use; not thread-safe.
 */
public class TaskManager {
	private final KadContext context;
	private final int maxActiveTasks;
	private final Deque<Task<?>> queuedTasks;
	private final Set<Task<?>> runningTasks;
	private boolean canceling;
	private final long deadlineTimer;

	private static final Logger log = LoggerFactory.getLogger(TaskManager.class);

	/**
	 * Constructs a new TaskManager with the given context and active-task ceiling.
	 * <p>
	 * Note that alpha - how many RPCs a single task keeps in flight - is not a parameter here: a
	 * {@link Task} holds no reference to its manager, so it reads alpha from the shared
	 * {@link KadContext} instead. Keeping it in one place avoids the two copies disagreeing.
	 * </p>
	 *
	 * @param context        the Kademlia context.
	 */
	public TaskManager(KadContext context) {
		this.context = context;
		this.maxActiveTasks = context.getConcurrentTasks();

		queuedTasks = new LinkedList<>();
		runningTasks = new HashSet<>();

		// One sweep for every task rather than a timer each: the running set is bounded by
		// concurrentTasks, so the scan is cheaper than the timers it replaces, and on an idle node it
		// walks an empty set. The period is one RPC timeout, which bounds how late a stalled task is
		// noticed - negligible against deadlines measured in whole task lifetimes.
		this.deadlineTimer = context.setPeriodic(RpcServer.RPC_CALL_TIMEOUT_MAX, unused -> checkDeadlines());
	}

	/**
	 * Cancels every running task that has outlived its deadline.
	 * <p>
	 * The safety net under the whole layer: iteration is driven only by RPC call state changes, so a task
	 * that stops receiving them stops forever, holding one of the {@code concurrentTasks} slots and
	 * leaving whoever waits on its listener waiting for the life of the node. Individual causes get fixed
	 * as they are found; this bounds the ones that have not been.
	 * </p>
	 * <p>
	 * Logged at WARN with the task's own status, because reaching a deadline is a defect rather than a
	 * slow network - see {@link Task#deadline()} for why it is sized so that no healthy task can.
	 * </p>
	 */
	void checkDeadlines() {
		if (canceling || runningTasks.isEmpty())
			return;

		// Collected before cancelling: cancel() reaches the end handler, which removes from this set.
		List<Task<?>> overdue = null;
		for (Task<?> task : runningTasks) {
			if (task.isOverdue()) {
				if (overdue == null)
					overdue = new ArrayList<>();
				overdue.add(task);
			}
		}

		if (overdue == null)
			return;

		for (Task<?> task : overdue) {
			log.warn("Task exceeded its deadline of {} and is being canceled: {}\n{}",
					task.deadline(), task, task.getStatus());
			task.cancel();
		}
	}

	public int getMaxActiveTasks() {
		return maxActiveTasks;
	}

	/**
	 * Adds a task to the manager, queuing it if not running and starting it when ready.
	 * <p>
	 * This method must be invoked from the Vert.x event loop associated with {@link KadContext#getVertxContext()}.
	 * </p>
	 *
	 * @param task  the task to add
	 * @param prior true to add to the front of the queue (priority), false to the end
	 * @throws IllegalStateException if the manager is currently canceling tasks
	 */
	public void add(Task<?> task, boolean prior) {
		assert (task != null) : "Invalid task";
		assert (!task.isEnd()) : "Task is end";

		if (canceling)
			throw new IllegalStateException("TaskManager is canceling");

		// Remove terminated task and dequeue queued
		task.endHandler(t -> {
			remove(t);
			dequeue();
		});

		if (task.getState() == Task.State.RUNNING) {
			log.trace("Add running task directly: {}", task);
			runningTasks.add(task);
			return;
		}

		if (!task.setState(Task.State.INITIAL, Task.State.QUEUED)) {
			log.error("!!!INTERNAL ERROR: task is not in INITIAL state: {}", task);
			task.endHandler(null);
			// Cancel rather than drop silently. Callers wait on this task through a listener, so a task
			// that leaves here without ever reaching a terminal state leaves them waiting forever - and
			// the state they are guarding is often a latch, so what looks like one lost task is really a
			// mechanism disabled for good. cancel() is a no-op if the task already ended, in which case
			// the listeners have fired already.
			task.cancel();
			return;
		}

		log.trace("Add task to queue: {}", task);
		if (prior)
			queuedTasks.addFirst(task);
		else
			queuedTasks.addLast(task);

		context.runOnContext(v -> dequeue());
	}

	/**
	 * Adds a task to the manager without a priority.
	 * <p>
	 * This method must be invoked from the Vert.x event loop associated with {@link KadContext#getVertxContext()}.
	 * </p>
	 *
	 * @param task the task to add
	 * @throws IllegalStateException if the manager is currently canceling tasks
	 */
	public void add(Task<?> task) {
		add(task, false);
	}

	/**
	 * Removes a task from the manager.
	 * <p>
	 * This method must be invoked from the Vert.x event loop associated with {@link KadContext#getVertxContext()}.
	 * </p>
	 *
	 * @param task the task to remove
	 * @return true if removed, false otherwise
	 */
	public boolean remove(Task<?> task) {
		log.trace("Remove task: {}", task);
		if (queuedTasks.remove(task)) {
			log.debug("Removed queued task: {}", task);
			return true;
		}
		if (runningTasks.remove(task)) {
			log.debug("Removed running task: {}", task);
			return true;
		}
		return false;
	}

	/**
	 * Dequeues and starts tasks when the manager is ready.
	 */
	protected void dequeue() {
		log.trace("Dequeue: running={}, queued={}", runningTasks.size(), queuedTasks.size());
		while (isReady()) {
			Task<?> task = queuedTasks.pollFirst();
			if (task == null) {
				log.debug("Queue drained");
				break;
			}

			if (task.isEnd())
				continue;

			log.debug("Start task: {}", task);
			runningTasks.add(task);
			context.runOnContext(task::start);
		}
	}

	/**
	 * Returns the number of running tasks.
	 *
	 * @return the number of running tasks
	 */
	public int getRunningTasks() {
		return runningTasks.size();
	}

	/**
	 * Returns the number of queued tasks.
	 *
	 * @return the number of queued tasks
	 */
	public int getQueuedTasks() {
		return queuedTasks.size();
	}

	/**
	 * Checks if the manager is ready to start more tasks.
	 *
	 * @return true if ready, false otherwise
	 */
	public boolean isReady() {
		return !canceling && (runningTasks.size() < maxActiveTasks);
	}

	/**
	 * Cancels all tasks and clears the manager.
	 * <p>
	 * This method must be invoked from the Vert.x event loop associated with {@link KadContext#getVertxContext()}.
	 * </p>
	 */
	public void cancelAll() {
		canceling = true;
		context.cancelTimer(deadlineTimer);

		log.info("Canceling all tasks: running={}, queued={}", runningTasks.size(), queuedTasks.size());

		// Emptied before anything is cancelled, rather than walked while cancelling. Clearing the end
		// handler of the task being cancelled is not enough on its own: cancel() cascades to the task's
		// nested task, and a nested task that is registered here carries its own end handler - the one
		// that removes from these very collections - so cancelling a parent could write to the collection
		// this method was iterating. On the running set that is a ConcurrentModificationException; on the
		// queue it is quieter and worse, since a shortened LinkedList can end the loop an element early
		// and leave a task uncancelled, still registered, with its handler still attached.
		//
		// Not reachable as the code stands - the only nested tasks in it enter the manager at the moment
		// their parent leaves it, because complete() runs the end handler before the listener that
		// registers the child - but that is an ordering coincidence rather than anything setNestedTask
		// promises. checkDeadlines collects before it cancels for the same reason.
		List<Task<?>> canceled = new ArrayList<>(queuedTasks.size() + runningTasks.size());
		canceled.addAll(queuedTasks);
		canceled.addAll(runningTasks);
		queuedTasks.clear();
		runningTasks.clear();

		// Every handler detached before any task is cancelled, not one task at a time: a cascade can reach
		// a task this loop has not got to yet, and the point is that no cancellation finds a live route
		// back into a manager that is being torn down.
		for (Task<?> task : canceled)
			task.endHandler(null);

		for (Task<?> task : canceled)
			task.cancel();

		canceling = false;
	}

	/**
	 * Returns a string representation of the manager's state.
	 *
	 * @return the string representation
	 */
	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder();

		repr.append("# Running: \n");
		for (Task<?> t : runningTasks)
			repr.append(" - ").append(t).append('\n');

		repr.append("# Queued: \n");
		for (Task<?> t : queuedTasks)
			repr.append(" - ").append(t.toString()).append('\n');

		return repr.toString();
	}
}