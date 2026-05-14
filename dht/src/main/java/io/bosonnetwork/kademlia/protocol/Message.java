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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.net.impl.SocketAddressImpl;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.Version;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.json.JsonContext;
import io.bosonnetwork.kademlia.KadNode;
import io.bosonnetwork.kademlia.rpc.RpcCall;

@JsonPropertyOrder({"y", "t", "q", "r", "e", "v"})
@JsonDeserialize(using = Message.Deserializer.class)
public class Message {
	// The minimum size of message: 10 bytes
	public static final int MIN_BYTES = 10;

	protected static final Object ATTR_NODE_ID = new Object();
	private static final AtomicInteger nextTxId = new AtomicInteger(1);

	// The DHT node id of the message sender
	private Id id;

	private final Type type;
	private final Method method;

	// Transaction id, should be unsigned integer.
	@JsonProperty("t")
	private final long txid;

	private final Object body;

	@JsonProperty("v")
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	private final int version;

	private RpcCall associatedCall;

	// Source address of the inbound message (where it came from)
	// or
	// Destination address of the outbound message (where it is being sent)
	private SocketAddress remoteAddress; // Use Vert.x SocketAddress for efficiency
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

	// Jackson polymorphic deserialization implementation.
	// Benchmark results show it is approximately 1.5x–2x slower than the dedicated Message deserializer.
	// Therefore, the custom deserializer remains the default implementation.
	/*/
	static class MessageTypeResolver extends TypeIdResolverBase {
		@Override
		public JavaType typeFromId(DatabindContext context, String id) {
			Type type;
			Method method;
			try {
				int compositeType = Integer.parseInt(id);
				type = Type.valueOf(compositeType);
				method = Method.valueOf(compositeType);
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Unknown message type: " + id);
			}

			return switch (type) {
				case REQUEST -> switch (method) {
					case UNKNOWN, PING -> context.constructType(Void.class);
					case FIND_NODE -> context.constructType(FindNodeRequest.class);
					case ANNOUNCE_PEER -> context.constructType(AnnouncePeerRequest.class);
					case FIND_PEER -> context.constructType(FindPeerRequest.class);
					case STORE_VALUE -> context.constructType(StoreValueRequest.class);
					case FIND_VALUE -> context.constructType(FindValueRequest.class);
				};

				case RESPONSE -> switch (method) {
					case UNKNOWN, PING, STORE_VALUE, ANNOUNCE_PEER -> context.constructType(Void.class);
					case FIND_NODE -> context.constructType(FindNodeResponse.class);
					case FIND_PEER -> context.constructType(FindPeerResponse.class);
					case FIND_VALUE -> context.constructType(FindValueResponse.class);
				};

				case ERROR -> context.constructType(Error.class);
			};

		}

		@Override
		public String idFromValue(Object value) {
			return "";
		}

		@Override
		public String idFromValueAndType(Object value, Class<?> suggestedType) {
			return "";
		}

		@Override
		public JsonTypeInfo.Id getMechanism() {
			return JsonTypeInfo.Id.CUSTOM;
		}
	}

	@JsonCreator
	protected Message(@JsonProperty(value = "y", required = true)
					  int compositeType,
	                  @JsonProperty(value = "t", required = true)
					  long txid,
	                  @JsonProperty(value = "q")
						  @JsonTypeInfo(
								  use = JsonTypeInfo.Id.CUSTOM,
								  include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
								  property = "y",
								  defaultImpl = Void.class
						  )
						  @JsonTypeIdResolver(MessageTypeResolver.class)
					  Object request,
	                  @JsonProperty(value = "r")
						  @JsonTypeInfo(
								  use = JsonTypeInfo.Id.CUSTOM,
								  include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
								  property = "y",
								  defaultImpl = Void.class
						  )
						  @JsonTypeIdResolver(MessageTypeResolver.class)
					  Object response,
	                  @JsonProperty(value = "e")
					  Error error,
	                  @JsonProperty(value = "v")
					  int version) {
		this.type = Type.valueOf(compositeType);
		switch (this.type) {
			case REQUEST -> {
				if (response != null || error != null)
					throw new IllegalArgumentException("Invalid request message: " + response);
				body = request;
			}
			case RESPONSE -> {
				if (request != null || error != null)
					throw new IllegalArgumentException("Invalid response message: " + response);
				body = response;
			}
			case ERROR -> {
				if (error == null || request != null || response != null)
					throw new IllegalArgumentException("Invalid error message: " + error);
				body = error;
			}
			default -> body = null;
		}


		this.method = Method.valueOf(compositeType);
		this.txid = txid;
		this.version = version;
	}
	*/

	protected Message(Type type, Method method, long txid, Object body, int version) {
		this.type = type;
		this.method = method;
		this.txid = txid;
		this.body = body;
		this.version = version;
	}

	protected Message(Type type, Method method, long txid, Object body) {
		this(type, method, txid, body, KadNode.VERSION);
	}

	@JsonProperty("y")
	protected int getCompositeType() {
		return type.value() | method.value();
	}

	public Type getType() {
		return type;
	}

	public Method getMethod() {
		return method;
	}

	public boolean isRequest() {
		return type == Type.REQUEST;
	}

	@JsonProperty("q")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	protected Request getRequestBody() {
		return type == Type.REQUEST ? (Request) body : null;
	}

	public boolean isResponse() {
		return type == Type.RESPONSE;
	}

	@JsonProperty("r")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	protected Response getResponseBody() {
		return type == Type.RESPONSE ? (Response) body : null;
	}

	public boolean isError() {
		return type == Type.ERROR;
	}

	@JsonProperty("e")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public Error getError() {
		return type == Type.ERROR ? (Error) body : null;
	}

	public Id getId() {
		return id;
	}

	public void setId(Id id) {
		this.id = id;
	}

	public long getTxid() {
		return txid;
	}

	@SuppressWarnings("unchecked")
	public <T> T getBody() {
		return (T) body;
	}

	public int getVersion() {
		return version;
	}

	public String getReadableVersion() {
		return Version.toString(version);
	}

	public Message setAssociatedCall(RpcCall associatedCall) {
		this.associatedCall = associatedCall;
		return this;
	}

	public RpcCall getAssociatedCall() {
		return associatedCall;
	}

	public Id getRemoteId() {
		return remoteId;
	}

	public SocketAddress getRemoteAddress() {
		return remoteAddress;
	}

	public InetAddress getRemoteIpAddress() {
		SocketAddressImpl sai = (SocketAddressImpl) remoteAddress;
		return sai.ipAddress();
	}

	public int getRemotePort() {
		return remoteAddress.port();
	}

	public Message setRemote(Id id, SocketAddress address) {
		this.remoteId = id;
		this.remoteAddress = address;
		return this;
	}

	public Message setRemote(Id id, InetAddress address, int port) {
		this.remoteId = id;
		this.remoteAddress = SocketAddress.inetSocketAddress(new InetSocketAddress(address, port));
		return this;
	}

	public Message setRemote(Id id, InetSocketAddress address) {
		this.remoteId = id;
		this.remoteAddress = SocketAddress.inetSocketAddress(address);
		return this;
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, type, method, txid, body, version);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof Message that)
			return Objects.equals(id, that.id)
					&& type == that.type
					&& method == that.method
					&& txid == that.txid
					&& Objects.equals(body, that.body)
					&& version == that.version;

		return false;
	}

	private static final ObjectReader cborReader = Json.cborMapper().readerFor(Message.class);
	private static final ObjectWriter cborWriter = Json.cborMapper().writerFor(Message.class);
	private static final ObjectReader jsonReader = Json.objectMapper().readerFor(Message.class);
	private static final ObjectWriter jsonWriter = Json.objectMapper().writerFor(Message.class);

	// nodeId -Source node ID, required to correctly deserialize inbound messages
	public static Message parse(byte[] bytes, Id nodeId) {
		try {
			return nodeId == null ?
					cborReader.readValue(bytes) :
					cborReader.with(JsonContext.perCall(ATTR_NODE_ID, nodeId)).readValue(bytes);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid CBOR data for Message", e);
		}
	}

	public static Message parse(String json) {
		return parse(json, null);
	}

	// nodeId -Source node ID, required to correctly deserialize inbound messages
	public static Message parse(String json, Id nodeId) {
		try {
			return nodeId == null ?
					jsonReader.readValue(json) :
					jsonReader.with(JsonContext.perCall(ATTR_NODE_ID, nodeId)).readValue(json);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid JSON data for Message", e);
		}
	}

	public static Message parse(byte[] bytes) {
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

		if (remoteId != null && remoteAddress != null) {
			if (remoteId.equals(id))
				repr.append(", from: ").append(remoteAddress);
			else
				repr.append(", to: ").append(remoteId).append("@").append(remoteAddress);
		}

		return repr.toString();
	}

	protected static void setTxidBase(int base) {
		nextTxId.set(base);
	}

	private static long nextTxid() {
		return Integer.toUnsignedLong(nextTxId.getAndIncrement());
	}

	public static Message message(Type type, Method method, long txid, Object body) {
		return new Message(type, method, txid, body);
	}

	public static Message pingRequest() {
		return new Message(Type.REQUEST, Method.PING, nextTxid(), null);
	}

	public static Message pingResponse(long txid) {
		return new Message(Type.RESPONSE, Method.PING, txid, null);
	}

	public static Message findNodeRequest(Id target, boolean want4, boolean want6) {
		return new Message(Type.REQUEST, Method.FIND_NODE, nextTxid(), new FindNodeRequest(target, want4, want6, false));
	}

	public static Message findNodeRequest(Id target, boolean want4, boolean want6, boolean wantToken) {
		return new Message(Type.REQUEST, Method.FIND_NODE, nextTxid(), new FindNodeRequest(target, want4, want6, wantToken));
	}

	public static Message findNodeResponse(long txid, List<? extends NodeInfo> n4, List<? extends NodeInfo> n6, int token) {
		return new Message(Type.RESPONSE, Method.FIND_NODE, txid, new FindNodeResponse(n4, n6, token));
	}

	public static Message findPeerRequest(Id target, boolean want4, boolean want6, int expectedSequenceNumber, int expectedCount) {
		return new Message(Type.REQUEST, Method.FIND_PEER, nextTxid(), new FindPeerRequest(target, want4, want6, expectedSequenceNumber, expectedCount));
	}

	public static Message findPeerResponse(long txid, List<? extends NodeInfo> n4, List<? extends NodeInfo> n6) {
		return new Message(Type.RESPONSE, Method.FIND_PEER, txid, new FindPeerResponse(n4, n6));
	}

	public static Message findPeerResponse(long txid, List<PeerInfo> peers) {
		return new Message(Type.RESPONSE, Method.FIND_PEER, txid, new FindPeerResponse(peers));
	}

	protected static Message findPeerResponse(long txid, List<? extends NodeInfo> n4, List<? extends NodeInfo> n6, List<PeerInfo> peers) {
		return new Message(Type.RESPONSE, Method.FIND_PEER, txid, new FindPeerResponse(n4, n6, peers));
	}

	public static Message announcePeerRequest(PeerInfo peer, int token, int expectedSequenceNumber) {
		return new Message(Type.REQUEST, Method.ANNOUNCE_PEER, nextTxid(), new AnnouncePeerRequest(peer, token, expectedSequenceNumber));
	}

	public static Message announcePeerResponse(long txid) {
		return new Message(Type.RESPONSE, Method.ANNOUNCE_PEER, txid, null);
	}

	public static Message findValueRequest(Id target, boolean want4, boolean want6, int sequenceNumber) {
		return new Message(Type.REQUEST, Method.FIND_VALUE, nextTxid(), new FindValueRequest(target, want4, want6, sequenceNumber));
	}

	public static Message findValueResponse(long txid, List<? extends NodeInfo> n4, List<? extends NodeInfo> n6) {
		return new Message(Type.RESPONSE, Method.FIND_VALUE, txid, new FindValueResponse(n4, n6));
	}

	public static Message findValueResponse(long txid, Value value) {
		return new Message(Type.RESPONSE, Method.FIND_VALUE, txid, new FindValueResponse(value));
	}

	protected static Message findValueResponse(long txid, List<? extends NodeInfo> n4, List<? extends NodeInfo> n6, Value value) {
		return new Message(Type.RESPONSE, Method.FIND_VALUE, txid, new FindValueResponse(n4, n6, value));
	}

	public static Message storeValueRequest(Value value, int token, int expectedSequenceNumber) {
		return new Message(Type.REQUEST, Method.STORE_VALUE, nextTxid(), new StoreValueRequest(value, token, expectedSequenceNumber));
	}

	public static Message storeValueResponse(long txid) {
		return new Message(Type.RESPONSE, Method.STORE_VALUE, txid, null);
	}

	public static Message error(Method method, long txid, int code, String message) {
		return new Message(Type.ERROR, method, txid, new Error(code, message));
	}

	static class Deserializer extends StdDeserializer<Message> {
		private static final long serialVersionUID = -46020275686127311L;

		@SuppressWarnings("unused")
		public Deserializer() {
			this(Message.class);
		}

		public Deserializer(Class<?> vc) {
			super(vc);
		}

		@Override
		public Message deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
			if (p.currentToken() != JsonToken.START_OBJECT)
				throw ctxt.wrongTokenException(p, Message.class, JsonToken.START_ARRAY,
						"Invalid Message: should be an object");

			Type type = null;
			Method method = null;
			long txid = 0;
			int version = 0;

			Body body = null;
			Class<?> bodyClass = null;

			while (p.nextToken() != JsonToken.END_OBJECT) {
				String fieldName = p.currentName();
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
						txid = p.getLongValue();
						if (txid == 0)
							throw ctxt.weirdNumberException(txid, Integer.class,
									"Invalid `[t]xid` field: should be non-zero integer");
						break;

					case "q":
					case "r":
						if (type == null || method == null) {
							ctxt.reportInputMismatch(Message.class, "Not seen 'y' field before + '" + fieldName + "' field");
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
				ctxt.reportInputMismatch(Message.class, "Missing '[t]xid' field");

			return new Message(type, method, txid, body, version);
		}
	}
}