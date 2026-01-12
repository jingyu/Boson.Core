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

package io.bosonnetwork.json.internal;

import java.io.IOException;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import io.bosonnetwork.Id;
import io.bosonnetwork.Value;

/**
 * Serializer for {@link Value} objects.
 * <p>
 * Serializes fields of Value as a JSON object. In binary formats, fields are written as Base64-encoded binary;
 * in text formats, ids are written as Base58 strings and binary fields as Base64. Handles optional fields.
 */
public class ValueSerializer extends StdSerializer<Value> {
	private static final long serialVersionUID = 5494303011447541850L;

	/**
	 * Default constructor.
	 */
	public ValueSerializer() {
		this(Value.class);
	}

	/**
	 * Constructor with class type.
	 *
	 * @param t the class type
	 */
	public ValueSerializer(Class<Value> t) {
		super(t);
	}

	/**
	 * Serializes the {@link Value}.
	 *
	 * @param value    the value to serialize
	 * @param gen      the generator
	 * @param provider the provider
	 * @throws IOException if serialization fails
	 */
	@Override
	public void serialize(Value value, JsonGenerator gen, SerializerProvider provider) throws IOException {
		final boolean binaryFormat = DataFormat.isBinary(gen);
		gen.writeStartObject();

		if (value.getPublicKey() != null) {
			// public key
			if (binaryFormat) {
				gen.writeFieldName("k");
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.getPublicKey().bytes(), 0, Id.BYTES);
			} else {
				gen.writeStringField("k", value.getPublicKey().toBase58String());
			}

			// recipient
			if (value.getRecipient() != null) {
				if (binaryFormat) {
					gen.writeFieldName("rec");
					gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.getRecipient().bytes(), 0, Id.BYTES);
				} else {
					gen.writeStringField("rec", value.getRecipient().toBase58String());
				}
			}

			// nonce
			byte[] binary = value.getNonce();
			if (binary != null) {
				gen.writeFieldName("n");
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, binary, 0, binary.length);
			}

			// sequence number
			if (value.getSequenceNumber() > 0)
				gen.writeNumberField("seq", value.getSequenceNumber());

			// signature
			binary = value.getSignature();
			if (binary != null) {
				gen.writeFieldName("sig");
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, binary, 0, binary.length);
			}
		}

		byte[] data = value.getData();
		if (data != null && data.length > 0) {
			gen.writeFieldName("v");
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, data, 0, data.length);
		}

		gen.writeEndObject();
	}
}