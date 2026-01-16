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
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import io.bosonnetwork.Id;
import io.bosonnetwork.Value;

/**
 * Deserializer for {@link Value} objects.
 * <p>
 * Decodes a JSON object with fields corresponding to Value's structure. In binary formats,
 * fields are decoded from Base64-encoded binary; in text formats, ids are parsed from Base58 strings.
 * Handles missing or optional fields gracefully.
 */
public class ValueDeserializer extends StdDeserializer<Value> {
	private static final long serialVersionUID = 2370471437259629126L;

	/**
	 * Default constructor.
	 */
	public ValueDeserializer() {
		this(Value.class);
	}

	/**
	 * Constructor with class type.
	 *
	 * @param vc the class type
	 */
	public ValueDeserializer(Class<?> vc) {
		super(vc);
	}

	/**
	 * Deserializes the {@link Value}.
	 *
	 * @param p   the parser
	 * @param ctx the context
	 * @return the deserialized value
	 * @throws IOException if deserialization fails
	 */
	@Override
	public Value deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
		if (p.currentToken() != JsonToken.START_OBJECT)
			throw MismatchedInputException.from(p, Value.class, "Invalid Value: should be an object");

		final boolean binaryFormat = DataFormat.isBinary(p);

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
				case "k":
					if (token != JsonToken.VALUE_NULL)
						publicKey = binaryFormat || token != JsonToken.VALUE_STRING ?
								Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());
					break;
				case "rec":
					if (token != JsonToken.VALUE_NULL)
						recipient = binaryFormat || token != JsonToken.VALUE_STRING ?
								Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());
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

		return Value.of(publicKey, recipient, nonce, sequenceNumber, signature, data);
	}
}