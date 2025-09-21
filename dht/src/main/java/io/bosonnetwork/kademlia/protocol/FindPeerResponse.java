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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;

@JsonPropertyOrder({"n4", "n6", "tok", "p"})
public class FindPeerResponse extends LookupResponse {
	@JsonProperty("p")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	@JsonSerialize(using = PeersSerializer.class)
	@JsonDeserialize(using = PeersDeserializer.class)
	private final List<PeerInfo> peers;

	@JsonCreator
	protected FindPeerResponse(
			@JsonProperty("n4") List<? extends NodeInfo> nodes4,
			@JsonProperty("n6") List<? extends NodeInfo> nodes6,
			@JsonProperty("p") List<PeerInfo> peers) {
		super(nodes4, nodes6, 0);
		this.peers = peers == null || peers.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(peers);
	}

	protected FindPeerResponse(List<? extends NodeInfo> nodes4, List<? extends NodeInfo> nodes6) {
		this(nodes4, nodes6, null);
	}

	protected FindPeerResponse(List<PeerInfo> peers) {
		this(null, null, peers);
	}

	public boolean hasPeers() {
		return !peers.isEmpty();
	}

	public List<PeerInfo> getPeers() {
		return peers;
	}

	@Override
	public int hashCode() {
		return 0xF1AD9EE2 + super.hashCode() + Objects.hashCode(peers);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof FindPeerResponse that)
			return Objects.equals(peers, that.peers) && super.equals(obj);

		return false;
	}

	private static class PeersSerializer extends StdSerializer<List<PeerInfo>> {
		private static final long serialVersionUID = -8878162342914711108L;

		public PeersSerializer() {
			super((Class<List<PeerInfo>>) null);
		}

		public PeersSerializer(Class<List<PeerInfo>> t) {
			super(t);
		}

		@Override
		public void serialize(List<PeerInfo> value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			final int size = value.size();

			gen.writeStartArray(value, size);

			boolean leadingPeer = true;

			for (int i = 0; i < size; i++) {
				provider.defaultSerializeValue(value.get(i), gen);
				if (leadingPeer && size > 1) {
					leadingPeer = false;
					provider.setAttribute(PeerInfo.ATTRIBUTE_OMIT_PEER_ID, true);
				}
			}

			gen.writeEndArray();
		}

		@Override
		public boolean isEmpty(SerializerProvider provider, List<PeerInfo> value) {
			return value == null || value.isEmpty();
		}
	}

	public static class PeersDeserializer extends StdDeserializer<List<PeerInfo>> {
		private static final long serialVersionUID = -6827233304562559223L;

		public PeersDeserializer() {
			super((Class<?>) null);
		}

		public PeersDeserializer(Class<?> vc) {
			super(vc);
		}

		@Override
		public List<PeerInfo> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
			if (p.currentToken() != JsonToken.START_ARRAY)
				throw ctxt.wrongTokenException(p, List.class, JsonToken.START_ARRAY, "Invalid peers list: should be an array");

			ArrayList<PeerInfo> peers = new ArrayList<>();
			boolean leadingPeer = true;

			final JavaType type = ctxt.constructType(PeerInfo.class);
			while (p.nextToken() != JsonToken.END_ARRAY) {
				final PeerInfo peer = ctxt.readValue(p, type);
				if (leadingPeer) {
					leadingPeer = false;
					ctxt.setAttribute(PeerInfo.ATTRIBUTE_PEER_ID, peer.getId());
				}
				peers.add(peer);
			}

			return peers;
		}
	}
}