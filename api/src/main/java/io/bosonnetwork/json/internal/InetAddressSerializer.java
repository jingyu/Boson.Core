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
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

/**
 * Serializer for {@link InetAddress} objects.
 * <p>
 * Serializes InetAddress as a Base64-encoded binary value in binary formats (CBOR),
 * or as a string (IP address or hostname) in text formats (JSON/YAML).
 */
public class InetAddressSerializer extends StdSerializer<InetAddress> {
	private static final long serialVersionUID = 618328089579234961L;

	/**
	 * Default constructor.
	 */
	public InetAddressSerializer() {
		this(InetAddress.class);
	}

	/**
	 * Constructor with class type.
	 *
	 * @param t the class type
	 */
	public InetAddressSerializer(Class<InetAddress> t) {
		super(t);
	}

	/**
	 * Serializes the {@link InetAddress}.
	 *
	 * @param value    the address to serialize
	 * @param gen      the generator
	 * @param provider the provider
	 * @throws IOException if serialization fails
	 */
	@Override
	public void serialize(InetAddress value, JsonGenerator gen, SerializerProvider provider) throws IOException {
		if (DataFormat.isBinary(gen)) {
			byte[] addr = value.getAddress();
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, addr, 0, addr.length); // binary ip address
		} else {
			gen.writeString(value.getHostAddress()); // ip address or host name
		}
	}
}