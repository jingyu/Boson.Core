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

import io.bosonnetwork.Id;

public class FindValueRequest extends LookupRequest {
	// Only send the value if the real sequence number greater than this.
	private final int expectedSequenceNumber;

	@JsonCreator
	protected FindValueRequest(@JsonProperty(value = "t", required = true) Id target,
							   @JsonProperty(value = "w", required = true) int want,
							   @JsonProperty(value = "cas") Integer expectedSequenceNumber) {
		super(target, want);
		this.expectedSequenceNumber = expectedSequenceNumber != null ? expectedSequenceNumber : -1;
	}

	public FindValueRequest(Id target, boolean want4, boolean want6, int expectedSequenceNumber) {
		super(target, want4, want6, true);
		this.expectedSequenceNumber = expectedSequenceNumber;
	}

	public int getExpectedSequenceNumber() {
		return expectedSequenceNumber;
	}

	@JsonProperty("cas")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Integer getCas() {
		return expectedSequenceNumber >= 0 ? expectedSequenceNumber : null;
	}

	@Override
	public int hashCode() {
		return 0xF1AD5A1E + super.hashCode() + Integer.hashCode(expectedSequenceNumber);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof FindValueRequest that)
			return expectedSequenceNumber == that.expectedSequenceNumber && super.equals(obj);

		return false;
	}
}