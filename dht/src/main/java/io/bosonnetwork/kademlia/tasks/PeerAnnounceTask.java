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

import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.kademlia.impl.KadContext;
import io.bosonnetwork.kademlia.protocol.Message;

/**
 * A task for performing a Kademlia peer announcement to advertise a peer to the closest nodes
 * to a peer ID, typically used in BitTorrent-style DHTs to announce peer availability.
 * This task issues {@code ANNOUNCE_PEER} RPCs to nodes from a provided {@link ClosestSet},
 * typically obtained from a {@link NodeLookupTask} with tokens. It extends {@link AnnounceTask}
 * to leverage its RPC handling and outcome accounting in a single-threaded Vert.x event loop.
 */
public class PeerAnnounceTask extends AnnounceTask<PeerAnnounceTask> {
	/** The peer information to announce. */
	private final PeerInfo peer;

	private static final Logger log = LoggerFactory.getLogger(PeerAnnounceTask.class);

	/**
	 * Constructs a new peer announcement task for the given peer.
	 *
	 * @param context the Kademlia context, must not be null
	 * @param peer    the peer information to announce, must be valid
	 * @param expectedSequenceNumber the expected sequence number for the peer; -1 to disable
	 */
	public PeerAnnounceTask(KadContext context, PeerInfo peer, int expectedSequenceNumber) {
		super(context, expectedSequenceNumber);
		this.peer = peer;
	}

	/**
	 * Builds the ANNOUNCE_PEER request for one node.
	 *
	 * @param cn the node to announce to
	 * @return the request message
	 */
	@Override
	protected Message createRequest(CandidateNode cn) {
		return Message.announcePeerRequest(peer, cn.getToken(), expectedSequenceNumber);
	}

	/**
	 * Returns the RPC name for log lines.
	 *
	 * @return the method name
	 */
	@Override
	protected String getMethodName() {
		return "ANNOUNCE_PEER";
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
