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

import io.bosonnetwork.Id;

/**
 * Deserializer for {@link Id} objects.
 * <p>
 * Handles decoding from either Base64-encoded binary (for binary formats like CBOR)
 * or from string (Base58 or W3C DID) for text formats (JSON/YAML).
 */
public class IdDeserializer extends StdDeserializer<Id> {
	private static final long serialVersionUID = 8977820243454538303L;

	/**
	 * Default constructor.
	 */
	public IdDeserializer() {
		this(Id.class);
	}

	/**
	 * Constructor with class type.
	 *
	 * @param vc the class type
	 */
	public IdDeserializer(Class<?> vc) {
		super(vc);
	}

	/**
	 * Deserializes the {@link Id}.
	 *
	 * @param p   the parser
	 * @param ctx the context
	 * @return the deserialized ID
	 * @throws IOException if deserialization fails
	 */
	@Override
	public Id deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
		return DataFormat.isBinary(p) || p.currentToken() != JsonToken.VALUE_STRING ?
				Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());
	}
}