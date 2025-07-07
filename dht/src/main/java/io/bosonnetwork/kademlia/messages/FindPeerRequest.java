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

package io.bosonnetwork.kademlia.messages;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.bosonnetwork.Id;

// @JsonDeserialize(using = FindPeerRequest.Deserializer.class)
public class FindPeerRequest extends LookupRequest {
	@JsonCreator
	protected FindPeerRequest(@JsonProperty(value = "t", required = true) Id target,
							  @JsonProperty(value = "w", required = true) int want) {
		super(target, want);
	}

	public FindPeerRequest(Id target, boolean want4, boolean want6) {
		super(target, want4, want6, false);
	}

	@Override
	public int hashCode() {
		return 0xF1AD9EE2 + super.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof FindPeerRequest && super.equals(obj);
	}
}