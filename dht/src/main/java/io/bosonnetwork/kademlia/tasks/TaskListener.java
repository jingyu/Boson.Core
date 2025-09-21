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

/**
 * A functional interface for listening to lifecycle events of Kademlia tasks, such as
 * {@link LookupTask}, {@link PingRefreshTask}, {@link NodeLookupTask}, {@link PeerLookupTask},
 * {@link ValueLookupTask}, {@link PeerAnnounceTask}, and {@link ValueAnnounceTask}.
 * Provides callbacks for task start, completion, failure, cancelation, and termination.
 * Designed for single-threaded use in a Vert.x event loop; not thread-safe. Listeners
 * are registered via {@link Task#addListener} and receive events during task execution.
 *
 * @param <S> the specific task type, extending {@link Task}
 */
@FunctionalInterface
public interface TaskListener<S extends Task<S>> {
	/**
	 * Called when the task starts execution.
	 *
	 * @param task the task that started
	 */
	default void started(S task) {
	}

	/**
	 * Called when the task completes successfully.
	 *
	 * @param task   the task that completed
	 */
	default void completed(S task) {
	}

	/**
	 * Called when the task is canceled.
	 *
	 * @param task  the task that was canceled
	 */
	default void canceled(S task) {
	}

	/**
	 * Called when the task ends, regardless of outcome (success, failure, or cancelation).
	 * Must be implemented by listeners.
	 *
	 * @param task the task that ended
	 */
	void ended(S task);
}