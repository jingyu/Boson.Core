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


package io.bosonnetwork.service;

import java.time.Duration;
import java.util.Objects;

/**
 * Whether this node accepts traffic from another node for the service asking, and why.
 * <p>
 * The decision follows from the reason, so the two can't disagree. An admission may be relied on for
 * {@link #maxAge()}; after that, or as soon as a {@link FederationEvent} says the node's admission has
 * changed, ask again.
 *
 * @param reason why the node is admitted, pending or refused
 * @param maxAge how long the admission may be cached by the caller, measured on its own clock
 */
public record Admission(Reason reason, Duration maxAge) {
	/**
	 * What the caller may do.
	 */
	public enum Decision {
		/** Exchange traffic with the node. */
		ALLOW,
		/** Not yet: a relationship has been asked for and not answered. Hold the traffic, or tell the sender. */
		PENDING,
		/** Exchange nothing with the node. */
		DENY
	}

	/**
	 * Why a node is admitted, pending or refused.
	 */
	public enum Reason {
		/** This node federates openly, and nothing excludes the other one. */
		OPEN(Decision.ALLOW),
		/** The two nodes have an active relationship. */
		ACTIVE(Decision.ALLOW),
		/** A relationship has been proposed and not yet answered. */
		PENDING(Decision.PENDING),
		/** This node federates only by agreement or by list, and has no relationship with the other one. */
		NO_RELATIONSHIP(Decision.DENY),
		/** A proposal between the two nodes was declined. */
		DECLINED(Decision.DENY),
		/** The relationship has been suspended, by either side. */
		SUSPENDED(Decision.DENY),
		/** This node's operator has blocked the other one. */
		BLOCKED(Decision.DENY),
		/** Federation is turned off on this node. */
		DISABLED(Decision.DENY),
		/** No announcement of the peer can be found. */
		UNKNOWN_PEER(Decision.DENY),
		/** The peer's announcements don't prove which node it belongs to. */
		UNVERIFIED_PEER(Decision.DENY);

		private final Decision decision;

		Reason(Decision decision) {
			this.decision = decision;
		}

		/**
		 * Returns the decision that follows from this reason.
		 *
		 * @return the decision
		 */
		public Decision decision() {
			return decision;
		}
	}

	/**
	 * Creates an admission.
	 *
	 * @param reason why the node is admitted, pending or refused
	 * @param maxAge how long the admission may be cached by the caller, not negative
	 */
	public Admission {
		Objects.requireNonNull(reason, "reason");
		Objects.requireNonNull(maxAge, "maxAge");
		if (maxAge.isNegative())
			throw new IllegalArgumentException("Negative maxAge: " + maxAge);
	}

	/**
	 * Returns the decision that follows from the reason.
	 *
	 * @return the decision
	 */
	public Decision decision() {
		return reason.decision();
	}

	/**
	 * Tells whether traffic with the node is allowed.
	 *
	 * @return {@code true} if the decision is {@link Decision#ALLOW}
	 */
	public boolean allowed() {
		return reason.decision() == Decision.ALLOW;
	}
}
