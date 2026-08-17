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

package io.bosonnetwork;

import org.jspecify.annotations.Nullable;

/**
 * A store or announce reached no node: nothing on the network holds the value or peer.
 * <p>
 * {@link Node#storeValue} and {@link Node#announcePeer} publish to the closest nodes a lookup found, and
 * this is how they report having published to none of them. Every route there is covered - the lookup found
 * no reachable nodes at all, none of the ones it found supplied a token to write with, every node refused,
 * or every request timed out. The message names which, since the reaction is the same in each case and what
 * separates them is diagnosis.
 * </p>
 * <p>
 * <b>One node answering is enough to succeed</b>, so this is not a quorum failure - it means zero. Kademlia
 * has never required a write to reach all k, and a threshold above one is a number nobody can choose
 * correctly without measuring the network it will run on. Anything short of zero completes instead, and
 * {@link #getResult()} is where a caller stricter than "somebody has it" looks.
 * </p>
 * <p>
 * <b>Raised for a refused payload too, and the distinction matters.</b> A node refusing the write on the
 * payload's own terms - a sequence number that lost its compare-and-set, an immutable value being replaced
 * - is one node's claim and nothing more, so it never abandons the publish and never speaks for the
 * network. When every node that refused said the same thing, that shared cause is attached here as this
 * exception's own cause; the individual answers are in the result either way.
 * </p>
 * <p>
 * The local copy is unaffected: both operations write locally before publishing, and that write has already
 * succeeded by the time this can be raised. The payload is held and served from this node's storage, but
 * nothing will find it there - see {@link AnnounceResult} for why that is not a contradiction.
 * </p>
 */
public class AnnounceFailedException extends BosonException {
	private static final long serialVersionUID = 4726093180592886625L;

	/** What each node answered; null only for a failure raised before any node was considered. */
	private final transient @Nullable AnnounceResult result;

	/**
	 * Constructs a new exception with a message describing what the attempt met.
	 *
	 * @param message the detail message, saved for later retrieval by {@link #getMessage()}.
	 */
	public AnnounceFailedException(String message) {
		super(message);
		this.result = null;
	}

	/**
	 * Constructs a new exception with a detailed message and a cause.
	 *
	 * @param message the detail message, saved for later retrieval by {@link #getMessage()}.
	 * @param cause the cause of the exception, saved for later retrieval by {@link #getCause()}.
	 *              A {@code null} value is permitted and indicates that the cause is nonexistent or unknown.
	 */
	public AnnounceFailedException(String message, Throwable cause) {
		super(message, cause);
		this.result = null;
	}

	/**
	 * Constructs a new exception carrying what each node answered.
	 * <p>
	 * The cause is the result's {@link AnnounceResult#unanimousRefusal()} when there is one, so that a
	 * caller can keep catching the typed refusal it cares about while the per-node detail stays available
	 * to anyone who wants to weigh it.
	 * </p>
	 *
	 * @param message the detail message, saved for later retrieval by {@link #getMessage()}.
	 * @param result  what each node answered.
	 */
	public AnnounceFailedException(String message, AnnounceResult result) {
		super(message, result.unanimousRefusal());
		this.result = result;
	}

	/**
	 * What each node answered.
	 *
	 * @return the per-node result, or null if the attempt failed before any node was considered.
	 */
	public @Nullable AnnounceResult getResult() {
		return result;
	}
}
