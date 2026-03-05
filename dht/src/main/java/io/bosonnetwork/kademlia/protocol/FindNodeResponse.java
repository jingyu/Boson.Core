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

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import io.bosonnetwork.NodeInfo;

@JsonPropertyOrder({"n4", "n6", "tok"})
public class FindNodeResponse extends LookupResponse {
	@JsonProperty("tok")
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	private final int token;

	@JsonCreator
	public FindNodeResponse(@JsonProperty("n4") List<? extends NodeInfo> nodes4,
							@JsonProperty("n6") List<? extends NodeInfo> nodes6,
							@JsonProperty("tok") int token) {
		super(nodes4, nodes6);
		this.token = token;
	}

	public int getToken() {
		return token;
	}

	@Override
	public int hashCode() {
		return Objects.hash(nodes4, nodes6, token);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof FindNodeResponse that)
			return Objects.equals(nodes4, that.nodes4) &&
					Objects.equals(nodes6, that.nodes6) &&
					token == that.token;

		return false;
	}
}