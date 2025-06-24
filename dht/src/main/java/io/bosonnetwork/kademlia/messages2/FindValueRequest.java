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

package io.bosonnetwork.kademlia.messages2;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.bosonnetwork.Id;

public class FindValueRequest extends LookupRequest {
	// Only send the value if the real sequence number greater than this.
	@JsonProperty("seq")
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	private final int sequenceNumber;

	@JsonCreator
	protected FindValueRequest(@JsonProperty(value = "t", required = true) Id target,
							   @JsonProperty(value = "w", required = true) int want,
							   @JsonProperty(value = "seq") int sequenceNumber) {
		super(target, want);
		this.sequenceNumber = sequenceNumber < 0 ? 0 : sequenceNumber;
	}

	public FindValueRequest(Id target, boolean want4, boolean want6, int sequenceNumber) {
		super(target, want4, want6, true);
		this.sequenceNumber = sequenceNumber;
	}

	public int getSequenceNumber() {
		return sequenceNumber;
	}

	@Override
	public int hashCode() {
		return 0xF1AD5A1E + super.hashCode() + Integer.hashCode(sequenceNumber);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof FindValueRequest that)
			return sequenceNumber == that.sequenceNumber && super.equals(obj);

		return false;
	}
}