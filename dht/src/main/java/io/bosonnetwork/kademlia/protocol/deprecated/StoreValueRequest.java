/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

package io.bosonnetwork.kademlia.protocol.deprecated;

import java.io.IOException;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;

import io.bosonnetwork.Id;
import io.bosonnetwork.Value;
import io.bosonnetwork.utils.Hex;

/**
 * @hidden
 */
public class StoreValueRequest extends OldMessage {
	private int token;
	private Id publicKey;
	private Id recipient;
	private byte[] nonce;
	private int sequenceNumber = 0;
	private int expectedSequenceNumber = 0;
	private byte[] signature;
	private byte[] value;

	public StoreValueRequest() {
		super(Type.REQUEST, Method.STORE_VALUE);
	}

	public StoreValueRequest(Value value, int token) {
		this();
		setToken(token);
		setValue(value);
	}

	public int getToken() {
		return token;
	}

	public void setToken(int token) {
		this.token = token;
	}

	public void setValue(Value value) {
		this.publicKey = value.getPublicKey();
		this.recipient = value.getRecipient();
		this.nonce = value.getNonce();
		this.signature = value.getSignature();
		this.sequenceNumber = value.getSequenceNumber();
		this.value = value.getData();
	}

	public Value getValue() {
		return Value.of(publicKey, recipient, nonce, sequenceNumber, signature, value);
	}

	public int getExpectedSequenceNumber() {
		return expectedSequenceNumber;
	}

	public void setExpectedSequenceNumber(int expectedSequenceNumber) {
		this.expectedSequenceNumber = expectedSequenceNumber;
	}


	public boolean isMutable() {
		return publicKey != null;
	}

	public Id getValueId() {
		return Value.calculateId(publicKey, value);
	}

	@Override
	protected void serialize(JsonGenerator gen) throws IOException {
		gen.writeFieldName(getType().toString());
		gen.writeStartObject();

		gen.writeNumberField("tok", token);

		if (publicKey != null) {
			if (expectedSequenceNumber > 0)
				gen.writeNumberField("cas", expectedSequenceNumber);

			gen.writeFieldName("k");
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, publicKey.bytes(), 0, Id.BYTES);

			if (recipient != null) {
				gen.writeFieldName("rec");
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, recipient.bytes(), 0, Id.BYTES);
			}

			if (nonce != null) {
				gen.writeFieldName("n");
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, nonce, 0, nonce.length);
			}

			if (sequenceNumber > 0)
				gen.writeNumberField("seq", sequenceNumber);

			if (signature != null) {
				gen.writeFieldName("sig");
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, signature, 0, signature.length);
			}
		}

		gen.writeFieldName("v");
		gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value, 0, value.length);

		gen.writeEndObject();
	}

	@Override
	protected void parse(String fieldName, CBORParser parser) throws MessageException, IOException {
		if (!fieldName.equals(Type.REQUEST.toString()) || parser.getCurrentToken() != JsonToken.START_OBJECT)
			throw new MessageException("Invalid " + getMethod() + " request message");

		while (parser.nextToken() != JsonToken.END_OBJECT) {
			String name = parser.currentName();
			parser.nextToken();
			switch (name) {
			case "cas":
				expectedSequenceNumber = parser.getIntValue();
				break;

			case "k":
				publicKey = Id.of(parser.getBinaryValue(Base64Variants.MODIFIED_FOR_URL));
				break;

			case "rec":
				recipient = Id.of(parser.getBinaryValue(Base64Variants.MODIFIED_FOR_URL));
				break;

			case "n":
				nonce = parser.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
				break;

			case "sig":
				signature = parser.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
				break;

			case "seq":
				sequenceNumber = parser.getIntValue();
				break;

			case "tok":
				token = parser.getIntValue();
				break;

			case "v":
				value = parser.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
				break;

			default:
				System.out.println("Unknown field: " + fieldName);
				break;
			}
		}
	}

	@Override
	public int estimateSize() {
		return super.estimateSize() + 208 + value.length;
	}

	@Override
	protected void toString(StringBuilder b) {
		b.append(",q:{");

		if (publicKey != null) {
			b.append("k:").append(publicKey);

			if (recipient != null)
				b.append(",rec:").append(recipient);

			if (nonce != null)
				b.append(",n:").append(Hex.encode(nonce));

			if (sequenceNumber >= 0)
				b.append(",seq:").append(sequenceNumber);

			if (signature != null)
				b.append(",sig:").append(Hex.encode(signature));

			if (expectedSequenceNumber >= 0)
				b.append(",cas:").append(expectedSequenceNumber);

			b.append(",");
		}

		b.append("tok:").append(token);
		b.append(",v:").append(Hex.encode(value));
		b.append("}");
	}
}