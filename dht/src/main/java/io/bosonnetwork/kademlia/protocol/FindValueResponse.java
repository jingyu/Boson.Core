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

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.Value;

@JsonPropertyOrder({"n4", "n6", "k", "rec", "n", "seq", "sig", "v"})
public class FindValueResponse extends LookupResponse {
	private final Value value;

	@JsonCreator
	protected FindValueResponse(
			@JsonProperty("n4") List<? extends NodeInfo> nodes4,
			@JsonProperty("n6") List<? extends NodeInfo> nodes6,
			@JsonProperty("k") Id publicKey,
			@JsonProperty("rec") Id recipient,
			@JsonProperty("n") byte[] nonce,
			@JsonProperty("seq") int sequenceNumber,
			@JsonProperty("sig") byte[] signature,
			@JsonProperty("v") byte[] data) {
		super(nodes4, nodes6);
		if (nodes4 == null && nodes6 == null && data == null)
			throw new IllegalArgumentException("Invalid FindValueResponse: missing nodes or value");
		this.value = data != null ? Value.of(publicKey, recipient, nonce, sequenceNumber, signature, data) : null;
	}

	protected FindValueResponse(List<? extends NodeInfo> nodes4, List<? extends NodeInfo> nodes6, Value value) {
		super(nodes4, nodes6);
		this.value = value;
	}

	protected FindValueResponse(Value value) {
		super(null, null);
		this.value = value;
	}

	protected FindValueResponse(List<? extends NodeInfo> nodes4, List<? extends NodeInfo> nodes6) {
		super(nodes4, nodes6);
		this.value = null;
	}

	public Value getValue() {
		return value;
	}

	public boolean hasValue() {
		return value != null;
	}

	@JsonProperty("k")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	protected Id getPublicKey() {
		return value == null ? null : value.getPublicKey();
	}

	@JsonProperty("rec")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	protected Id getRecipient() {
		return value == null ? null : value.getRecipient();
	}

	@JsonProperty("n")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	protected byte[] getNonce() {
		return value == null ? null : value.getNonce();
	}

	@JsonProperty("seq")
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	protected int getSequenceNumber() {
		return value == null ? 0 : value.getSequenceNumber();
	}

	@JsonProperty("sig")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	protected byte[] getSignature() {
		return value == null ? null : value.getSignature();
	}

	@JsonProperty("v")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	protected byte[] getData() {
		return value == null ? null : value.getData();
	}

	@Override
	public int hashCode() {
		return Objects.hash(nodes4, nodes6, value);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof FindValueResponse that)
			return Objects.equals(nodes4, that.nodes4) &&
					Objects.equals(nodes6, that.nodes6) &&
					Objects.equals(value, that.value);

		return false;
	}
}