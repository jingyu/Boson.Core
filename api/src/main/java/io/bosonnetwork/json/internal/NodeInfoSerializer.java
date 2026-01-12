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
import io.bosonnetwork.NodeInfo;

/**
 * Serializer for {@link NodeInfo} objects.
 * <p>
 * Encodes NodeInfo as a triple/array: [id, host, port]. In binary formats (CBOR),
 * id and host are written as Base64-encoded binary; in text formats (JSON/YAML),
 * id is written as Base58 and host as a string (IP address or hostname).
 */
public class NodeInfoSerializer extends StdSerializer<NodeInfo> {
	private static final long serialVersionUID = 652112589617276783L;

	/**
	 * Default constructor.
	 */
	public NodeInfoSerializer() {
		this(NodeInfo.class);
	}

	/**
	 * Constructor with class type.
	 *
	 * @param t the class type
	 */
	public NodeInfoSerializer(Class<NodeInfo> t) {
		super(t);
	}

	/**
	 * Serializes the {@link NodeInfo}.
	 *
	 * @param value    the node info to serialize
	 * @param gen      the generator
	 * @param provider the provider
	 * @throws IOException if serialization fails
	 */
	@Override
	public void serialize(NodeInfo value, JsonGenerator gen, SerializerProvider provider) throws IOException {
		// Format: triple
		//   [id, host, port]
		//
		// host:
		//   text format: IP address string or hostname string
		//   binary format: binary ip address
		gen.writeStartArray();

		if (DataFormat.isBinary(gen)) {
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.getId().bytes(), 0, Id.BYTES);
			if (value.getAddress().isUnresolved()) {
				// not attempting to do name resolution
				gen.writeString(value.getAddress().getHostString());
			} else {
				byte[] addr = value.getIpAddress().getAddress();
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, addr, 0, addr.length); // binary ip address
			}
		} else {
			gen.writeString(value.getId().toBase58String());
			gen.writeString(value.getHost()); // host name or ip address
		}

		gen.writeNumber(value.getPort());

		gen.writeEndArray();
	}
}