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

package io.bosonnetwork.kademlia.protocol;

import java.io.IOException;
import java.util.Objects;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import io.bosonnetwork.Id;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.json.internal.DataFormat;

@JsonSerialize(using = AnnouncePeerRequest.Serializer.class)
@JsonDeserialize(using = AnnouncePeerRequest.Deserializer.class)
public class AnnouncePeerRequest implements Request {
	private final int token;
	private final int expectedSequenceNumber;
	private final PeerInfo peer;

	public AnnouncePeerRequest(PeerInfo peer, int token, int expectedSequenceNumber) {
		this.token = token;
		this.expectedSequenceNumber = expectedSequenceNumber;
		this.peer = peer;
	}

	public int getToken() {
		return token;
	}

	public int getExpectedSequenceNumber() {
		return expectedSequenceNumber;
	}

	public PeerInfo getPeer() {
		return peer;
	}

	@Override
	public int hashCode() {
		return Objects.hash(token, peer);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof AnnouncePeerRequest that)
			return this.token == that.token && this.peer.equals(that.peer);

		return false;
	}

	static class Serializer extends StdSerializer<AnnouncePeerRequest> {
		private static final long serialVersionUID = -3471421981677027622L;

		public Serializer() {
			this(AnnouncePeerRequest.class);
		}

		public Serializer(Class<AnnouncePeerRequest> t) {
			super(t);
		}

		@Override
		public void serialize(AnnouncePeerRequest value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			boolean binaryFormat = DataFormat.isBinary(gen);

			gen.writeStartObject();
			gen.writeNumberField("tok", value.token);

			if (value.expectedSequenceNumber >= 0)
				gen.writeNumberField("cas", value.expectedSequenceNumber);

			PeerInfo peer = value.peer;

			if (binaryFormat) {
				gen.writeFieldName("t");
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, peer.getId().bytes(), 0, Id.BYTES);
			} else {
				gen.writeStringField("t", peer.getId().toBase58String());
			}

			byte[] nonce = peer.getNonce();
			gen.writeFieldName("n");
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, nonce, 0, nonce.length);

			if (peer.getSequenceNumber() > 0)
				gen.writeNumberField("seq", peer.getSequenceNumber());

			if (peer.isAuthenticated()) {
				if (binaryFormat) {
					gen.writeFieldName("o");
					gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, peer.getNodeId().bytes(), 0, Id.BYTES);
				} else {
					gen.writeStringField("o", value.peer.getNodeId().toBase58String());
				}

				byte[] sig = peer.getNodeSignature();
				gen.writeFieldName("os");
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, sig, 0, sig.length);
			}

			byte[] sig = peer.getSignature();
			gen.writeFieldName("sig");
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, sig, 0, sig.length);

			gen.writeNumberField("f", peer.getFingerprint());
			gen.writeStringField("e", peer.getEndpoint());

			if (peer.hasExtra()) {
				byte[] extra = peer.getExtraData();
				gen.writeFieldName("ex");
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, extra, 0, extra.length);
			}

			gen.writeEndObject();
		}
	}

	static class Deserializer extends StdDeserializer<AnnouncePeerRequest> {
		private static final long serialVersionUID = -1837715448567615081L;

		public Deserializer() {
			this(AnnouncePeerRequest.class);
		}

		public Deserializer(Class<AnnouncePeerRequest> t) {
			super(t);
		}

		@Override
		public AnnouncePeerRequest deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JsonProcessingException {
			if (p.getCurrentToken() != JsonToken.START_OBJECT)
				throw ctxt.wrongTokenException(p, AnnouncePeerRequest.class, JsonToken.START_OBJECT,
						"Invalid AnnouncePeerRequest: should be an object");

			final boolean binaryFormat = DataFormat.isBinary(p);

			int tok = 0;
			int cas = -1;
			Id peerId = null;
			byte[] nonce = null;
			int sequenceNumber = 0;
			Id nodeId = null;
			byte[] nodeSig = null;
			byte[] signature = null;
			long fingerprint = 0;
			String endpoint = null;
			byte[] extraData = null;

			while (p.nextToken() != JsonToken.END_OBJECT) {
				final String fieldName = p.currentName();
				final JsonToken token = p.nextToken();
				switch (fieldName) {
				case "tok":
					tok = p.getIntValue();
					break;
				case "cas":
					cas = p.getIntValue();
					break;
				case "t":
					peerId = binaryFormat ? Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());
					break;
				case "n":
					nonce = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
					break;
				case "seq":
					sequenceNumber = p.getIntValue();
					break;
				case "o":
					if (token != JsonToken.VALUE_NULL)
						nodeId = binaryFormat ? Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());
					break;
				case "os":
					if (token != JsonToken.VALUE_NULL)
						nodeSig = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
					break;
				case "sig":
					signature = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
					break;
				case "f":
					fingerprint = p.getLongValue();
					break;
				case "e":
					endpoint = p.getText();
					break;
				case "ex":
					if (token != JsonToken.VALUE_NULL)
						extraData = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
					break;
				default:
					p.skipChildren();
				}
			}

			return new AnnouncePeerRequest(PeerInfo.of(peerId, nonce, sequenceNumber, nodeId, nodeSig, signature, fingerprint, endpoint, extraData), tok, cas);
		}
	}
}