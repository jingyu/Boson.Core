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
import io.bosonnetwork.PeerInfo;

/**
 * Serializer for {@link PeerInfo} objects.
 * <p>
 * Encodes PeerInfo as a 6-element array: [peerId, nodeId, originNodeId, port, alternativeURI, signature].
 * In binary formats, ids and signature are written as Base64-encoded binary; in text formats, ids are Base58 strings.
 * Special behavior: the peerId can be omitted if the context attribute
 * {@link io.bosonnetwork.PeerInfo#ATTRIBUTE_OMIT_PEER_ID} is set.
 */
public class PeerInfoSerializer extends StdSerializer<PeerInfo> {
	private static final long serialVersionUID = -2372725165793659632L;

	/**
	 * Default constructor.
	 */
	public PeerInfoSerializer() {
		this(PeerInfo.class);
	}

	/**
	 * Constructor with class type.
	 *
	 * @param t the class type
	 */
	public PeerInfoSerializer(Class<PeerInfo> t) {
		super(t);
	}

	/**
	 * Serializes the {@link PeerInfo}.
	 *
	 * @param value    the peer info to serialize
	 * @param gen      the generator
	 * @param provider the provider
	 * @throws IOException if serialization fails
	 */
	@Override
	public void serialize(PeerInfo value, JsonGenerator gen, SerializerProvider provider) throws IOException {
		final boolean binaryFormat = DataFormat.isBinary(gen);

		gen.writeStartObject();

		// omit peer id?
		final Boolean attr = (Boolean) provider.getAttribute(PeerInfo.ATTRIBUTE_OMIT_PEER_ID);
		final boolean omitPeerId = attr != null && attr;

		if (!omitPeerId) {
			if (binaryFormat) {
				gen.writeFieldName("id");
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.getId().bytes(), 0, Id.BYTES);
			} else {
				gen.writeStringField("id", value.getId().toBase58String());
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

		if (value.getNodeId() != null) {
			if (binaryFormat) {
				gen.writeFieldName("o");
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.getNodeId().bytes(), 0, Id.BYTES);
			} else {
				gen.writeStringField("o", value.getNodeId().toBase58String());
			}

			binary = value.getNodeSignature();
			if (binary != null) {
				gen.writeFieldName("os");
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, binary, 0, binary.length);
			}
		}

		// signature
		binary = value.getSignature();
		if (binary != null) {
			gen.writeFieldName("sig");
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, binary, 0, binary.length);
		}

		if (value.getFingerprint() != 0)
			gen.writeNumberField("f", value.getFingerprint());

		gen.writeStringField("e", value.getEndpoint());

		binary = value.getExtraData();
		if (binary != null) {
			gen.writeFieldName("ex");
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, binary, 0, binary.length);
		}

		gen.writeEndObject();
	}
}