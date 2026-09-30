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

/**
 * The answer to SUBSCRIBE: the lease actually granted, and a fresh write token for the next renewal, so
 * that renewing needs no lookup of its own.
 */
@JsonPropertyOrder({"tok", "l"})
public class SubscribeResponse implements Response {
	private final int token;
	private final int lease;

	@JsonCreator
	protected SubscribeResponse(
			@JsonProperty(value = "tok") int token,
			@JsonProperty(value = "l") int lease) {
		if (lease < 0 || lease > SubscribeRequest.MAX_LEASE)
			throw new IllegalArgumentException("Invalid lease: " + lease);

		this.token = token;
		this.lease = lease;
	}

	/**
	 * Creates a SUBSCRIBE response.
	 *
	 * @param token the write token for the next renewal, or 0 for none - a cancel is answered without one.
	 * @param lease the granted lease in seconds, 0 for a cancel.
	 */
	public static SubscribeResponse of(int token, int lease) {
		return new SubscribeResponse(token, lease);
	}

	/**
	 * Returns the write token for the next renewal.
	 *
	 * @return the token, or 0 if the response carries none.
	 */
	@JsonProperty("tok")
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	public int getToken() {
		return token;
	}

	/**
	 * Returns the granted lease.
	 *
	 * @return the lease in seconds; 0 when the subscription was cancelled.
	 */
	@JsonProperty("l")
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	public int getLease() {
		return lease;
	}

	@Override
	public int hashCode() {
		return Objects.hash(token, lease);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof SubscribeResponse that)
			return token == that.token && lease == that.lease;

		return false;
	}
}
