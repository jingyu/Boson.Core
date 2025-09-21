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
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.protocol.AnnouncePeerRequest;
import io.bosonnetwork.kademlia.protocol.Message;

/**
 * A task for performing a Kademlia peer announcement to advertise a peer to the closest nodes
 * to a peer ID, typically used in BitTorrent-style DHTs to announce peer availability.
 * This task issues {@code ANNOUNCE_PEER} RPCs to nodes from a provided {@link ClosestSet},
 * typically obtained from a {@link NodeLookupTask} with tokens. It extends {@link Task}
 * to leverage its RPC handling in a single-threaded Vert.x event loop.
 */
public class PeerAnnounceTask extends Task<PeerAnnounceTask> {
	/** Queue of nodes to send ANNOUNCE_PEER RPCs to. */
	private final Deque<CandidateNode> todo;
	/** The peer information to announce. */
	private final PeerInfo peer;

	private static final Logger log = LoggerFactory.getLogger(PeerAnnounceTask.class);

	/**
	 * Constructs a new peer announcement task for the given peer.
	 *
	 * @param context the Kademlia context, must not be null
	 * @param peer    the peer information to announce, must be valid
	 * @throws IllegalArgumentException if the peer is invalid
	 */
	public PeerAnnounceTask(KadContext context, PeerInfo peer) {
		super(context);
		this.peer = peer;
		this.todo = new ArrayDeque<>();
	}

	/**
	 * Sets the closest nodes to the peer ID to announce to, typically from a {@link NodeLookupTask}.
	 * Filters out nodes with invalid tokens or ineligible addresses.
	 *
	 * @param closest the set of closest nodes
	 * @return this task for method chaining
	 */
	public PeerAnnounceTask closest(ClosestSet closest) {
		this.todo.addAll(closest.entries());
		log.debug("{}#{} added {} eligible nodes to announce queue", getName(), getId(), closest.size());
		return this;
	}

	/**
	 * Performs one iteration of the task, sending ANNOUNCE_PEER RPCs to nodes in the queue.
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

			log.debug("{}#{} sending ANNOUNCE_PEER RPC to {}", getName(), getId(), cn.getId());
			Message<AnnouncePeerRequest> request = Message.announcePeerRequest(peer, cn.getToken());
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