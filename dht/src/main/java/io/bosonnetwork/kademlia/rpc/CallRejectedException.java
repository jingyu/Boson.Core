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

package io.bosonnetwork.kademlia.rpc;

/**
 * The transport would not carry a call: it was refused rather than sent.
 * <p>
 * {@link RpcServer#sendCall} rejects rather than queues when the node is at one of its own limits - the
 * active-call table is full, the sub-budget for calls that arriving traffic provokes is spent, or the
 * outbound throttle would hold this one longer than we would ever wait for its answer. All three are
 * ordinary runtime outcomes under load, and all three reach the caller the same way: the call ends in
 * {@link RpcCall.State#ERROR} carrying this as its cause, and the returned future fails with the same
 * instance.
 * </p>
 * <p>
 * One type rather than one per limit, because for most callers the reaction is the same in every case -
 * the candidate was never asked, so try another node. The type exists to separate all three from an
 * {@link IllegalStateException}, which in Java means the caller did something wrong; none of these are that.
 * </p>
 * <p>
 * The limit that refused is still carried as a {@link Reason}, for a caller that must react differently:
 * a full active-call table is the whole node's congestion, so a caller with a queue of its own should stop
 * draining it for a while, whereas the outbound throttle refuses on account of one destination, and
 * stopping everything for it would hold up every other destination behind one busy host.
 * </p>
 * <p>
 * Deliberately not a {@code KadException}: those carry a protocol error code that
 * {@code DHT.exceptionToError} puts into a response to a remote peer, and this node's own capacity is
 * nothing to tell a peer about. Unchecked, because it travels as a future's cause rather than up a call
 * stack.
 * </p>
 */
public class CallRejectedException extends RuntimeException {
	private static final long serialVersionUID = -5539919478923821563L;

	/**
	 * Which of the node's limits refused the call.
	 */
	public enum Reason {
		/** The active-call table is full: the node as a whole is at capacity. */
		ACTIVE_CALLS,
		/** The sub-budget for calls that arriving traffic provokes is spent. */
		UNSOLICITED_BUDGET,
		/** The outbound throttle would hold the call past its horizon: too much is queued for its destination. */
		OUTBOUND_THROTTLE
	}

	private final Reason reason;

	/**
	 * Constructs a rejection with the limit that refused the call.
	 *
	 * @param reason  which limit refused the call
	 * @param message a description of it, for diagnosis
	 */
	public CallRejectedException(Reason reason, String message) {
		super(message);
		this.reason = reason;
	}

	/**
	 * Returns which limit refused the call.
	 *
	 * @return the reason
	 */
	public Reason getReason() {
		return reason;
	}
}
