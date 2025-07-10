/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

package io.bosonnetwork.kademlia.protocol.deprecated;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;

import io.bosonnetwork.Id;
import io.bosonnetwork.Version;
import io.bosonnetwork.kademlia.RPCCall;
import io.bosonnetwork.kademlia.RPCServer;
import io.bosonnetwork.utils.Functional.ThrowingSupplier;
import io.bosonnetwork.utils.Json;

/**
 * @hidden
 */
public abstract class OldMessage {
	private static final int READ_LIMIT = 2048;

	private static final int TYPE_MASK = 0x000000E0;
	private static final int METHOD_MASK = 0x0000001F;

	public static final int MIN_SIZE = 13;
	protected static final int BASE_SIZE = 20;

	private final int type;
	private Id id;
	private int txid;
	private int version;

	private InetSocketAddress origin;
	private InetSocketAddress remoteAddr;
	private Id remoteId;

	private RPCServer server;
	private RPCCall associatedCall;

	/**
	 * @hidden
	 */
	public enum Method {
		UNKNOWN(0x00),
		PING(0x01),
		FIND_NODE(0x02),
		ANNOUNCE_PEER(0x03),
		FIND_PEER(0x04),
		STORE_VALUE(0x05),
		FIND_VALUE(0x06);

		private final int value;

		Method(int value) {
			this.value = value;
		}

		public int value() {
			return value;
		}

		@Override
		public String toString() {
			return name().toLowerCase();
		}

		public static Method valueOf(int value) {
			return switch (value & METHOD_MASK) {
				case 0x01 -> PING;
				case 0x02 -> FIND_NODE;
				case 0x03 -> ANNOUNCE_PEER;
				case 0x04 -> FIND_PEER;
				case 0x05 -> STORE_VALUE;
				case 0x06 -> FIND_VALUE;
				case 0x00 -> UNKNOWN;
				default -> throw new IllegalArgumentException("Invalid method: " + value);
			};
		}
	}

	/**
	 * @hidden
	 */
	public enum Type {
		REQUEST(0x20),
		RESPONSE(0x40),
		ERROR(0x00);

		private final int value;

		Type(int value) {
			this.value = value;
		}

		public int value() {
			return value;
		}

		@Override
		public String toString() {
			if (value == REQUEST.value) return "q";
			else if (value == RESPONSE.value) return "r";
			else if (value == ERROR.value) return "e";
			else return null;
		}

		public static Type valueOf(int value) {
			return switch (value & TYPE_MASK) {
				case 0x20 -> REQUEST;
				case 0x40 -> RESPONSE;
				case 0x00 -> ERROR;
				default -> throw new IllegalArgumentException("Invalid Type: " + value);
			};
		}
	}

	protected OldMessage(Type type, Method method, int txid) {
		this.type = type.value | method.value;
		this.txid = txid;
		this.version = 0;
	}

	protected OldMessage(Type type, Method method) {
		this.type = type.value | method.value;
	}

	public Type getType() {
		return Type.valueOf(type);
	}

	public Method getMethod() {
		return Method.valueOf(type);
	}

	public void setId(Id id) {
		this.id = id;
	}

	public Id getId() {
		return id;
	}

	public void setTxid(int txid) {
		assert(txid != 0);
		this.txid = txid;
	}

	public int getTxid() {
		return txid;
	}

	public void setVersion(int version) {
		this.version = version;
	}

	public int getVersion() {
		return version;
	}

	public String getReadableVersion() {
		return Version.toString(version);
	}

	public InetSocketAddress getOrigin() {
		return origin;
	}

	public void setOrigin(InetSocketAddress local) {
		this.origin = local;
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

	public void setServer(RPCServer server) {
		this.server = server;
	}

	public RPCServer getServer() {
		return server;
	}

	public void setAssociatedCall(RPCCall call) {
		this.associatedCall = call;
	}

	public RPCCall getAssociatedCall() {
		return associatedCall;
	}

	public byte[] serialize() {
		ByteArrayOutputStream out = new ByteArrayOutputStream(1500);
		try {
			serialize(out);
		} catch (MessageException e) {
			throw new RuntimeException("INTERNAL ERROR: should never happen.");
		}
		return out.toByteArray();
	}

	public void serialize(OutputStream out) throws MessageException {
		try {
			CBORGenerator gen = Json.cborFactory().createGenerator(out);
			serializeInternal(gen);
			gen.close();
		} catch (IOException e) {
			throw new MessageException("Serialize message failed.", e);
		}
	}

	protected void serialize(JsonGenerator gen) throws MessageException, IOException {
	}

	// According to my tests, streaming serialization is about 30x faster than the object mapping
	private void serializeInternal(JsonGenerator gen) throws MessageException, IOException {
		gen.writeStartObject();

		gen.writeNumberField("y", type);

		// Id will not include in the encrypted message.
		/*
		if (id != null) {
			gen.writeFieldName("i");
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, id.bytes(), 0, Id.BYTES);
		}
		*/

		gen.writeNumberField("t", txid);

		serialize(gen);

		if (version != 0)
			gen.writeNumberField("v", version);

		gen.writeEndObject();
	}

	public static OldMessage parse(byte[] data) throws MessageException {
		checkArgument(data != null && data.length >= MIN_SIZE, "Invalid data");

		return parse(() -> (Json.cborFactory().createParser(data)));
	}

	public static OldMessage parse(InputStream in) throws MessageException {
		checkArgument(in.markSupported(), "Input stream shoud support mark()");
		in.mark(READ_LIMIT);

		return parse(() -> {
			in.reset();
			in.mark(READ_LIMIT);
			return Json.cborFactory().createParser(in);
		});
	}

	// According to my tests, streaming deserialization is about 25x faster than the object mapping
	private static OldMessage parse(ThrowingSupplier<CBORParser, IOException> ps) throws MessageException {
		OldMessage msg = null;

		try {
			int typeCode = Integer.MAX_VALUE;

			CBORParser parser = ps.get();
			int depth = 0;
			while (true) {
				JsonToken tok = parser.nextToken();

				if (tok == JsonToken.START_OBJECT) {
					depth++;
				} else if (tok == JsonToken.END_OBJECT) {
					if (--depth == 0)
						break;
				}

				String name = parser.currentName();
				if (name != null && name.equals("y")) {
					parser.nextToken();
					typeCode = parser.getIntValue();
					break;
				}
			}
			parser.close();

			if (typeCode == Integer.MAX_VALUE)
				throw new MessageException("Missing message type");

			Type type;
			try {
				type = Type.valueOf(typeCode);
			} catch (IllegalArgumentException e) {
				throw new MessageException("Invalid message type: " + typeCode);
			}

			Method method;
			try {
				method = Method.valueOf(typeCode);
			} catch (IllegalArgumentException e) {
				throw new MessageException("Invalid message type: " + typeCode);
			}

			msg = createMessage(type, method);

			parser = ps.get();
			depth = 0;
			while (true) {
				JsonToken tok = parser.nextToken();

				if (tok == JsonToken.START_OBJECT) {
					depth++;
				} else if (tok == JsonToken.END_OBJECT) {
					if (--depth == 0)
						break;
				} else if (tok == JsonToken.FIELD_NAME) {
					String name = parser.currentName();
					parser.nextToken();
					switch (name) {
					case "y":
						break;

					// Id will not include in the encrypted message.
					/*
					case "i":
						try {
							msg.id = Id.of(parser.getBinaryValue());
						} catch (IllegalArgumentException e) {
							throw new MessageException("Invalid node id for 'i'").setPartialMessage(PartialMessage.of(msg));
						}
						break;
					*/

					case "t":
						msg.txid = parser.getIntValue();
						break;

					case "q":
						if (type != Type.REQUEST)
							throw new MessageException("Invalid " + type.name().toLowerCase() + " message, unknown 'q'").setPartialMessage(PartialMessage.of(msg));
						else
							msg.parse(name, parser);
						break;

					case "r":
						if (type != Type.RESPONSE)
							throw new MessageException("Invalid " + type.name().toLowerCase() + " message, unknown 'r'").setPartialMessage(PartialMessage.of(msg));
						else
							msg.parse(name, parser);
						break;

					case "e":
						if (type != Type.ERROR)
							throw new MessageException("Invalid " + type.name().toLowerCase() + " message, unknown 'e'").setPartialMessage(PartialMessage.of(msg));
						else
							msg.parse(name, parser);
						break;

					case "v":
						msg.version = parser.getIntValue();
						break;
					}
				}
			}

			parser.close();
		} catch (IOException e) {
			throw new MessageException("Parse message failed", e).setPartialMessage(PartialMessage.of(msg));
		}

		return msg;
	}

	protected void parse(String fieldName, CBORParser parser) throws MessageException, IOException {
	}

	private static OldMessage createMessage(Type type, Method method) throws MessageException {
		return switch (type) {
			case REQUEST -> switch (method) {
				case PING -> new PingRequest();
				case FIND_NODE -> new FindNodeRequest();
				case ANNOUNCE_PEER -> new AnnouncePeerRequest();
				case FIND_PEER -> new FindPeerRequest();
				case STORE_VALUE -> new StoreValueRequest();
				case FIND_VALUE -> new FindValueRequest();
				default -> throw new MessageException("Invalid request method.");
			};
			case RESPONSE -> switch (method) {
				case PING -> new PingResponse();
				case FIND_NODE -> new FindNodeResponse();
				case ANNOUNCE_PEER -> new AnnouncePeerResponse();
				case FIND_PEER -> new FindPeerResponse();
				case STORE_VALUE -> new StoreValueResponse();
				case FIND_VALUE -> new FindValueResponse();
				default -> throw new MessageException("Invalid response method.");
			};
			case ERROR -> new ErrorMessage(method);
		};
	}

	public int estimateSize() {
		return BASE_SIZE;
	}

	@Override
	public String toString() {
		StringBuilder b = new StringBuilder(1500);
		b.append("y:").append(getType());
		b.append(",m:").append(getMethod());
		b.append(",i:").append(id);
		b.append(",t:").append(txid);

		toString(b);

		if (version != 0)
			b.append(",v:").append(getReadableVersion());

		return b.toString();
	}

	protected void toString(StringBuilder b) {
	}
}