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
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.Version;
import io.bosonnetwork.kademlia.Constants;
import io.bosonnetwork.utils.Json;

@JsonDeserialize(using = Message2.Deserializer.class)
@JsonSerialize(using = Message2.Serializer.class)
public class Message2<T> {
	protected static final Object ATTR_NODE_ID = new Object();

	// The DHT node id of the message sender
	private Id id;

	private final Type type;
	private final Method method;
	// Transaction id, should be non-zero integer.
	private final int txid;
	private final T body;
	private final int version;

	// Source address of the inbound message (where it came from)
	// or
	// Destination address of the outbound message (where it is being sent)
	private InetSocketAddress remoteAddr;
	// Source node ID for inbound messages (the node the message came from)
	// or
	// Destination node ID for outbound messages (the node the message is being sent to)
	private Id remoteId;

	public enum Method {
		UNKNOWN(0x00, JsonNode.class, JsonNode.class),
		PING(0x01, Void.class, Void.class),
		FIND_NODE(0x02, FindNodeRequest.class, FindNodeResponse.class),
		ANNOUNCE_PEER(0x03, AnnouncePeerRequest.class, Void.class),
		FIND_PEER(0x04, FindPeerRequest.class, FindPeerResponse.class),
		STORE_VALUE(0x05, StoreValueRequest.class, Void.class),
		FIND_VALUE(0x06, FindValueRequest.class, FindValueResponse.class);

		private static final Method[] VALUES = values();
		private static final int MASK = 0x0000001F;

		private final int value;
		private final Class<?> requestBodyClass;
		private final Class<?> responseBodyClass;

		Method(int value, Class<?> requestBodyClass, Class<?> responseBodyClass) {
			this.value = value;
			this.requestBodyClass = requestBodyClass;
			this.responseBodyClass = responseBodyClass;
		}

		public int value() {
			return value;
		}

		public static Method valueOf(int value) {
			int pos = value & MASK;
			if (pos < VALUES.length)
				return VALUES[pos];

			throw new IllegalArgumentException("Invalid method: " + value);
		}

		public Class<?> bodyClassOf(Type type) {
			if (type == Type.REQUEST)
				return requestBodyClass;
			else if (type == Type.RESPONSE)
				return responseBodyClass;
			else
				return Error.class;
		}
	}

	public enum Type {
		ERROR(0x00, "e"),
		REQUEST(0x20, "q"),
		RESPONSE(0x40, "r");

		private static final Type[] VALUES = values();
		private static final int MASK = 0x000000E0;
		private final int value;
		private final String bodyFieldName;

		Type(int value, String bodyFieldName) {
			this.value = value;
			this.bodyFieldName = bodyFieldName;
		}

		public int value() {
			return value;
		}

		public String bodyFieldName() {
			return bodyFieldName;
		}

		public static Type valueOf(int value) {
			int pos = (value & MASK) >> 5;
			if (pos < VALUES.length)
				return VALUES[pos];

			throw new IllegalArgumentException("Invalid Type: " + value);
		}
	}

	public interface Body {
		Type getType();
	}

	protected Message2(Type type, Method method, int txid, T body, int version) {
		this.type = type;
		this.method = method;
		this.txid = txid;
		this.body = body;
		this.version = version;
	}

	protected Message2(Type type, Method method, int txid, T body) {
		this(type, method, txid, body, Constants.VERSION);
	}

	protected int getCompositeType() {
		return type.value() | method.value();
	}

	public Type getType() {
		return type;
	}

	public Method getMethod() {
		return method;
	}

	public Id getId() {
		return id;
	}

	public void setId(Id id) {
		this.id = id;
	}

	public int getTxid() {
		return txid;
	}

	public T getBody() {
		return body;
	}

	public <B> B getBody(Class<B> clazz) {
		if (clazz.isInstance(body))
			return clazz.cast(body);

		throw new IllegalArgumentException("Body is not an instance of " + clazz);
	}

	public int getVersion() {
		return version;
	}

	public String getReadableVersion() {
		return Version.toString(version);
	}

	public InetSocketAddress getRemoteAddress() {
		return remoteAddr;
	}

	public Id getRemoteId() {
		return remoteId;
	}

	public void setRemote(Id id, InetSocketAddress address) {
		this.remoteId = id;
		this.remoteAddr = address;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, type, method, txid, body, version);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof Message2<?> that)
			return Objects.equals(id, that.id)
					&& type == that.type
					&& method == that.method
					&& txid == that.txid
					&& Objects.equals(body, that.body)
					&& version == that.version;

		return false;
	}

	private static final ObjectReader cborReader = Json.cborMapper().readerFor(Message2.class);
	private static final ObjectWriter cborWriter = Json.cborMapper().writerFor(Message2.class);
	private static final ObjectReader jsonReader = Json.objectMapper().readerFor(Message2.class);
	private static final ObjectWriter jsonWriter = Json.objectMapper().writerFor(Message2.class);

	// ndodeId -Source node ID, required to correctly deserialize inbound messages
	public static Message2<?> parse(byte[] bytes, Id nodeId) {
		try {
			/*
			return nodeId == null ?
					Json.cborMapper().readValue(bytes, Message2.class) :
					Json.cborMapper().reader(Json.JsonContext.shared(ATTR_NODE_ID, nodeId)).readValue(bytes, Message2.class);
			 */
			return nodeId == null ?
					cborReader.readValue(bytes) :
					cborReader.with(Json.JsonContext.perCall(ATTR_NODE_ID, nodeId)).readValue(bytes);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid CBOR data for Message", e);
		}
	}

	public static Message2<?> parse(String json) {
		return parse(json, null);
	}

	// nodeId -Source node ID, required to correctly deserialize inbound messages
	public static Message2<?> parse(String json, Id nodeId) {
		try {
			/*
			return nodeId == null ?
					Json.objectMapper().readValue(json, Message2.class) :
					Json.objectMapper().reader(Json.JsonContext.shared(ATTR_NODE_ID, nodeId)).readValue(json, Message2.class);
			 */
			return nodeId == null ?
					jsonReader.readValue(json) :
					jsonReader.with(Json.JsonContext.perCall(ATTR_NODE_ID, nodeId)).readValue(json);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid JSON data for Message", e);
		}
	}

	public static Message2<?> parse(byte[] bytes) {
		return parse(bytes, null);
	}

	public byte[] toBytes() {
		try {
			//return Json.cborMapper().writeValueAsBytes(this);
			return cborWriter.writeValueAsBytes(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Message is not serializable", e);
		}
	}

	public String toJson() {
		try {
			// return Json.objectMapper().writeValueAsString(this);
			return jsonWriter.writeValueAsString(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Message is not serializable", e);
		}
	}

	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder();
		repr.append('[').append(method).append('/').append(type).append(']')
			.append("id: ").append(id)
			.append(", txid: ").append(txid);
		if (body != null)
			repr.append(", body: ").append(Json.toString(body));

		if (version != 0)
			repr.append(", version: ").append(Version.toString(version));

		if (remoteId != null && remoteAddr != null) {
			if (remoteId.equals(id))
				repr.append(", from: ").append(remoteAddr);
			else
				repr.append(", to: ").append(remoteId).append("/").append(remoteAddr);
		}

		return repr.toString();
	}

	public static Message2<Void> pingRequest(int txid) {
		return new Message2<>(Type.REQUEST, Method.PING, txid, null);
	}

	public static Message2<Void> pingResponse(int txid) {
		return new Message2<>(Type.RESPONSE, Method.PING, txid, null);
	}

	public static Message2<FindNodeRequest> findNodeRequest(int txid, Id target, boolean want4, boolean want6, boolean wantToken) {
		return new Message2<>(Type.REQUEST, Method.FIND_NODE, txid, new FindNodeRequest(target, want4, want6, wantToken));
	}

	public static Message2<FindNodeResponse> findNodeResponse(int txid, List<NodeInfo> n4, List<NodeInfo> n6, int token) {
		return new Message2<>(Type.RESPONSE, Method.FIND_NODE, txid, new FindNodeResponse(n4, n6, token));
	}

	public static Message2<FindPeerRequest> findPeerRequest(int txid, Id target, boolean want4, boolean want6) {
		return new Message2<>(Type.REQUEST, Method.FIND_PEER, txid, new FindPeerRequest(target, want4, want6));
	}

	public static Message2<FindPeerResponse> findPeerResponse(int txid, List<NodeInfo> n4, List<NodeInfo> n6, List<PeerInfo> peers) {
		return new Message2<>(Type.RESPONSE, Method.FIND_PEER, txid, new FindPeerResponse(n4, n6, peers));
	}

	public static Message2<AnnouncePeerRequest> announcePeerRequest(int txid, PeerInfo peer, int token) {
		return new Message2<>(Type.REQUEST, Method.ANNOUNCE_PEER, txid, new AnnouncePeerRequest(peer, token));
	}

	public static Message2<Void> announcePeerResponse(int txid) {
		return new Message2<>(Type.RESPONSE, Method.ANNOUNCE_PEER, txid, null);
	}

	public static Message2<FindValueRequest> findValueRequest(int txid, Id target, boolean want4, boolean want6, int sequenceNumber) {
		return new Message2<>(Type.REQUEST, Method.FIND_VALUE, txid, new FindValueRequest(target, want4, want6, sequenceNumber));
	}

	public static Message2<FindValueResponse> findValueResponse(int txid, List<NodeInfo> n4, List<NodeInfo> n6, Value value) {
		return new Message2<>(Type.RESPONSE, Method.FIND_VALUE, txid, new FindValueResponse(n4, n6, value));
	}

	public static Message2<StoreValueRequest> storeValueRequest(int txid, Value value, int token, int expectedSequenceNumber) {
		return new Message2<>(Type.REQUEST, Method.STORE_VALUE, txid, new StoreValueRequest(value, token, expectedSequenceNumber));
	}

	public static Message2<Void> storeValueResponse(int txid) {
		return new Message2<>(Type.RESPONSE, Method.STORE_VALUE, txid, null);
	}

	public static Message2<Error> error(Method method, int txid, int code, String message) {
		return new Message2<>(Type.ERROR, method, txid, new Error(code, message));
	}

	@SuppressWarnings("rawtypes")
	static class Serializer extends StdSerializer<Message2> {
		private static final long serialVersionUID = -5377850531790575309L;

		public Serializer() {
			this(Message2.class);
		}

		public Serializer(Class<Message2> vc) {
			super(vc);
		}

		@Override
		public void serialize(Message2 value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			gen.writeStartObject();
			gen.writeNumberField("y", value.getCompositeType());
			gen.writeNumberField("t", value.getTxid());
			if (value.getBody() != null)
				gen.writeObjectField(value.getType().bodyFieldName(), value.getBody());
			if (value.getVersion() != 0)
				gen.writeNumberField("v", value.getVersion());
			gen.writeEndObject();
		}
	}

	static class Deserializer extends StdDeserializer<Message2<?>> {
		private static final long serialVersionUID = -46020275686127311L;

		public Deserializer() {
			this(Message2.class);
		}

		public Deserializer(Class<?> vc) {
			super(vc);
		}

		@Override
		public Message2<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
			if (p.currentToken() != JsonToken.START_OBJECT)
				throw ctxt.wrongTokenException(p, Message2.class, JsonToken.START_ARRAY,
						"Invalid Message: should be an object");

			Type type = null;
			Method method = null;
			int txid = 0;
			int version = 0;

			Body body = null;
			Class<?> bodyClass = null;

			while (p.nextToken() != JsonToken.END_OBJECT) {
				String fieldName = p.getCurrentName();
				p.nextToken();
				switch (fieldName) {
				case "y": {
					int value = p.getIntValue();
					type = Type.valueOf(value);
					method = Method.valueOf(value);
					bodyClass = method.bodyClassOf(type);
					break;
				}
				case "t":
					txid = p.getIntValue();
					if (txid == 0)
						throw ctxt.weirdNumberException(txid, Integer.class,
								"Invalid `[t]xid` field: should be non-zero integer");
					break;

				case "q":
				case "r":
					if (type == null || method == null) {
						ctxt.reportInputMismatch(Message2.class, "Not seen 'y' field before + '" + fieldName + "' field");
						return null; // should never here
					}

					if (!type.bodyFieldName().equals(fieldName))
						throw ctxt.weirdKeyException(bodyClass, fieldName, "Invalid '" + fieldName + "' field for " + type.name() + " message");

					if (bodyClass == Void.class)
						throw ctxt.weirdKeyException(bodyClass, fieldName, "Invalid '" + fieldName + "' field for request: " + method);

					body = (Body) ctxt.readValue(p, bodyClass);
					break;

				case "e":
					if (type != Type.ERROR)
						throw ctxt.weirdKeyException(Error.class, "e", "Invalid 'e' field for non-error message");

					body = p.readValueAs(Error.class);
					break;

				case "v":
					version = p.getIntValue();
					break;

				default:
					p.skipChildren();
				}
			}

			if (txid == 0)
				ctxt.reportInputMismatch(Message2.class, "Missing '[t]xid' field");

			return new Message2<>(type, method, txid, body, version);
		}
	}
}