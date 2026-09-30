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
import io.bosonnetwork.Value;

/**
 * NOTIFY_VALUE: a storing node telling a subscriber that the value it watches has changed.
 * <p>
 * The body is the value exactly as STORE_VALUE carried it, minus the token and {@code cas}, and nothing
 * else - not even the target, which derives from the value. That is what keeps it inside one datagram: a
 * value that fitted in the write that stored it always fits in the notification that reports it.
 * </p>
 */
@JsonPropertyOrder({"k", "rec", "n", "seq", "sig", "v"})
public class NotifyValueRequest implements Request {
	private final Value value;

	@JsonCreator
	protected NotifyValueRequest(
			@JsonProperty(value = "k") Id publicKey,
			@JsonProperty(value = "rec") Id recipient,
			@JsonProperty(value = "n") byte[] nonce,
			@JsonProperty(value = "seq") int sequenceNumber,
			@JsonProperty(value = "sig") byte[] signature,
			@JsonProperty(value = "v", required = true) byte[] data) {
		this.value = Value.of(publicKey, null, recipient, nonce, sequenceNumber, signature, data);
	}

	public NotifyValueRequest(Value value) {
		this.value = Objects.requireNonNull(value, "value");
	}

	public Value getValue() {
		return value;
	}

	@JsonProperty("k")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	protected Id getPublicKey() {
		return value.getPublicKey();
	}

	@JsonProperty("rec")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	protected Id getRecipient() {
		return value.getRecipient();
	}

	@JsonProperty("n")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	protected byte[] getNonce() {
		return value.getNonce();
	}

	@JsonProperty("seq")
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	protected int getSequenceNumber() {
		return value.getSequenceNumber();
	}

	@JsonProperty("sig")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	protected byte[] getSignature() {
		return value.getSignature();
	}

	@JsonProperty("v")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	protected byte[] getData() {
		return value.getData();
	}

	@Override
	public int hashCode() {
		return value.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof NotifyValueRequest that)
			return value.equals(that.value);

		return false;
	}
}
