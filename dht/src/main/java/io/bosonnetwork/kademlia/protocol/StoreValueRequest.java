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

import java.io.IOException;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import io.bosonnetwork.Id;
import io.bosonnetwork.Value;
import io.bosonnetwork.utils.Json;

@JsonPropertyOrder({"tok", "cas", "k", "rec", "n", "seq", "sig", "v"})
@JsonDeserialize(using = StoreValueRequest.Deserializer.class)
public class StoreValueRequest implements Request {
	private final int token;
	private final int expectedSequenceNumber;
	private final Value value;

	protected StoreValueRequest(Value value, int token, int expectedSequenceNumber) {
		this.token = token;
		this.expectedSequenceNumber = expectedSequenceNumber;
		this.value = value;
	}

	public StoreValueRequest(int token, int expectedSequenceNumber, Value value) {
		this.token = token;
		this.expectedSequenceNumber = expectedSequenceNumber;
		this.value = value;
	}

	@JsonProperty("tok")
	public int getToken() {
		return token;
	}

	public int getExpectedSequenceNumber() {
		return expectedSequenceNumber;
	}

	public Value getValue() {
		return value;
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
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	protected byte[] getSignature() {
		return value == null ? null : value.getSignature();
	}

	@JsonProperty("v")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	protected byte[] getData() {
		return value == null ? null : value.getData();
	}

	@JsonProperty("cas")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	protected Integer getCas() {
		return expectedSequenceNumber >= 0 ? expectedSequenceNumber : null;
	}

	@Override
	public int hashCode() {
		return Objects.hash(token, expectedSequenceNumber, value);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof StoreValueRequest that)
			return Objects.equals(token, that.token) &&
					Objects.equals(expectedSequenceNumber, that.expectedSequenceNumber) &&
					Objects.equals(value, that.value);

		return false;
	}

	static class Deserializer extends StdDeserializer<StoreValueRequest> {
		private static final long serialVersionUID = -5378630545373241813L;

		public Deserializer() {
			super(StoreValueRequest.class);
		}

		public Deserializer(Class<?> vc) {
			super(vc);
		}

		@Override
		public StoreValueRequest deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
			if (p.getCurrentToken() != JsonToken.START_OBJECT)
				throw ctxt.wrongTokenException(p, StoreValueRequest.class, JsonToken.START_OBJECT,
						"Invalid StoreValueRequest: should be an object");

			final boolean binaryFormat = Json.isBinaryFormat(p);

			int tok = 0;
			int cas = -1;
			Id publicKey = null;
			Id recipient = null;
			byte[] nonce = null;
			int sequenceNumber = 0;
			byte[] signature = null;
			byte[] data = null;

			while (p.nextToken() != JsonToken.END_OBJECT) {
				String fieldName = p.currentName();
				JsonToken token = p.nextToken();
				switch (fieldName) {
				case "tok":
					tok = p.getIntValue();
					break;
				case "cas":
					cas = p.getIntValue();
					break;
				case "k":
					if (token != JsonToken.VALUE_NULL)
						publicKey = binaryFormat ? Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());
					break;
				case "rec":
					if (token != JsonToken.VALUE_NULL)
						recipient = binaryFormat ? Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());
					break;
				case "n":
					if (token != JsonToken.VALUE_NULL)
						nonce = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
					break;
				case "seq":
					if (token != JsonToken.VALUE_NULL)
						sequenceNumber = p.getIntValue();
					break;
				case "sig":
					signature = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
					break;
				case "v":
					if (token != JsonToken.VALUE_NULL)
						data = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
					break;
				default:
					p.skipChildren();
				}
			}

			return new StoreValueRequest(Value.of(publicKey, recipient, nonce, sequenceNumber, signature, data),
					tok, cas);
		}
	}
}