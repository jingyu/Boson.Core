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

import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.kademlia.impl.KadContext;

/**
 * A class for managing Kademlia tasks, handling queuing, execution, removal, and cancellation.
 * Enforces limits on active tasks and concurrent RPC requests to prevent overload in a single-threaded
 * Vert.x event loop. Integrated with {@link KadContext} for task scheduling. Designed for single-threaded
 * use; not thread-safe.
 */
public class TaskManager {
	/** Maximum number of active tasks. */
	static final int MAX_ACTIVE_TASKS = 32;
	/** Maximum concurrent RPC requests for normal tasks. */
	static final int MAX_CONCURRENT_TASK_REQUESTS = 16;
	/** Maximum concurrent RPC requests for low-priority tasks. */
	static final int MAX_CONCURRENT_TASK_REQUESTS_LOW_PRIORITY = 4;

	private final KadContext context;
	private final Deque<Task<?>> queuedTasks;
	private final Set<Task<?>> runningTasks;
	private boolean canceling;

	private static final Logger log = LoggerFactory.getLogger(TaskManager.class);

	/**
	 * Constructs a new TaskManager with the given context and default limits.
	 *
	 * @param context the Kademlia context
	 */
	public TaskManager(KadContext context) {
		this.context = context;

		queuedTasks = new LinkedList<>();
		runningTasks = new HashSet<>();
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
		return !canceling && (runningTasks.size() < MAX_ACTIVE_TASKS);
	}

	/**
	 * Cancels all tasks and clears the manager.
	 * <p>
	 * This method must be invoked from the Vert.x event loop associated with {@link KadContext#getVertxContext()}.
	 * </p>
	 */
	public void cancelAll() {
		canceling = true;

		log.info("Canceling all tasks: running={}, queued={}", runningTasks.size(), queuedTasks.size());
		for (Task<?> task : queuedTasks) {
			task.endHandler(null);
			task.cancel();
		}
		queuedTasks.clear();
		for (Task<?> task : runningTasks) {
			task.endHandler(null);
			task.cancel();
		}
		runningTasks.clear();

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