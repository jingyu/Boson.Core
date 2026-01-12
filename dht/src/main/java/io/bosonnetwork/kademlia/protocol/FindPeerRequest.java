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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.bosonnetwork.Id;

@JsonPropertyOrder({"t", "w", "cas", "e"})
public class FindPeerRequest extends LookupRequest {
	private final int expectedSequenceNumber;
	private final int expectedCount;

	@JsonCreator
	protected FindPeerRequest(@JsonProperty(value = "t", required = true) Id target,
							  @JsonProperty(value = "w", required = true) int want,
							  @JsonProperty(value = "cas") Integer expectedSequenceNumber,
							  @JsonProperty(value = "e") Integer expectedCount) {
		super(target, want);
		this.expectedSequenceNumber = expectedSequenceNumber != null ? expectedSequenceNumber : -1;
		this.expectedCount = expectedCount != null ? expectedCount : 0;
	}

	public FindPeerRequest(Id target, boolean want4, boolean want6, int expectedSequenceNumber, int expectedCount) {
		super(target, want4, want6, false);
		this.expectedSequenceNumber = expectedSequenceNumber;
		this.expectedCount = expectedCount;
	}

	public int getExpectedSequenceNumber() {
		return expectedSequenceNumber;
	}

	@JsonProperty("cas")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Integer getCas() {
		return expectedSequenceNumber >= 0 ? expectedSequenceNumber : null;
	}

	public int getExpectedCount() {
		return expectedCount;
	}

	@JsonProperty("e")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Integer getExceptedPeers() {
		return expectedCount > 0 ? expectedCount : null;
	}

	@Override
	public int hashCode() {
		return 0xF1AD9EE2 + super.hashCode() + Integer.hashCode(expectedSequenceNumber) + Integer.hashCode(expectedCount);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof FindPeerRequest that)
			return expectedSequenceNumber == that.expectedSequenceNumber &&
					expectedCount == that.expectedCount &&
					super.equals(obj);

		return false;
	}
}