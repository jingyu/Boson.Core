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
import java.net.InetSocketAddress;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;

/**
 * Serializer for {@link NodeInfo} objects.
 * <p>
 * Encodes NodeInfo as an array: {@code [id, host, port]} for a single-address node, or
 * {@code [id, host4, port4, host6, port6]} for a dual-stack node (IPv4 first, then IPv6). In binary
 * formats (CBOR), id and host are written as Base64-encoded binary; in text formats (JSON/YAML),
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
		// Format: triple or 5-tuple:
		//   [id, host, port] | [id, host4, port4, host6, port6]
		//
		// host:
		//   text format: IP address string or hostname string
		//   binary format: binary ip address

		InetSocketAddress sockAddr4 = value.getAddress4();
		InetSocketAddress sockAddr6 = value.getAddress6();
		if (sockAddr4 == null && sockAddr6 == null)
			throw new IllegalStateException("NodeInfo must have at least one address");

		gen.writeStartArray();

		if (DataFormat.isBinary(gen)) {
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.getId().bytesUnsafe(), 0, Id.BYTES);

			if (sockAddr4 != null) {
				if (sockAddr4.isUnresolved()) {
					// not attempting to do name resolution
					gen.writeString(sockAddr4.getHostString());
				} else {
					byte[] addr = sockAddr4.getAddress().getAddress();
					gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, addr, 0, addr.length); // binary ip address
				}

				gen.writeNumber(sockAddr4.getPort());
			}

			if (sockAddr6 != null) {
				if (sockAddr6.isUnresolved()) {
					// not attempting to do name resolution
					gen.writeString(sockAddr6.getHostString());
				} else {
					byte[] addr = sockAddr6.getAddress().getAddress();
					gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, addr, 0, addr.length); // binary ip address
				}

				gen.writeNumber(sockAddr6.getPort());
			}
		} else {
			gen.writeString(value.getId().toBase58String());
			if (sockAddr4 != null) {
				gen.writeString(sockAddr4.getHostString()); // host name or ip address
				gen.writeNumber(sockAddr4.getPort());
			}

			if (sockAddr6 != null) {
				gen.writeString(sockAddr6.getHostString()); // host name or ip address
				gen.writeNumber(sockAddr6.getPort());
			}
		}

		gen.writeEndArray();
	}
}