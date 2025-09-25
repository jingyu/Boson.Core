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
import com.fasterxml.jackson.annotation.JsonProperty;

import io.bosonnetwork.Id;

// @JsonDeserialize(using = FindNodeRequest.Deserializer.class)
public class FindNodeRequest extends LookupRequest {
	@JsonCreator
	protected FindNodeRequest(@JsonProperty(value = "t", required = true) Id target,
							  @JsonProperty(value = "w", required = true) int want) {
		super(target, want);
	}

	public FindNodeRequest(Id target, boolean want4, boolean want6, boolean wantToken) {
		super(target, want4, want6, wantToken);
	}

	@Override
	public boolean doesWantToken() {
		return super.doesWantToken();
	}

	@Override
	public int hashCode() {
		return 0xF1ADA00D + super.hashCode();
	}

	@Override
	public boolean equals(Object obj) {
		return obj instanceof FindNodeRequest && super.equals(obj);
	}
}