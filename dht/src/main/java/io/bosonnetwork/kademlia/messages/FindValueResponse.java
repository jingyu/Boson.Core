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

package io.bosonnetwork.kademlia.messages;

import java.io.IOException;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;

import io.bosonnetwork.Id;
import io.bosonnetwork.Value;
import io.bosonnetwork.utils.Hex;

/**
 * @hidden
 */
public class FindValueResponse extends LookupResponse {
	private Id publicKey;
	private Id recipient;
	private byte[] nonce;
	private byte[] signature;
	private int sequenceNumber = 0;
	private byte[] value;

	public FindValueResponse(int txid) {
		super(Method.FIND_VALUE, txid);
	}

	protected FindValueResponse() {
		this(0);
	}

	public void setValue(Value value) {
		this.publicKey = value.getPublicKey();
		this.recipient = value.getRecipient();
		this.nonce = value.getNonce();
		this.signature = value.getSignature();
		this.sequenceNumber = value.getSequenceNumber();
		this.value = value.getData();
	}

	public boolean hasValue() {
		return value != null && value.length != 0;
	}

	public Value getValue() {
		if (!hasValue())
			return null;

		return Value.of(publicKey, recipient, nonce, sequenceNumber, signature, value);
	}

	@Override
	protected void _serialize(JsonGenerator gen) throws IOException {
		if (publicKey != null) {
			gen.writeFieldName("k");
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, publicKey.bytes(), 0, Id.BYTES);
		}

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

		if (value != null) {
			gen.writeFieldName("v");
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value, 0, value.length);
		}
	}

	@Override
	protected void _parse(String fieldName, CBORParser parser) throws IOException {
		switch (fieldName) {
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

		case "v":
			value = parser.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
			break;

		default:
			System.out.println("Unknown field: " + fieldName);
			break;
		}
	}

	@Override
	public int estimateSize() {
		return super.estimateSize() + 195 + (value == null ? 0 : value.length);
	}

	@Override
	protected void _toString(StringBuilder b) {
		if (publicKey != null)
			b.append(",k:").append(publicKey);

		if (recipient != null)
			b.append(",rec:").append(recipient);

		if (nonce != null)
			b.append(",n:").append(Hex.encode(nonce));

		if (sequenceNumber > 0)
			b.append(",seq:").append(sequenceNumber);

		if (signature != null)
			b.append(",sig:").append(Hex.encode(signature));

		if (value != null)
			b.append(",v:").append(Hex.encode(value));
	}
}