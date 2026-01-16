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
import io.bosonnetwork.PeerInfo;

/**
 * Deserializer for {@link PeerInfo} objects.
 * <p>
 * Expects a 6-element array: [peerId, nodeId, originNodeId, port, alternativeURI, signature].
 * In binary formats, ids and signature are decoded from Base64-encoded binary; in text formats, ids are Base58 strings.
 * Special behavior: if peerId is omitted (null), it is taken from the context attribute
 * {@link io.bosonnetwork.PeerInfo#ATTRIBUTE_PEER_ID}.
 */
public class PeerInfoDeserializer extends StdDeserializer<PeerInfo> {
	private static final long serialVersionUID = 6475890164214322573L;

	/**
	 * Default constructor.
	 */
	public PeerInfoDeserializer() {
		this(PeerInfo.class);
	}

	/**
	 * Constructor with class type.
	 *
	 * @param vc the class type
	 */
	public PeerInfoDeserializer(Class<?> vc) {
		super(vc);
	}

	/**
	 * Deserializes the {@link PeerInfo}.
	 *
	 * @param p   the parser
	 * @param ctx the context
	 * @return the deserialized peer info
	 * @throws IOException if deserialization fails
	 */
	@Override
	public PeerInfo deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
		if (p.currentToken() != JsonToken.START_OBJECT)
			throw MismatchedInputException.from(p, PeerInfo.class, "Invalid PeerInfo, should be an object");

		final boolean binaryFormat = DataFormat.isBinary(p);

		Id publicKey = null;
		byte[] nonce = null;
		int sequenceNumber = 0;
		Id nodeId = null;
		byte[] nodeSig = null;
		byte[] signature = null;
		long fingerprint = 0;
		String endpoint = null;
		byte[] extraData = null;

		while (p.nextToken() != JsonToken.END_OBJECT) {
			String fieldName = p.currentName();
			JsonToken token = p.nextToken();
			switch (fieldName) {
				case "id":
					if (token != JsonToken.VALUE_NULL)
						publicKey = binaryFormat || token != JsonToken.VALUE_STRING ?
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
				case "o":
					if (token != JsonToken.VALUE_NULL)
						nodeId = binaryFormat || token != JsonToken.VALUE_STRING ?
								Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());
					break;
				case "os":
					if (token != JsonToken.VALUE_NULL)
						nodeSig = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
					break;
				case "sig":
					if (token != JsonToken.VALUE_NULL)
						signature = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
					break;
				case "f":
					if (token != JsonToken.VALUE_NULL)
						fingerprint = p.getLongValue();
					break;
				case "e":
					if (p.currentToken() != JsonToken.VALUE_NULL)
						endpoint = p.getValueAsString();
					break;
				case "ex":
					if (p.currentToken() != JsonToken.VALUE_NULL)
						extraData = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
					break;
				default:
					p.skipChildren();
			}
		}

		// peer id is omitted, should retrieve it from the context
		if (publicKey == null) {
			publicKey = (Id) ctx.getAttribute(PeerInfo.ATTRIBUTE_PEER_ID);
			if (publicKey == null)
				throw MismatchedInputException.from(p, Id.class, "Invalid PeerInfo: peer id can not be null");
		}

		return PeerInfo.of(publicKey, nonce, sequenceNumber, nodeId, nodeSig, signature, fingerprint, endpoint, extraData);
	}
}