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

package io.bosonnetwork.kademlia.protocol;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.bosonnetwork.Id;

/**
 * SUBSCRIBE: asks a node storing a target to notify this node when the record changes. The same request
 * renews the subscription, and cancels it with a lease of zero.
 * <p>
 * A subscription is identified by the subscriber's node id, the kind and the target - nothing the
 * subscriber allocates. The notification names its own subscription: a value's id derives from the value
 * and a peer's id is its key, while the method gives the kind.
 * </p>
 */
@JsonPropertyOrder({"t", "m", "tok", "cas", "l"})
public class SubscribeRequest implements Request {
	/** The largest lease the wire can carry, in seconds: the field is a {@code u16}. */
	public static final int MAX_LEASE = 0xFFFF;

	private final Id target;
	private final SubscriptionKind kind;
	private final int token;
	private final int knownSequenceNumber;
	private final int lease;

	@JsonCreator
	protected SubscribeRequest(
			@JsonProperty(value = "t", required = true) Id target,
			@JsonProperty(value = "m", required = true) int kind,
			@JsonProperty(value = "tok") int token,
			@JsonProperty(value = "cas") Integer knownSequenceNumber,
			@JsonProperty(value = "l") Integer lease) {
		if (target == null)
			throw new IllegalArgumentException("Missing target");
		if (knownSequenceNumber != null && knownSequenceNumber < 0)
			throw new IllegalArgumentException("Invalid known sequence number: " + knownSequenceNumber);
		if (lease != null && (lease < 0 || lease > MAX_LEASE))
			throw new IllegalArgumentException("Invalid lease: " + lease);

		this.target = target;
		this.kind = SubscriptionKind.valueOf(kind);
		this.token = token;
		// Values only. On a peer subscription it carries nothing, and is ignored rather than refused.
		this.knownSequenceNumber = knownSequenceNumber != null && this.kind == SubscriptionKind.VALUE ?
				knownSequenceNumber : -1;
		this.lease = lease != null ? lease : -1;
	}

	/**
	 * Creates a SUBSCRIBE request.
	 *
	 * @param target              the value id or peer id to watch.
	 * @param kind                what to watch.
	 * @param token               the write token for the target; 0 only when cancelling.
	 * @param knownSequenceNumber the sequence number of the value the subscriber already holds, or -1 for
	 *                            none. Ignored for peers.
	 * @param lease               the requested lease in seconds, 0 to cancel, or -1 for the default.
	 */
	public SubscribeRequest(Id target, SubscriptionKind kind, int token, int knownSequenceNumber, int lease) {
		if (lease > MAX_LEASE)
			throw new IllegalArgumentException("Invalid lease: " + lease);

		this.target = Objects.requireNonNull(target, "target");
		this.kind = Objects.requireNonNull(kind, "kind");
		this.token = token;
		this.knownSequenceNumber = kind == SubscriptionKind.VALUE ? Math.max(-1, knownSequenceNumber) : -1;
		this.lease = Math.max(-1, lease);
	}

	@JsonProperty("t")
	public Id getTarget() {
		return target;
	}

	public SubscriptionKind getKind() {
		return kind;
	}

	@JsonProperty("m")
	protected int getKindValue() {
		return kind.value();
	}

	/**
	 * Returns the write token.
	 *
	 * @return the token, or 0 if the request carries none.
	 */
	@JsonProperty("tok")
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	public int getToken() {
		return token;
	}

	/**
	 * Returns the sequence number of the value the subscriber already holds.
	 *
	 * @return the sequence number, or -1 if the subscriber holds nothing, or for a peer subscription.
	 */
	public int getKnownSequenceNumber() {
		return knownSequenceNumber;
	}

	@JsonProperty("cas")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	protected Integer getCas() {
		return knownSequenceNumber >= 0 ? knownSequenceNumber : null;
	}

	/**
	 * Returns the requested lease.
	 *
	 * @return the lease in seconds, 0 to cancel, or -1 if the request leaves it to the receiver.
	 */
	public int getLease() {
		return lease;
	}

	@JsonProperty("l")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	protected Integer getLeaseValue() {
		return lease >= 0 ? lease : null;
	}

	/**
	 * Whether this request cancels the subscription rather than creating or renewing it.
	 *
	 * @return true for a lease of zero.
	 */
	public boolean isCancel() {
		return lease == 0;
	}

	@Override
	public int hashCode() {
		return Objects.hash(target, kind, token, knownSequenceNumber, lease);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof SubscribeRequest that)
			return target.equals(that.target) && kind == that.kind && token == that.token &&
					knownSequenceNumber == that.knownSequenceNumber && lease == that.lease;

		return false;
	}
}
