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
import java.net.InetAddress;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

/**
 * Deserializer for {@link InetAddress} objects.
 * <p>
 * Decodes either a Base64-encoded binary address (for binary formats like CBOR)
 * or a string representation (IP address or hostname) for text formats.
 */
public class InetAddressDeserializer extends StdDeserializer<InetAddress> {
	private static final long serialVersionUID = -5009935040580375373L;

	/**
	 * Default constructor.
	 */
	public InetAddressDeserializer() {
		this(InetAddress.class);
	}

	/**
	 * Constructor with class type.
	 *
	 * @param vc the class type
	 */
	public InetAddressDeserializer(Class<?> vc) {
		super(vc);
	}

	/**
	 * Deserializes the {@link InetAddress}.
	 *
	 * @param p   the parser
	 * @param ctx the context
	 * @return the deserialized address
	 * @throws IOException if deserialization fails
	 */
	@Override
	public InetAddress deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
		return DataFormat.isBinary(p) || p.currentToken() != JsonToken.VALUE_STRING ?
				InetAddress.getByAddress(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) :
				InetAddress.getByName(p.getText());
	}
}