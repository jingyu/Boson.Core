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
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.utils.Json;

@JsonPropertyOrder({"n4", "n6", "k", "rec", "n", "seq", "sig", "v"})
@JsonDeserialize(using = FindValueResponse.Deserializer.class)
public class FindValueResponse extends LookupResponse {
	private final Value value;

	protected FindValueResponse(List<NodeInfo> nodes4, List<NodeInfo> nodes6, Value value) {
		super(nodes4, nodes6, 0);
		this.value = value;
	}

	protected FindValueResponse(Value value) {
		super(null, null, 0);
		this.value = value;
	}

	protected FindValueResponse(List<NodeInfo> nodes4, List<NodeInfo> nodes6) {
		super(nodes4, nodes6, 0);
		this.value = null;
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
		return 0xF1AD5A1E + super.hashCode() + Objects.hashCode(value);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof FindValueResponse that)
			return Objects.equals(value, that.value) && super.equals(obj);

		return false;
	}

	static class Deserializer extends StdDeserializer<FindValueResponse> {
		private static final long serialVersionUID = 8374168035654893465L;

		private static final JavaType nodeInfoListType = Json.cborMapper().constructType(new TypeReference<List<NodeInfo>>() { });

		public Deserializer() {
			this(FindValueResponse.class);
		}

		public Deserializer(Class<FindValueResponse> t) {
			super(t);
		}

		@Override
		public FindValueResponse deserialize(JsonParser p, DeserializationContext ctxt)
				throws IOException, JsonProcessingException {
			if (p.getCurrentToken() != JsonToken.START_OBJECT)
				throw ctxt.wrongTokenException(p, FindValueResponse.class, JsonToken.START_OBJECT,
						"Invalid FindValueResponse: should be an object");

			final boolean binaryFormat = Json.isBinaryFormat(p);

			List<NodeInfo> nodes4 = null;
			List<NodeInfo> nodes6 = null;
			Id publicKey = null;
			Id recipient = null;
			byte[] nonce = null;
			int sequenceNumber = 0;
			byte[] signature = null;
			byte[] data = null;
			Value value = null;

			while (p.nextToken() != JsonToken.END_OBJECT) {
				final String fieldName = p.currentName();
				final JsonToken token = p.nextToken();
				switch (fieldName) {
				case "n4":
					if (token != JsonToken.VALUE_NULL)
						nodes4 = ctxt.readValue(p, nodeInfoListType);
					break;
				case "n6":
					if (token != JsonToken.VALUE_NULL)
						nodes6 = ctxt.readValue(p, nodeInfoListType);
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
					if (token != JsonToken.VALUE_NULL)
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

			if (nodes4 == null && nodes6 == null && data == null)
				ctxt.reportInputMismatch(FindValueResponse.class, "Invalid FindValueResponse: missing nodes or value");

			if (data != null)
				value = Value.of(publicKey, recipient, nonce, sequenceNumber, signature, data);

			return new FindValueResponse(nodes4, nodes6, value);
		}
	}
}