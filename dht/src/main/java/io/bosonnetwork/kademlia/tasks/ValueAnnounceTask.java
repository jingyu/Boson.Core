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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.protocol.StoreValueRequest;

/**
 * A task for performing a Kademlia value announcement to store a value on the closest nodes
 * to a target ID, typically used in a distributed hash table to publish data.
 * This task issues {@code STORE_VALUE} RPCs to nodes from a provided {@link ClosestSet},
 * typically obtained from a {@link NodeLookupTask} with tokens. It extends {@link Task}
 * to leverage its RPC handling in a single-threaded Vert.x event loop.
 */
public class ValueAnnounceTask extends Task<ValueAnnounceTask> {
	/** Queue of nodes to send STORE_VALUE RPCs to. */
	private final Deque<CandidateNode> todo;
	/** The value to store. */
	private final Value value;
	/** The expected sequence number for mutable content; -1 disables the check. */
	private final int expectedSequenceNumber;

	private static final Logger log = LoggerFactory.getLogger(ValueAnnounceTask.class);

	/**
	 * Constructs a new value announcement task for the given value and sequence number.
	 *
	 * @param context               the Kademlia context, must not be null
	 * @param value                 the value to store, must not be null
	 * @param expectedSequenceNumber the sequence number for the value; -1 to disable
	 */
	public ValueAnnounceTask(KadContext context, Value value, int expectedSequenceNumber) {
		super(context);
		this.value = value;
		this.expectedSequenceNumber = expectedSequenceNumber;
		this.todo = new ArrayDeque<>();
	}

	/**
	 * Sets the closest nodes to the value's ID to store to, typically from a {@link NodeLookupTask}.
	 * Assumes nodes have been validated by {@link NodeLookupTask} for eligibility and tokens.
	 *
	 * @param closest the set of closest nodes
	 * @return this task for method chaining
	 */
	public ValueAnnounceTask closest(ClosestSet closest) {
		this.todo.addAll(closest.entries());
		log.debug("{}#{} added {} nodes to announce queue", getName(), getId(), closest.entries().size());
		return this;
	}

	/**
	 * Performs one iteration of the task, sending STORE_VALUE RPCs to nodes in the queue.
	 */
	@Override
	protected void iterate() {
		log.trace("{}#{} todo.size={}", getName(), getId(), todo.size());
		while (!todo.isEmpty() && canDoRequest()) {
			CandidateNode cn = todo.peekFirst();
			if (cn == null) {
				log.warn("{}#{} unexpected null candidate in non-empty queue", getName(), getId());
				continue;
			}

			if (cn.getToken() == 0) {
				log.warn("{}#{} skipping candidate {} due to missing token", getName(), getId(), cn.getId());
				todo.remove(cn);
				continue;
			}

			log.debug("{}#{} sending STORE_VALUE RPC to {}", getName(), getId(), cn.getId());
			Message<StoreValueRequest> request = Message.storeValueRequest(value, cn.getToken(), expectedSequenceNumber);
			sendCall(cn, request, c -> todo.remove(cn));
		}
	}

	/**
	 * Checks if the task is complete, based on an empty queue and no pending RPCs.
	 *
	 * @return true if the task is done, false otherwise
	 */
	@Override
	protected boolean isDone() {
		return todo.isEmpty() && super.isDone();
	}

	/**
	 * Returns a detailed string representation of the task’s state.
	 *
	 * @return the status string
	 */
	@Override
	protected String getStatus() {
		StringBuilder status = new StringBuilder();

		status.append(this).append('\n');
		status.append("todo: \n");
		if (!todo.isEmpty())
			status.append(todo.stream().map(NodeInfo::toString).collect(Collectors.joining("\n    ", "    ", "\n")));
		else
			status.append("    <empty>\n");

		return status.toString();
	}

	/**
	 * Returns the logger for this task.
	 *
	 * @return the logger instance
	 */
	@Override
	protected Logger getLogger() {
		return log;
	}
}