/*
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
 * A functional interface for filtering and validating results in Kademlia lookup tasks.
 * Implementations determine whether a new result should be accepted or rejected, and whether
 * the lookup process should continue or terminate. Used by {@link LookupTask} subclasses to
 * process results (e.g., comparing sequence numbers in value lookups).
 *
 * @param <T> the type of the result being filtered
 */
@FunctionalInterface
public interface ResultFilter<T> {
	/**
	 * Possible actions returned by the {@link #apply} method, indicating whether to accept
	 * or reject a new result and whether to continue or terminate the lookup.
	 */
	enum Action {
		/** Accept the new result and continue the lookup. */
		ACCEPT_CONTINUE,
		/** Reject the new result and continue the lookup. */
		REJECT_CONTINUE,
		/** Accept the new result and terminate the lookup. */
		ACCEPT_DONE,
		/** Reject the new result and terminate the lookup. */
		REJECT_DONE;

		/**
		 * Checks if the action allows the lookup to continue.
		 *
		 * @return true if the action is {@code ACCEPT_CONTINUE} or {@code REJECT_CONTINUE}
		 */
		public boolean isContinue() {
			return this == ACCEPT_CONTINUE || this == REJECT_CONTINUE;
		}

		/**
		 * Checks if the action terminates the lookup.
		 *
		 * @return true if the action is {@code ACCEPT_DONE} or {@code REJECT_DONE}
		 */
		public boolean isDone() {
			return this == ACCEPT_DONE || this == REJECT_DONE;
		}

		/**
		 * Checks if the action accepts the new result.
		 *
		 * @return true if the action is {@code ACCEPT_CONTINUE} or {@code ACCEPT_DONE}
		 */
		public boolean isAccept() {
			return this == ACCEPT_CONTINUE || this == ACCEPT_DONE;
		}

		/**
		 * Checks if the action rejects the new result.
		 *
		 * @return true if the action is {@code REJECT_CONTINUE} or {@code REJECT_DONE}
		 */
		public boolean isReject() {
			return this == REJECT_CONTINUE || this == REJECT_DONE;
		}
	}

	/**
	 * Filters a new result by comparing it to the previous result, determining whether to
	 * accept or reject it and whether to continue or terminate the lookup.
	 * For example, in a value lookup, this might compare sequence numbers to accept a newer value.
	 *
	 * @param previous the previous result, or null if none
	 * @param next     the new result to evaluate
	 * @return an {@link Action} indicating whether to accept/reject the result and continue/terminate the lookup
	 */
	Action apply(T previous, T next);
}