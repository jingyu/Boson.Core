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

import java.util.Comparator;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.kademlia.routing.KBucketEntry;

/**
 * @hidden
 */
public class CandidateNode extends NodeInfo {
	private long lastSent;			/* the time of the last unanswered request */
	private long lastReply;			/* the time of the last reply */
	private boolean acked;			/* whether they acked our announcement */
	private int pinged;
	private boolean reachable;

	private int token;

	/**
	 * @hidden
	 */
	public static final class DistanceOrder implements Comparator<CandidateNode> {
		final Id target;

		public DistanceOrder(Id target) {
			this.target = target;
		}

		@Override
		public int compare(CandidateNode node1, CandidateNode node2) {
			return target.threeWayCompare(node1.getId(), node2.getId());
		}
	}

	public CandidateNode(NodeInfo ni) {
		super(ni);

		if (ni instanceof KBucketEntry entry)
			reachable = entry.isReachable();
	}

	public void setSent() {
		lastSent = System.currentTimeMillis();
		pinged++;
	}

	public void clearSent() {
		lastSent = 0;
	}

	public boolean isSent() {
		return lastSent != 0;
	}

	public int getPinged() {
		return pinged;
	}

	public void setReplied() {
		lastReply = System.currentTimeMillis();
	}

	public boolean isReplied() {
		return lastReply != 0;
	}

	public void setToken(int token) {
		this.token = token;
	}

	public int getToken() {
		return token;
	}

	public boolean isAcked() {
		return acked;
	}

	public boolean isReachable() {
		return reachable;
	}

	public boolean isUnreachable() {
		return pinged >= 3;
	}

	public boolean isInFlight() {
		return lastSent != 0;
	}

	public boolean isEligible() {
		// No pending request and timeout < 3 times
		return lastSent == 0 && pinged < 3;
	}

}