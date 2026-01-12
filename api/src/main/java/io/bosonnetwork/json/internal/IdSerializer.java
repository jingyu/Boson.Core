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
import io.bosonnetwork.identifier.DIDConstants;

/**
 * Serializer for {@link Id} objects.
 * <p>
 * Handles serialization of Ids to either a Base64-encoded binary (for binary formats like CBOR)
 * or as a string (either W3C DID or Base58, depending on the context attribute
 * {@link io.bosonnetwork.identifier.DIDConstants#BOSON_ID_FORMAT_W3C}) for text formats (JSON/YAML).
 */
public class IdSerializer extends StdSerializer<Id> {
	private static final long serialVersionUID = -1352630613285716899L;

	/**
	 * Default constructor.
	 */
	public IdSerializer() {
		this(Id.class);
	}

	/**
	 * Constructor with class type.
	 *
	 * @param t the class type
	 */
	public IdSerializer(Class<Id> t) {
		super(t);
	}

	/**
	 * Serializes the {@link Id}.
	 *
	 * @param value    the ID to serialize
	 * @param gen      the generator
	 * @param provider the provider
	 * @throws IOException if serialization fails
	 */
	@Override
	public void serialize(Id value, JsonGenerator gen, SerializerProvider provider) throws IOException {
		Boolean attr = (Boolean) provider.getAttribute(DIDConstants.BOSON_ID_FORMAT_W3C);
		boolean w3cDID = attr != null && attr;
		if (DataFormat.isBinary(gen))
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.bytes(), 0, Id.BYTES);
		else
			gen.writeString(w3cDID ? value.toDIDString() : value.toBase58String());
	}
}