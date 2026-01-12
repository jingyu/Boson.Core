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
import java.net.InetSocketAddress;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;

/**
 * Deserializer for {@link NodeInfo} objects.
 * <p>
 * Expects an array of [id, host, port]. In binary formats, id and host are decoded from Base64-encoded binary;
 * in text formats, id is parsed from Base58 and host from a string (IP address or hostname).
 */
public class NodeInfoDeserializer extends StdDeserializer<NodeInfo> {
	private static final long serialVersionUID = -1802423497777216345L;

	/**
	 * Default constructor.
	 */
	public NodeInfoDeserializer() {
		this(NodeInfo.class);
	}

	/**
	 * Constructor with class type.
	 *
	 * @param vc the class type
	 */
	public NodeInfoDeserializer(Class<?> vc) {
		super(vc);
	}

	/**
	 * Deserializes the {@link NodeInfo}.
	 *
	 * @param p   the parser
	 * @param ctx the context
	 * @return the deserialized node info
	 * @throws IOException if deserialization fails
	 */
	@Override
	public NodeInfo deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
		if (p.currentToken() != JsonToken.START_ARRAY)
			throw MismatchedInputException.from(p, NodeInfo.class, "Invalid NodeInfo, should be an array");

		final boolean binaryFormat = DataFormat.isBinary(p);

		Id id = null;
		InetAddress addr = null;
		String host = null;
		int port = 0;

		// id
		if (p.nextToken() != JsonToken.VALUE_NULL)
			id = binaryFormat ? Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());

		// address
		// text format: IP address string or hostname string
		// binary format: binary ip address or host name string
		JsonToken token = p.nextToken();
		if (token == JsonToken.VALUE_STRING)
			host = p.getText();
		else if (token == JsonToken.VALUE_EMBEDDED_OBJECT)
			addr = InetAddress.getByAddress(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL));
		else
			throw MismatchedInputException.from(p, NodeInfo.class, "Invalid NodeInfo: invalid node address");

		// port
		if (p.nextToken() != JsonToken.VALUE_NULL)
			port = p.getIntValue();

		if (p.nextToken() != JsonToken.END_ARRAY)
			throw MismatchedInputException.from(p, NodeInfo.class, "Invalid NodeInfo: too many elements in array");

		InetSocketAddress isa = addr != null ? new InetSocketAddress(addr, port) : new InetSocketAddress(host, port);
		return new NodeInfo(id, isa);
	}
}