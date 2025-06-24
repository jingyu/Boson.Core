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

package io.bosonnetwork.kademlia.messages2;

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
import io.bosonnetwork.utils.Json;

@JsonSerialize(using = AnnouncePeerRequest.Serializer.class)
@JsonDeserialize(using = AnnouncePeerRequest.Deserializer.class)
public class AnnouncePeerRequest implements Request {
	private final int token;
	private final PeerInfo peer;

	public AnnouncePeerRequest(PeerInfo peer, int token) {
		this.token = token;
		this.peer = peer;
	}

	public int getToken() {
		return token;
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
			boolean binaryFormat = Json.isBinaryFormat(gen);

			gen.writeStartObject();
			gen.writeNumberField("tok", value.token);

			if (binaryFormat) {
				gen.writeFieldName("t");
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.peer.getId().bytes(), 0, Id.BYTES);
			} else {
				gen.writeStringField("t", value.peer.getId().toBase58String());
			}

			if (value.peer.isDelegated()) {
				if (binaryFormat) {
					gen.writeFieldName("o");
					gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.peer.getOrigin().bytes(), 0, Id.BYTES);
				} else {
					gen.writeStringField("o", value.peer.getOrigin().toBase58String());
				}
			}

			gen.writeNumberField("p", value.peer.getPort());

			if (value.peer.getAlternativeURL() != null)
				gen.writeStringField("alt", value.peer.getAlternativeURL());

			byte[] sig = value.peer.getSignature();
			gen.writeFieldName("sig");
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, sig, 0, sig.length);

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

			final boolean binaryFormat = Json.isBinaryFormat(p);

			int tok = 0;
			Id peerId = null;
			Id origin = null;
			int port = 0;
			String alternativeURL = null;
			byte[] signature = null;

			Id nodeId = (Id) ctxt.getAttribute(Message2.ATTR_NODE_ID);
			if (nodeId == null)
				ctxt.reportInputMismatch(AnnouncePeerRequest.class, "Missing nodeId attribute in the deserialization context");

			while (p.nextToken() != JsonToken.END_OBJECT) {
				final String fieldName = p.getCurrentName();
				final JsonToken token = p.nextToken();
				switch (fieldName) {
				case "tok":
					tok = p.getIntValue();
					break;
				case "t":
					peerId = binaryFormat ? Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());
					break;
				case "o":
					if (token != JsonToken.VALUE_NULL)
						origin = binaryFormat ? Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());
					break;
				case "p":
					port = p.getIntValue();
					break;
				case "alt":
					if (token != JsonToken.VALUE_NULL)
						alternativeURL = p.getText();
					break;
				case "sig":
					signature = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
					break;
				default:
					p.skipChildren();
				}
			}

			return new AnnouncePeerRequest(PeerInfo.of(peerId, nodeId, origin, port, alternativeURL, signature), tok);
		}
	}
}