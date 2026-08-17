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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Value;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.protocol.Message;

/**
 * A task for performing a Kademlia value announcement to store a value on the closest nodes
 * to a target ID, typically used in a distributed hash table to publish data.
 * This task issues {@code STORE_VALUE} RPCs to nodes from a provided {@link ClosestSet},
 * typically obtained from a {@link NodeLookupTask} with tokens. It extends {@link AnnounceTask}
 * to leverage its RPC handling and outcome accounting in a single-threaded Vert.x event loop.
 */
public class ValueAnnounceTask extends AnnounceTask<ValueAnnounceTask> {
	/** The value to store. */
	private final Value value;

	private static final Logger log = LoggerFactory.getLogger(ValueAnnounceTask.class);

	/**
	 * Constructs a new value announcement task for the given value and sequence number.
	 *
	 * @param context               the Kademlia context, must not be null
	 * @param value                 the value to store, must not be null
	 * @param expectedSequenceNumber the sequence number for the value; -1 to disable
	 */
	public ValueAnnounceTask(KadContext context, Value value, int expectedSequenceNumber) {
		super(context, expectedSequenceNumber);
		this.value = value;
	}

	/**
	 * Builds the STORE_VALUE request for one node.
	 *
	 * @param cn the node to store to
	 * @return the request message
	 */
	@Override
	protected Message createRequest(CandidateNode cn) {
		return Message.storeValueRequest(value, cn.getToken(), expectedSequenceNumber);
	}

	/**
	 * Returns the RPC name for log lines.
	 *
	 * @return the method name
	 */
	@Override
	protected String getMethodName() {
		return "STORE_VALUE";
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
