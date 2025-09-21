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

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.protocol.Message;
import io.bosonnetwork.kademlia.routing.KBucket;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.rpc.RpcCall;

/**
 * A task for refreshing Kademlia routing table buckets by sending PING RPCs to nodes.
 * This task supports refreshing all nodes in a bucket, removing nodes that timeout, or probing
 * replacement nodes in the bucket’s cache. It extends the {@link Task} class to leverage its
 * RPC management and lifecycle handling in a single-threaded Vert.x event loop.
 */
public class PingRefreshTask extends Task<PingRefreshTask> {
	/** Queue of bucket entries to ping. */
	private final Deque<KBucketEntry> todo;

	/** Whether to ping all nodes in the bucket, regardless of their ping status. */
	private boolean checkAll;
	/** Whether to remove nodes from the routing table if their PING RPC times out. */
	private boolean removeOnTimeout;
	/** Whether to ping a replacement node from the bucket’s cache. */
	private boolean probeReplacement;

	private static final Logger log = LoggerFactory.getLogger(PingRefreshTask.class);

	/**
	 * Constructs a new ping refresh task with the given Kademlia context.
	 *
	 * @param context the Kademlia context, must not be null
	 */
	public PingRefreshTask(KadContext context) {
		super(context);
		// Initialize with capacity for typical Kademlia bucket size(main entries and replacement entries)
		this.todo = new ArrayDeque<>(KBucket.MAX_ENTRIES * 2);
	}

	/**
	 * Configures the task to ping all nodes in the bucket, even if recently active.
	 *
	 * @param checkAll true to ping all nodes, false to ping only stale nodes
	 * @return this task for method chaining
	 */
	public PingRefreshTask checkAll(boolean checkAll) {
		this.checkAll = checkAll;
		return this;
	}

	/**
	 * Configures the task to remove nodes from the routing table if their PING RPC times out.
	 *
	 * @param removeOnTimeout true to remove nodes on timeout, false otherwise
	 * @return this task for method chaining
	 */
	public PingRefreshTask removeOnTimeout(boolean removeOnTimeout) {
		this.removeOnTimeout = removeOnTimeout;
		return this;
	}

	/**
	 * Configures the task to ping a replacement node from the bucket’s cache.
	 *
	 * @param probeReplacement true to probe a replacement node, false otherwise
	 * @return this task for method chaining
	 */
	public PingRefreshTask probeReplacement(boolean probeReplacement) {
		this.probeReplacement = probeReplacement;
		return this;
	}

	/**
	 * Sets the bucket to refresh, adding its nodes to the ping queue.
	 *
	 * @param bucket the bucket to refresh, must not be null
	 * @return this task for method chaining
	 * @throws IllegalArgumentException if the bucket is null
	 */
	public PingRefreshTask bucket(KBucket bucket) {
		if (bucket == null)
			throw new IllegalArgumentException("Bucket must not be null");

		addBucket(bucket);
		return this;
	}

	/**
	 * Adds nodes from the specified bucket to the ping queue based on configuration.
	 *
	 * @param bucket the bucket whose nodes to add
	 */
	private void addBucket(KBucket bucket) {
		bucket.updateRefreshTime();

		// Add nodes that need pinging based on configuration
		bucket.entries().forEach(entry -> {
			if (checkAll || removeOnTimeout || entry.needsPing())
				todo.add(entry);
		});

		// Add a replacement node if probing is enabled
		if (probeReplacement) {
			KBucketEntry entry = bucket.findPingableReplacement();
			if (entry != null)
				todo.add(entry);
		}
	}

	/**
	 * Handles a timeout for a PING RPC, optionally removing the node from the routing table.
	 * The routing table is accessed dynamically to avoid using stale bucket references.
	 *
	 * @param call the RPC call that timed out
	 */
	@Override
	protected void callTimeout(RpcCall call) {
		if (removeOnTimeout) {
			// CAUTION:
			// Should not use the original bucket object,
			// because the routing table is dynamic, maybe already changed.
			Id nodeId = call.getTargetId();
			log.info("{}#{} removing timeout entry {} from routing table.", getName(), getId(), nodeId);
			getContext().getDHT().getRoutingTable().removeIfBad(nodeId, true);
		} else {
			log.debug("{}#{} timeout for node {}, not removed (removeOnTimeout=false).", getName(), getId(), call.getTargetId());
		}
	}

	/**
	 * Performs one iteration of the task, sending PING RPCs to nodes in the todo queue.
	 */
	@Override
	protected void iterate() {
		while (!todo.isEmpty() && canDoRequest()) {
			KBucketEntry entry = todo.peekFirst();
			if (entry == null) {
				log.warn("{}#{} unexpected null entry in todo queue", getName(), getId());
				todo.removeFirst();
				continue;
			}

			if (!checkAll && !entry.needsPing()) {
				// Skip entries that were updated during task execution
				log.debug("{}#{} entry {} looks good, skip", getName(), getId(), entry.getId());
				todo.removeFirst();
				continue;
			}

			log.debug("{}#{} sending PING RPC to {}", getName(), getId(), entry.getId());
			Message<Void> request = Message.pingRequest();
			sendCall(entry, request, unused -> todo.removeFirst());
		}
	}

	/**
	 * Checks if the task is complete, requiring both an empty todo queue and no in-flight RPCs.
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