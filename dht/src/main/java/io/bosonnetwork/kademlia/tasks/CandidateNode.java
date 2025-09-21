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

import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.kademlia.routing.KBucketEntry;

/**
 * A class representing a candidate node in Kademlia lookup tasks, extending {@link NodeInfo}
 * with additional state for tracking query status, reachability, and tokens. Used in
 * {@link LookupTask} to manage nodes during iterative lookups and in announce tasks
 * ({@link PeerAnnounceTask}, {@link ValueAnnounceTask}) to send RPCs. Designed for
 * single-threaded use in a Vert.x event loop; not thread-safe.
 */
public class CandidateNode extends NodeInfo {
	/** Time of the last unanswered request. */
	private long lastSent;
	/** Time of the last reply. */
	private long lastReply;
	/** Whether the node acknowledged an announcement (e.g., ANNOUNCE_PEER). */
	private boolean acked;
	/** Number of ping attempts. */
	private int pinged;
	/** Whether the node is considered reachable. */
	private boolean reachable;
	/** Token for ANNOUNCE_PEER or STORE_VALUE RPCs */
	private int token;

	// /** Timeout for considering a request stale (5 seconds in nanoseconds). */
	// private static final long TIMEOUT = 5_000_000_000L; // 5 seconds

	/**
	 * Constructs a new candidate node from a {@link NodeInfo}.
	 *
	 * @param ni the node information, must not be null
	 * @throws IllegalArgumentException if ni is null
	 */
	public CandidateNode(NodeInfo ni) {
		super(ni);

		this.pinged = 0;
		this.acked = false;
		this.lastSent = 0;
		this.lastReply = 0;
		this.token = 0;
		this.reachable = ni instanceof KBucketEntry entry && entry.isReachable();
	}

	/**
	 * Marks the node as having a request sent, incrementing the ping count.
	 */
	public void setSent() {
		lastSent = System.currentTimeMillis();
		pinged++;
	}

	/**
	 * Clears the sent status, allowing the node to be retried.
	 */
	public void clearSent() {
		lastSent = 0;
	}

	/**
	 * Checks if a request has been sent to the node and is pending.
	 *
	 * @return true if a request is pending, false otherwise
	 */
	public boolean isSent() {
		return lastSent != 0;
	}

	/**
	 * Returns the number of ping attempts made to the node.
	 *
	 * @return the ping count
	 */
	public int getPinged() {
		return pinged;
	}

	/**
	 * Marks the node as having replied to a request.
	 */
	public void setReplied() {
		lastReply = System.currentTimeMillis();
	}

	/**
	 * Checks if the node has replied to a request.
	 *
	 * @return true if replied, false otherwise
	 */
	public boolean isReplied() {
		return lastReply != 0;
	}

	/**
	 * Sets the token for ANNOUNCE_PEER or STORE_VALUE RPCs.
	 *
	 * @param token the token
	 */
	public void setToken(int token) {
		this.token = token;
	}

	/**
	 * Returns the token for ANNOUNCE_PEER or STORE_VALUE RPCs.
	 *
	 * @return the token, or null if not set
	 */
	public int getToken() {
		return token;
	}

	/**
	 * Marks the node as having acknowledged an announcement.
	 */
	public void setAcked() {
		this.acked = true;
	}

	/**
	 * Checks if the node acknowledged an announcement.
	 *
	 * @return true if acknowledged, false otherwise
	 */
	public boolean isAcked() {
		return acked;
	}

	/**
	 * Checks if the node is considered reachable.
	 *
	 * @return true if reachable, false otherwise
	 */
	public boolean isReachable() {
		return reachable;
	}

	/**
	 * Checks if the node is unreachable, based on ping attempts and timeout.
	 *
	 * @return true if unreachable (3 or more pings and last request timed out), false otherwise
	 */
	public boolean isUnreachable() {
		return pinged >= 3;
		
		// alternative:
		// return pinged >= 3 && (lastSent == 0 || System.currentTimeMillis() - lastSent > TIMEOUT);
	}

	/**
	 * Checks if a request is currently in flight (sent but not replied or timed out).
	 *
	 * @return true if in flight, false otherwise
	 */
	public boolean isInFlight() {
		return lastSent != 0;
	}

	/**
	 * Checks if the node is eligible for querying (no pending request and fewer than 3 pings).
	 *
	 * @return true if eligible, false otherwise
	 */
	public boolean isEligible() {
		// without pending request and timeout < 3 times
		return lastSent == 0 && pinged < 3;
	}
}