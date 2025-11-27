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

package io.bosonnetwork.utils;

import java.io.IOException;
import java.net.InetAddress;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.cfg.ContextAttributes;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.vertx.core.json.jackson.DatabindCodec;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.identifier.DIDConstants;

/**
 * Utility class for serialization and deserialization of data to and from JSON, CBOR, and YAML formats.
 * <p>
 * This class provides common methods for encoding and decoding objects using Jackson, with special support for Boson-specific
 * types and extensions. It supports both text-based (JSON, YAML) and binary (CBOR) formats, and registers Jackson modules
 * to handle custom serialization and deserialization of Boson domain objects such as {@link io.bosonnetwork.Id}, {@link io.bosonnetwork.NodeInfo},
 * {@link io.bosonnetwork.PeerInfo}, and {@link io.bosonnetwork.Value}.
 * <p>
 * The utility also provides factory methods for obtaining pre-configured Jackson {@link ObjectMapper}, {@link CBORMapper},
 * and {@link YAMLMapper} instances, as well as methods for pretty-printing, byte encoding, and parsing from various formats.
 * <p>
 * Boson-specific serialization features include:
 * <ul>
 *   <li>Binary and text encoding of IDs and network addresses depending on the chosen format.</li>
 *   <li>Support for ISO8601/RFC3339 date/time formats and epoch milliseconds.</li>
 *   <li>Context-aware serialization using {@link Json.JsonContext} for configuration of serialization details.</li>
 * </ul>
 */
public class Json {
	private static final String BOSON_JSON_MODULE_NAME = "io.bosonnetwork.utils.json.module";

	private static TypeReference<Map<String, Object>> _mapType;

	private static SimpleModule _bosonJsonModule;

	private static JsonFactory _jsonFactory;
	private static CBORFactory _cborFactory;

	private static ObjectMapper _objectMapper;
	private static CBORMapper _cborMapper;
	private static YAMLMapper _yamlMapper;

	private static final TimeZone UTC = TimeZone.getTimeZone("UTC");
	@SuppressWarnings("SpellCheckingInspection")
	private static final String ISO_8601 = "yyyy-MM-dd'T'HH:mm:ss'Z'";
	@SuppressWarnings("SpellCheckingInspection")
	private static final String ISO_8601_WITH_MILLISECONDS = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

	/**
	 * Determines if the provided {@link JsonParser} is operating in a binary format (CBOR).
	 *
	 * @param p the {@code JsonParser} to check
	 * @return {@code true} if the parser is handling a binary format (CBOR), {@code false} otherwise
	 */
	public static boolean isBinaryFormat(JsonParser p) {
		// Now we only sport JSON, CBOR and TOML formats; CBOR is the only binary format
		return p instanceof CBORParser;
	}

	/**
	 * Determines if the provided {@link JsonGenerator} is operating in a binary format (CBOR).
	 *
	 * @param gen the {@code JsonGenerator} to check
	 * @return {@code true} if the generator is handling a binary format (CBOR), {@code false} otherwise
	 */
	public static boolean isBinaryFormat(JsonGenerator gen) {
		// Now we only sport JSON, CBOR and TOML formats; CBOR is the only binary format
		return gen instanceof CBORGenerator;
	}

	/**
	 * Serializer for {@link Id} objects.
	 * <p>
	 * Handles serialization of Ids to either a Base64-encoded binary (for binary formats like CBOR)
	 * or as a string (either W3C DID or Base58, depending on the context attribute
	 * {@link io.bosonnetwork.identifier.DIDConstants#BOSON_ID_FORMAT_W3C}) for text formats (JSON/YAML).
	 */
	static class IdSerializer extends StdSerializer<Id> {
		private static final long serialVersionUID = -1352630613285716899L;

		public IdSerializer() {
			this(Id.class);
		}

		public IdSerializer(Class<Id> t) {
			super(t);
		}

		@Override
		public void serialize(Id value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			Boolean attr = (Boolean) provider.getAttribute(DIDConstants.BOSON_ID_FORMAT_W3C);
			boolean w3cDID = attr != null && attr;
			if (isBinaryFormat(gen))
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.bytes(), 0, Id.BYTES);
			else
				gen.writeString(w3cDID ? value.toDIDString() : value.toBase58String());
		}
	}

	/**
	 * Deserializer for {@link Id} objects.
	 * <p>
	 * Handles decoding from either Base64-encoded binary (for binary formats like CBOR)
	 * or from string (Base58 or W3C DID) for text formats (JSON/YAML).
	 */
	static class IdDeserializer extends StdDeserializer<Id> {
		private static final long serialVersionUID = 8977820243454538303L;

		public IdDeserializer() {
			this(Id.class);
		}

		public IdDeserializer(Class<?> vc) {
			super(vc);
		}

		@Override
		public Id deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
			return isBinaryFormat(p) ? Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());
		}
	}

	/**
	 * Serializer for {@link InetAddress} objects.
	 * <p>
	 * Serializes InetAddress as a Base64-encoded binary value in binary formats (CBOR),
	 * or as a string (IP address or hostname) in text formats (JSON/YAML).
	 */
	static class InetAddressSerializer extends StdSerializer<InetAddress> {
		private static final long serialVersionUID = 618328089579234961L;

		public InetAddressSerializer() {
			this(InetAddress.class);
		}

		public InetAddressSerializer(Class<InetAddress> t) {
			super(t);
		}

		@Override
		public void serialize(InetAddress value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			if (isBinaryFormat(gen)) {
				byte[] addr = value.getAddress();
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, addr, 0, addr.length); // binary ip address
			} else {
				gen.writeString(value.getHostAddress()); // ip address or host name
			}
		}
	}

	/**
	 * Deserializer for {@link InetAddress} objects.
	 * <p>
	 * Decodes either a Base64-encoded binary address (for binary formats like CBOR)
	 * or a string representation (IP address or hostname) for text formats.
	 */
	static class InetAddressDeserializer extends StdDeserializer<InetAddress> {
		private static final long serialVersionUID = -5009935040580375373L;

		public InetAddressDeserializer() {
			this(InetAddress.class);
		}

		public InetAddressDeserializer(Class<?> vc) {
			super(vc);
		}

		@Override
		public InetAddress deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
			return isBinaryFormat(p) ? InetAddress.getByAddress(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) :
					InetAddress.getByName(p.getText());
		}
	}

	/**
	 * Serializer for {@link java.util.Date} objects.
	 * <p>
	 * Handles serialization of Date objects to either ISO8601 string format (for text formats like JSON/YAML)
	 * or to epoch milliseconds (for binary formats like CBOR), depending on the output format.
	 */
	static class DateSerializer extends StdSerializer<Date> {
		private static final long serialVersionUID = 4759684498722016230L;

		public DateSerializer() {
			this(Date.class);
		}

		public DateSerializer(Class<Date> t) {
			super(t);
		}

		@Override
		public void serialize(Date value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			if (isBinaryFormat(gen))
				gen.writeNumber(value.getTime());
			else
				gen.writeString(getDateFormat().format(value));
		}
	}

	/**
	 * Deserializer for {@link java.util.Date} objects.
	 * <p>
	 * Handles deserialization of Date objects from either ISO8601/RFC3339 string format or from epoch milliseconds,
	 * depending on the input format. In text formats, expects ISO8601 or RFC3339 strings; in binary formats, expects
	 * epoch milliseconds.
	 */
	static class DateDeserializer extends StdDeserializer<Date> {
		private static final long serialVersionUID = -4252894239212420927L;

		public DateDeserializer() {
			this(Date.class);
		}

		public DateDeserializer(Class<?> t) {
			super(t);
		}

		@Override
		public Date deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
			if (p.getCurrentToken() == JsonToken.VALUE_NUMBER_INT) {
				// Binary format: epoch milliseconds
				return new Date(p.getValueAsLong());
			} else {
				// Text format: RFC3339 / ISO8601 format
				if (!p.getCurrentToken().equals(JsonToken.VALUE_STRING))
					throw ctx.wrongTokenException(p, String.class, JsonToken.VALUE_STRING, "Invalid datetime string");

				final String dateStr = p.getValueAsString();
				try {
					return getDateFormat().parse(dateStr);
				} catch (ParseException ignore) {
				}

				// Fail-back to ISO 8601 format.
				try {
					return getFailbackDateFormat().parse(dateStr);
				} catch (ParseException e) {
					throw ctx.weirdStringException(p.getText(),
							Date.class, "Invalid datetime string");
				}
			}
		}
	}


	/**
	 * Returns the default date and time format for serializing and deserializing {@link java.util.Date} objects.
	 * <p>
	 * This format uses the ISO8601 pattern {@code yyyy-MM-dd'T'HH:mm:ss'Z'} in UTC timezone.
	 *
	 * @return the {@code DateFormat} object for ISO8601 date formatting.
	 */
	public static DateFormat getDateFormat() {
		SimpleDateFormat dateFormat = new SimpleDateFormat(ISO_8601);
		dateFormat.setTimeZone(UTC);
		return dateFormat;
	}

	/**
	 * Returns a failback date and time format for deserializing {@link java.util.Date} objects.
	 * <p>
	 * This format uses the ISO8601 pattern with milliseconds: {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'} in UTC timezone.
	 * Used if parsing with {@link #getDateFormat()} fails.
	 *
	 * @return the {@code DateFormat} object for ISO8601 date formatting with milliseconds.
	 */
	protected static DateFormat getFailbackDateFormat() {
		SimpleDateFormat dateFormat = new SimpleDateFormat(ISO_8601_WITH_MILLISECONDS);
		dateFormat.setTimeZone(UTC);
		return dateFormat;
	}

	/**
	 * Serializer for {@link NodeInfo} objects.
	 * <p>
	 * Encodes NodeInfo as a triple/array: [id, host, port]. In binary formats (CBOR),
	 * id and host are written as Base64-encoded binary; in text formats (JSON/YAML),
	 * id is written as Base58 and host as a string (IP address or hostname).
	 */
	static class NodeInfoSerializer extends StdSerializer<NodeInfo> {
		private static final long serialVersionUID = 652112589617276783L;

		public NodeInfoSerializer() {
			this(NodeInfo.class);
		}

		public NodeInfoSerializer(Class<NodeInfo> t) {
			super(t);
		}

		@Override
		public void serialize(NodeInfo value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			// Format: triple
			//   [id, host, port]
			//
			// host:
			//   text format: IP address string or hostname string
			//   binary format: binary ip address
			gen.writeStartArray();

			if (isBinaryFormat(gen)) {
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.getId().bytes(), 0, Id.BYTES);
				byte[] addr = value.getIpAddress().getAddress();
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, addr, 0, addr.length); // binary ip address
			} else {
				gen.writeString(value.getId().toBase58String());
				gen.writeString(value.getHost()); // host name or ip address
			}

			gen.writeNumber(value.getPort());

			gen.writeEndArray();
		}
	}

	/**
	 * Deserializer for {@link NodeInfo} objects.
	 * <p>
	 * Expects an array of [id, host, port]. In binary formats, id and host are decoded from Base64-encoded binary;
	 * in text formats, id is parsed from Base58 and host from a string (IP address or hostname).
	 */
	static class NodeInfoDeserializer extends StdDeserializer<NodeInfo> {
		private static final long serialVersionUID = -1802423497777216345L;

		public NodeInfoDeserializer() {
			this(NodeInfo.class);
		}

		public NodeInfoDeserializer(Class<?> vc) {
			super(vc);
		}

		@Override
		public NodeInfo deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
			if (p.currentToken() != JsonToken.START_ARRAY)
				throw MismatchedInputException.from(p, NodeInfo.class, "Invalid NodeInfo, should be an array");

			final boolean binaryFormat = isBinaryFormat(p);

			Id id = null;
			InetAddress addr = null;
			int port = 0;

			// id
			if (p.nextToken() != JsonToken.VALUE_NULL)
				id = binaryFormat ? Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());

			// address
			// text format: IP address string or hostname string
			// binary format: binary ip address or host name string
			if (p.nextToken() != JsonToken.VALUE_NULL)
				addr = binaryFormat ? InetAddress.getByAddress(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) :
						InetAddress.getByName(p.getText());

			// port
			if (p.nextToken() != JsonToken.VALUE_NULL)
				port = p.getIntValue();

			if (p.nextToken() != JsonToken.END_ARRAY)
				throw MismatchedInputException.from(p, NodeInfo.class, "Invalid NodeInfo: too many elements in array");

			return new NodeInfo(id, addr, port);
		}
	}

	/**
	 * Serializer for {@link PeerInfo} objects.
	 * <p>
	 * Encodes PeerInfo as a 6-element array: [peerId, nodeId, originNodeId, port, alternativeURI, signature].
	 * In binary formats, ids and signature are written as Base64-encoded binary; in text formats, ids are Base58 strings.
	 * Special behavior: the peerId can be omitted if the context attribute
	 * {@link io.bosonnetwork.PeerInfo#ATTRIBUTE_OMIT_PEER_ID} is set.
	 */
	static class PeerInfoSerializer extends StdSerializer<PeerInfo> {
		private static final long serialVersionUID = -2372725165793659632L;

		public PeerInfoSerializer() {
			this(PeerInfo.class);
		}

		public PeerInfoSerializer(Class<PeerInfo> t) {
			super(t);
		}

		@Override
		public void serialize(PeerInfo value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			final boolean binaryFormat = isBinaryFormat(gen);

			// Format: 6-tuple
			//   [peerId, nodeId, originNodeId, port, alternativeURI, signature]
			// If omit the peer id, format:
			//   [null, nodeId, originNodeId, port, alternativeURI, signature]

			gen.writeStartArray();

			// omit peer id?
			final Boolean attr = (Boolean) provider.getAttribute(PeerInfo.ATTRIBUTE_OMIT_PEER_ID);
			final boolean omitPeerId = attr != null && attr;

			if (!omitPeerId) {
				if (binaryFormat)
					gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.getId().bytes(), 0, Id.BYTES);
				else
					gen.writeString(value.getId().toBase58String());
			} else {
				gen.writeNull();
			}

			// node id
			if (binaryFormat)
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.getNodeId().bytes(), 0, Id.BYTES);
			else
				gen.writeString(value.getNodeId().toBase58String());

			// origin node id
			if (value.isDelegated()) {
				if (binaryFormat)
					gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.getOrigin().bytes(), 0, Id.BYTES);
				else
					gen.writeString(value.getOrigin().toBase58String());
			} else {
				gen.writeNull();
			}

			// port
			gen.writeNumber(value.getPort());

			// alternative url
			if (value.hasAlternativeURI())
				gen.writeString(value.getAlternativeURI());
			else
				gen.writeNull();

			// signature
			byte[] sig = value.getSignature();
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, sig, 0, sig.length);

			gen.writeEndArray();
		}
	}

	/**
	 * Deserializer for {@link PeerInfo} objects.
	 * <p>
	 * Expects a 6-element array: [peerId, nodeId, originNodeId, port, alternativeURI, signature].
	 * In binary formats, ids and signature are decoded from Base64-encoded binary; in text formats, ids are Base58 strings.
	 * Special behavior: if peerId is omitted (null), it is taken from the context attribute
	 * {@link io.bosonnetwork.PeerInfo#ATTRIBUTE_PEER_ID}.
	 */
	static class PeerInfoDeserializer extends StdDeserializer<PeerInfo> {
		private static final long serialVersionUID = 6475890164214322573L;

		public PeerInfoDeserializer() {
			this(PeerInfo.class);
		}

		public PeerInfoDeserializer(Class<?> vc) {
			super(vc);
		}

		@Override
		public PeerInfo deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
			if (p.currentToken() != JsonToken.START_ARRAY)
				throw MismatchedInputException.from(p, PeerInfo.class, "Invalid PeerInfo, should be an array");

			final boolean binaryFormat = isBinaryFormat(p);

			Id peerId;
			Id nodeId = null;
			Id origin = null;
			int port = 0;
			String alternativeURI = null;
			byte[] signature = null;

			// peer id
			if (p.nextToken() != JsonToken.VALUE_NULL) {
				peerId = binaryFormat ? Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());
			} else {
				// peer id is omitted, should retrieve it from the context
				peerId = (Id) ctx.getAttribute(PeerInfo.ATTRIBUTE_PEER_ID);
				if (peerId == null)
					throw MismatchedInputException.from(p, Id.class, "Invalid PeerInfo: peer id can not be null");
			}

			// node id
			if (p.nextToken() != JsonToken.VALUE_NULL)
				nodeId = binaryFormat ? Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());

			// origin node id
			if (p.nextToken() != JsonToken.VALUE_NULL)
				origin = binaryFormat ?	Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());

			// port
			if (p.nextToken() != JsonToken.VALUE_NULL)
				port = p.getIntValue();

			// alternative url
			if (p.nextToken() != JsonToken.VALUE_NULL)
				alternativeURI = p.getText();

			// signature
			if (p.nextToken() != JsonToken.VALUE_NULL)
				signature = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);

			if (p.nextToken() != JsonToken.END_ARRAY)
				throw MismatchedInputException.from(p, PeerInfo.class, "Invalid PeerInfo: too many elements in array");

			return PeerInfo.of(peerId, nodeId, origin, port, alternativeURI, signature);
		}
	}

	/**
	 * Serializer for {@link Value} objects.
	 * <p>
	 * Serializes fields of Value as a JSON object. In binary formats, fields are written as Base64-encoded binary;
	 * in text formats, ids are written as Base58 strings and binary fields as Base64. Handles optional fields.
	 */
	static class ValueSerializer extends StdSerializer<Value> {
		private static final long serialVersionUID = 5494303011447541850L;

		public ValueSerializer() {
			this(Value.class);
		}

		public ValueSerializer(Class<Value> t) {
			super(t);
		}

		@Override
		public void serialize(Value value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			final boolean binaryFormat = isBinaryFormat(gen);
			gen.writeStartObject();

			if (value.getPublicKey() != null) {
				// public key
				if (binaryFormat) {
					gen.writeFieldName("k");
					gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.getPublicKey().bytes(), 0, Id.BYTES);
				} else {
					gen.writeStringField("k", value.getPublicKey().toBase58String());
				}

				// recipient
				if (value.getRecipient() != null) {
					if (binaryFormat) {
						gen.writeFieldName("rec");
						gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.getRecipient().bytes(), 0, Id.BYTES);
					} else {
						gen.writeStringField("rec", value.getRecipient().toBase58String());
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

				// signature
				binary = value.getSignature();
				if (binary != null) {
					gen.writeFieldName("sig");
					gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, binary, 0, binary.length);
				}
			}

			byte[] data = value.getData();
			if (data != null && data.length > 0) {
				gen.writeFieldName("v");
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, data, 0, data.length);
			}

			gen.writeEndObject();
		}
	}

	/**
	 * Deserializer for {@link Value} objects.
	 * <p>
	 * Decodes a JSON object with fields corresponding to Value's structure. In binary formats,
	 * fields are decoded from Base64-encoded binary; in text formats, ids are parsed from Base58 strings.
	 * Handles missing or optional fields gracefully.
	 */
	static class ValueDeserializer extends StdDeserializer<Value> {
		private static final long serialVersionUID = 2370471437259629126L;

		public ValueDeserializer() {
			this(Value.class);
		}

		public ValueDeserializer(Class<?> vc) {
			super(vc);
		}

		@Override
		public Value deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
			if (p.currentToken() != JsonToken.START_OBJECT)
				throw MismatchedInputException.from(p, Value.class, "Invalid Value: should be an object");

			final boolean binaryFormat = isBinaryFormat(p);

			Id publicKey = null;
			Id recipient = null;
			byte[] nonce = null;
			int sequenceNumber = 0;
			byte[] signature = null;
			byte[] data = null;

			while (p.nextToken() != JsonToken.END_OBJECT) {
				String fieldName = p.currentName();
				JsonToken token = p.nextToken();
				switch (fieldName) {
					case "k":
						if (token != JsonToken.VALUE_NULL)
							publicKey = binaryFormat ? Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());
						break;
					case "rec":
						if (token != JsonToken.VALUE_NULL)
							recipient = binaryFormat ? Id.of(p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL)) : Id.of(p.getText());
						break;
					case "n":
						if (p.currentToken() != JsonToken.VALUE_NULL)
							nonce = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
						break;
					case "seq":
						if (p.currentToken() != JsonToken.VALUE_NULL)
							sequenceNumber = p.getIntValue();
						break;
					case "sig":
						if (p.currentToken() != JsonToken.VALUE_NULL)
							signature = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
						break;
					case "v":
						if (p.currentToken() != JsonToken.VALUE_NULL)
							data = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
						break;
					default:
						p.skipChildren();
				}
			}

			return Value.of(publicKey, recipient, nonce, sequenceNumber, signature, data);
		}
	}

	/**
	 * Creates the Jackson module.
	 *
	 * @return the {@code SimpleModule} object.
	 */
	protected static SimpleModule bosonJsonModule() {
		if (_bosonJsonModule == null) {
			SimpleModule module = new SimpleModule(BOSON_JSON_MODULE_NAME);
			module.addSerializer(Date.class, new DateSerializer());
			module.addDeserializer(Date.class, new DateDeserializer());
			module.addSerializer(Id.class, new IdSerializer());
			module.addDeserializer(Id.class, new IdDeserializer());
			module.addSerializer(InetAddress.class, new InetAddressSerializer());
			module.addDeserializer(InetAddress.class, new InetAddressDeserializer());

			module.addSerializer(NodeInfo.class, new NodeInfoSerializer());
			module.addDeserializer(NodeInfo.class, new NodeInfoDeserializer());
			module.addSerializer(PeerInfo.class, new PeerInfoSerializer());
			module.addDeserializer(PeerInfo.class, new PeerInfoDeserializer());
			module.addSerializer(Value.class, new ValueSerializer());
			module.addDeserializer(Value.class, new ValueDeserializer());

			_bosonJsonModule = module;
		}

		return _bosonJsonModule;
	}

	/**
	 * Creates the Jackson JSON factory without auto-close the source and target.
	 *
	 * @return the {@code JsonFactory} object.
	 */
	public static JsonFactory jsonFactory() {
		if (_jsonFactory == null) {
			JsonFactory factory = new JsonFactory();
			factory.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
			factory.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
			_jsonFactory = factory;
		}

		return _jsonFactory;
	}

	/**
	 * Creates the Jackson CBOR factory without auto-close the source and target.
	 *
	 * @return the {@code CBORFactory} object.
	 */
	public static CBORFactory cborFactory() {
		if (_cborFactory == null) {
			CBORFactory factory = new CBORFactory();
			factory.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
			factory.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
			_cborFactory = factory;
		}

		return _cborFactory;
	}

	/**
	 * Creates the Jackson object mapper, with basic Boson types support.
	 *
	 * @return the new {@code ObjectMapper} object.
	 */
	public static ObjectMapper objectMapper() {
		if (_objectMapper == null) {
			_objectMapper = JsonMapper.builder(jsonFactory())
					.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
					.disable(MapperFeature.AUTO_DETECT_CREATORS)
					.disable(MapperFeature.AUTO_DETECT_FIELDS)
					.disable(MapperFeature.AUTO_DETECT_GETTERS)
					.disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
					.disable(MapperFeature.AUTO_DETECT_SETTERS)
					.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
					.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
					.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
					.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
					// .defaultDateFormat(getDateFormat())
					.defaultBase64Variant(Base64Variants.MODIFIED_FOR_URL)
					.addModule(bosonJsonModule())
					.build();
		}

		return _objectMapper;
	}

	/**
	 * Creates the Jackson CBOR mapper, with basic Boson types support.
	 *
	 * @return the new {@code CBORMapper} object.
	 */
	public static CBORMapper cborMapper() {
		if (_cborMapper == null) {
			_cborMapper = CBORMapper.builder(cborFactory())
					.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
					.disable(MapperFeature.AUTO_DETECT_CREATORS)
					.disable(MapperFeature.AUTO_DETECT_FIELDS)
					.disable(MapperFeature.AUTO_DETECT_GETTERS)
					.disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
					.disable(MapperFeature.AUTO_DETECT_SETTERS)
					.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
					.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
					.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
					.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
					// .defaultDateFormat(getDateFormat())
					.defaultBase64Variant(Base64Variants.MODIFIED_FOR_URL)
					.addModule(bosonJsonModule())
					.build();
		}

		return _cborMapper;
	}

	/**
	 * Creates the Jackson YAML mapper, with basic Boson types support.
	 *
	 * @return the new {@code YAMLMapper} object.
	 */
	public static YAMLMapper yamlMapper() {
		if (_yamlMapper == null) {
			YAMLFactory factory = new YAMLFactory();
			factory.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
			factory.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);

			_yamlMapper = YAMLMapper.builder(factory)
					.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
					.disable(MapperFeature.AUTO_DETECT_CREATORS)
					.disable(MapperFeature.AUTO_DETECT_FIELDS)
					.disable(MapperFeature.AUTO_DETECT_GETTERS)
					.disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
					.disable(MapperFeature.AUTO_DETECT_SETTERS)
					.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
					.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
					.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
					.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
					.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
					.enable(YAMLGenerator.Feature.INDENT_ARRAYS)
					.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
					//.defaultDateFormat(getDateFormat())
					.defaultBase64Variant(Base64Variants.MODIFIED_FOR_URL)
					.addModule(bosonJsonModule())
					.build();
		}

		return _yamlMapper;
	}

	/**
	 * Serializes the given object to a JSON string using the provided {@link JsonContext}.
	 *
	 * @param object  the object to serialize
	 * @param context the serialization context, or {@code null} for default context
	 * @return the JSON string representation of the object
	 * @throws IllegalArgumentException if the object cannot be serialized
	 */
	public static String toString(Object object, JsonContext context) {
		try {
			if (context == null || context.isEmpty())
				return objectMapper().writeValueAsString(object);
			else
				return objectMapper().writer(context).writeValueAsString(object);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("object can not be serialized", e);
		}
	}

	/**
	 * Serializes the given object to a JSON string using the default context.
	 *
	 * @param object the object to serialize
	 * @return the JSON string representation of the object
	 * @throws IllegalArgumentException if the object cannot be serialized
	 */
	public static String toString(Object object) {
		return toString(object, null);
	}

	/**
	 * Serializes the given object to a pretty-printed JSON string using the provided {@link JsonContext}.
	 *
	 * @param object  the object to serialize
	 * @param context the serialization context, or {@code null} for default context
	 * @return the pretty-printed JSON string representation of the object
	 * @throws IllegalArgumentException if the object cannot be serialized
	 */
	public static String toPrettyString(Object object, JsonContext context) {
		try {
			ObjectWriter writer = context == null || context.isEmpty() ?
					objectMapper().writerWithDefaultPrettyPrinter() :
					objectMapper().writerWithDefaultPrettyPrinter().with(context);

			return writer.writeValueAsString(object);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("object can not be serialized", e);
		}
	}

	/**
	 * Serializes the given object to a pretty-printed JSON string using the default context.
	 *
	 * @param object the object to serialize
	 * @return the pretty-printed JSON string representation of the object
	 * @throws IllegalArgumentException if the object cannot be serialized
	 */
	public static String toPrettyString(Object object) {
		return toPrettyString(object, null);
	}

	/**
	 * Serializes the given object to a CBOR-encoded byte array using the provided {@link JsonContext}.
	 *
	 * @param object  the object to serialize
	 * @param context the serialization context, or {@code null} for default context
	 * @return the CBOR-encoded byte array representation of the object
	 * @throws IllegalArgumentException if the object cannot be serialized
	 */
	public static byte[] toBytes(Object object, JsonContext context) {
		try {
			if (context == null || context.isEmpty())
				return cborMapper().writeValueAsBytes(object);
			else
				return cborMapper().writer(context).writeValueAsBytes(object);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("object can not be serialized", e);
		}
	}

	/**
	 * Serializes the given object to a CBOR-encoded byte array using the default context.
	 *
	 * @param object the object to serialize
	 * @return the CBOR-encoded byte array representation of the object
	 * @throws IllegalArgumentException if the object cannot be serialized
	 */
	public static byte[] toBytes(Object object) {
		return toBytes(object, null);
	}

	/**
	 * Returns a Jackson {@link TypeReference} for {@code Map<String, Object>} for generic map parsing.
	 *
	 * @return the {@code TypeReference} for {@code Map<String, Object>}
	 */
	public static TypeReference<Map<String, Object>> mapType() {
		if (_mapType == null)
			_mapType = new TypeReference<>() { };

		return _mapType;
	}

	/**
	 * Parses a JSON string into a {@code Map<String, Object>} using the provided {@link JsonContext}.
	 *
	 * @param json    the JSON string to parse
	 * @param context the deserialization context, or {@code null} for default context
	 * @return a map representation of the JSON input
	 * @throws IllegalArgumentException if the JSON cannot be parsed
	 */
	public static Map<String, Object> parse(String json, JsonContext context) {
		return parse(json, mapType(), context);
	}

	/**
	 * Parses a JSON string into a {@code Map<String, Object>} using the default context.
	 *
	 * @param json the JSON string to parse
	 * @return a map representation of the JSON input
	 * @throws IllegalArgumentException if the JSON cannot be parsed
	 */
	public static Map<String, Object> parse(String json) {
		return parse(json, mapType(), null);
	}

	/**
	 * Parses a JSON string into an object of the specified class using the provided {@link JsonContext}.
	 *
	 * @param json    the JSON string to parse
	 * @param clazz   the class of the object to return
	 * @param context the deserialization context, or {@code null} for default context
	 * @param <T>     the type of the desired object
	 * @return the parsed object
	 * @throws IllegalArgumentException if the JSON cannot be parsed
	 */
	public static <T> T parse(String json, Class<T> clazz, JsonContext context) {
		try {
			if (context == null || context.isEmpty())
				return objectMapper().readValue(json, clazz);
			else
				return objectMapper().reader(context).forType(clazz).readValue(json);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("json can not be parsed", e);
		}
	}

	/**
	 * Parses a JSON string into an object of the specified class using the default context.
	 *
	 * @param json  the JSON string to parse
	 * @param clazz the class of the object to return
	 * @param <T>   the type of the desired object
	 * @return the parsed object
	 * @throws IllegalArgumentException if the JSON cannot be parsed
	 */
	public static <T> T parse(String json, Class<T> clazz) {
		return parse(json, clazz, null);
	}

	/**
	 * Parses a JSON string into an object of the specified type using the provided {@link JsonContext}.
	 *
	 * @param json    the JSON string to parse
	 * @param type    the type reference describing the type to return
	 * @param context the deserialization context, or {@code null} for default context
	 * @param <T>     the type of the desired object
	 * @return the parsed object
	 * @throws IllegalArgumentException if the JSON cannot be parsed
	 */
	public static <T> T parse(String json, TypeReference<T> type, JsonContext context) {
		try {
			if (context == null || context.isEmpty())
				return objectMapper().readValue(json, type);
			else
				return objectMapper().reader(context).forType(type).readValue(json);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("json can not be parsed", e);
		}
	}

	/**
	 * Parses a JSON string into an object of the specified type using the default context.
	 *
	 * @param json the JSON string to parse
	 * @param type the type reference describing the type to return
	 * @param <T>  the type of the desired object
	 * @return the parsed object
	 * @throws IllegalArgumentException if the JSON cannot be parsed
	 */
	public static <T> T parse(String json, TypeReference<T> type) {
		return parse(json, type, null);
	}

	/**
	 * Parses a CBOR-encoded byte array into a {@code Map<String, Object>} using the provided {@link JsonContext}.
	 *
	 * @param cbor    the CBOR-encoded byte array to parse
	 * @param context the deserialization context, or {@code null} for default context
	 * @return a map representation of the CBOR input
	 * @throws IllegalArgumentException if the CBOR cannot be parsed
	 */
	public static Map<String, Object> parse(byte[] cbor, JsonContext context) {
		return parse(cbor, mapType(), context);
	}

	/**
	 * Parses a CBOR-encoded byte array into a {@code Map<String, Object>} using the default context.
	 *
	 * @param cbor the CBOR-encoded byte array to parse
	 * @return a map representation of the CBOR input
	 * @throws IllegalArgumentException if the CBOR cannot be parsed
	 */
	public static Map<String, Object> parse(byte[] cbor) {
		return parse(cbor, mapType(), null);
	}

	/**
	 * Parses a CBOR-encoded byte array into an object of the specified class using the provided {@link JsonContext}.
	 *
	 * @param cbor    the CBOR-encoded byte array to parse
	 * @param clazz   the class of the object to return
	 * @param context the deserialization context, or {@code null} for default context
	 * @param <T>     the type of the desired object
	 * @return the parsed object
	 * @throws IllegalArgumentException if the CBOR cannot be parsed
	 */
	public static <T> T parse(byte[] cbor, Class<T> clazz, JsonContext context) {
		try {
			if (context == null || context.isEmpty())
				return cborMapper().readValue(cbor, clazz);
			else
				return cborMapper().reader(context).forType(clazz).readValue(cbor);
		} catch (IOException e) {
			throw new IllegalArgumentException("cbor can not be parsed", e);
		}
	}

	/**
	 * Parses a CBOR-encoded byte array into an object of the specified class using the default context.
	 *
	 * @param cbor  the CBOR-encoded byte array to parse
	 * @param clazz the class of the object to return
	 * @param <T>   the type of the desired object
	 * @return the parsed object
	 * @throws IllegalArgumentException if the CBOR cannot be parsed
	 */
	public static <T> T parse(byte[] cbor, Class<T> clazz) {
		return parse(cbor, clazz, null);
	}

	/**
	 * Parses a CBOR-encoded byte array into an object of the specified type using the provided {@link JsonContext}.
	 *
	 * @param cbor    the CBOR-encoded byte array to parse
	 * @param type    the type reference describing the type to return
	 * @param context the deserialization context, or {@code null} for default context
	 * @param <T>     the type of the desired object
	 * @return the parsed object
	 * @throws IllegalArgumentException if the CBOR cannot be parsed
	 */
	public static <T> T parse(byte[] cbor, TypeReference<T> type, JsonContext context) {
		try {
			if (context == null || context.isEmpty())
				return cborMapper().readValue(cbor, type);
			else
				return cborMapper().reader(context).forType(type).readValue(cbor);
		} catch (IOException e) {
			throw new IllegalArgumentException("cbor can not be parsed", e);
		}
	}

	/**
	 * Parses a CBOR-encoded byte array into an object of the specified type using the default context.
	 *
	 * @param cbor the CBOR-encoded byte array to parse
	 * @param type the type reference describing the type to return
	 * @param <T>  the type of the desired object
	 * @return the parsed object
	 * @throws IllegalArgumentException if the CBOR cannot be parsed
	 */
	public static <T> T parse(byte[] cbor, TypeReference<T> type) {
		return parse(cbor, type, null);
	}

	/**
	 * Initializes and registers the Boson JSON Jackson module with the global Jackson DatabindCodec mapper.
	 * <p>
	 * This method ensures the Boson JSON module is registered only once. If already registered,
	 * the method returns immediately. The module adds support for Boson-specific types and serialization behaviors.
	 */
	public static void initializeBosonJsonModule() {
		if (DatabindCodec.mapper().getRegisteredModuleIds().stream()
				.anyMatch(id -> id.equals(BOSON_JSON_MODULE_NAME)))
			return; // already registered

		DatabindCodec.mapper().registerModule(bosonJsonModule());
		DatabindCodec.mapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
	}

	/**
	 * A context object for customizing JSON serialization and deserialization.
	 * <p>
	 * This class extends {@link ContextAttributes.Impl} and provides a convenient, immutable,
	 * and type-safe way to manage shared (global) and per-call (thread-local) attributes for Jackson operations.
	 * <p>
	 * Use static factory methods such as {@link #empty()}, {@link #perCall()}, and {@link #shared(Object, Object)}
	 * to construct a context instance with the desired attributes. Use instance methods to query or derive
	 * new contexts with added/removed attributes.
	 * <p>
	 * Shared attributes are visible to all serialization/deserialization operations that use this context,
	 * while per-call attributes are specific to a single operation or thread.
	 */
	public static class JsonContext extends ContextAttributes.Impl {
		private static final long serialVersionUID = -385397772721358918L;

		/**
		 * Constructs a new {@code JsonContext} with the specified shared attributes and an empty per-call attribute map.
		 *
		 * @param shared the shared (global) attribute map
		 */
		protected JsonContext(Map<?,?> shared) {
			super(shared, new HashMap<>());
		}

		/**
		 * Constructs a new {@code JsonContext} with the specified shared and per-call (non-shared) attributes.
		 *
		 * @param shared   the shared (global) attribute map
		 * @param nonShared the per-call (thread-local) attribute map
		 */
		protected JsonContext(Map<?,?> shared, Map<Object,Object> nonShared) {
			super(shared, nonShared);
		}

		/**
		 * Returns an empty {@code JsonContext} with no shared or per-call attributes.
		 *
		 * @return an empty context
		 */
		public static JsonContext empty() {
			return new JsonContext(Collections.emptyMap(), Collections.emptyMap());
		}

		/**
		 * Returns a new {@code JsonContext} with no shared or per-call attributes, for use as a per-call context.
		 *
		 * @return a per-call context with no attributes
		 */
		public static JsonContext perCall() {
			return new JsonContext(Collections.emptyMap(), Collections.emptyMap());
		}

		/**
		 * Returns a per-call {@code JsonContext} with the given attribute key and value.
		 * The attribute will only be visible to the current serialization/deserialization operation.
		 *
		 * @param key   the attribute key
		 * @param value the attribute value
		 * @return a per-call context with the specified attribute
		 */
		public static JsonContext perCall(Object key, Object value) {
			Map<Object, Object> m = new HashMap<>();
			m.put(key, value);
			return new JsonContext(Collections.emptyMap(), m);
		}

		/**
		 * Returns a per-call {@code JsonContext} with two attribute key/value pairs.
		 *
		 * @param key1   the first attribute key
		 * @param value1 the first attribute value
		 * @param key2   the second attribute key
		 * @param value2 the second attribute value
		 * @return a per-call context with the specified attributes
		 */
		static JsonContext perCall(Object key1, Object value1, Object key2, Object value2) {
			Map<Object, Object> m = new HashMap<>();
			m.put(key1, value1);
			m.put(key2, value2);
			return new JsonContext(Collections.emptyMap(), m);
		}

		/**
		 * Returns a per-call {@code JsonContext} with three attribute key/value pairs.
		 *
		 * @param key1   the first attribute key
		 * @param value1 the first attribute value
		 * @param key2   the second attribute key
		 * @param value2 the second attribute value
		 * @param key3   the third attribute key
		 * @param value3 the third attribute value
		 * @return a per-call context with the specified attributes
		 */
		static JsonContext perCall(Object key1, Object value1, Object key2, Object value2, Object key3, Object value3) {
			Map<Object, Object> m = new HashMap<>();
			m.put(key1, value1);
			m.put(key2, value2);
			m.put(key3, value3);
			return new JsonContext(Collections.emptyMap(), m);
		}

		/**
		 * Returns a new {@code JsonContext} with the given shared attribute key and value.
		 * Shared attributes are visible to all serialization/deserialization operations using this context.
		 *
		 * @param key   the shared attribute key
		 * @param value the shared attribute value
		 * @return a context with the specified shared attribute
		 */
		static JsonContext shared(Object key, Object value) {
			return new JsonContext(Map.of(key, value), Collections.emptyMap());
		}

		/**
		 * Returns a new {@code JsonContext} with two shared attribute key/value pairs.
		 *
		 * @param key1   the first shared attribute key
		 * @param value1 the first shared attribute value
		 * @param key2   the second shared attribute key
		 * @param value2 the second shared attribute value
		 * @return a context with the specified shared attributes
		 */
		static JsonContext shared(Object key1, Object value1, Object key2, Object value2) {
			return new JsonContext(Map.of(key1, value1, key2, value2), Collections.emptyMap());
		}

		/**
		 * Returns a new {@code JsonContext} with three shared attribute key/value pairs.
		 *
		 * @param key1   the first shared attribute key
		 * @param value1 the first shared attribute value
		 * @param key2   the second shared attribute key
		 * @param value2 the second shared attribute value
		 * @param key3   the third shared attribute key
		 * @param value3 the third shared attribute value
		 * @return a context with the specified shared attributes
		 */
		static JsonContext shared(Object key1, Object value1, Object key2, Object value2, Object key3, Object value3) {
			return new JsonContext(Map.of(key1, value1, key2, value2, key3, value3), Collections.emptyMap());
		}

		/**
		 * Returns the value of the attribute for the specified key.
		 * If the key is {@code JsonContext.class} or {@code ContextAttributes.class}, returns this context instance.
		 * Otherwise, delegates to the parent implementation.
		 *
		 * @param key the attribute key
		 * @return the attribute value, or {@code null} if not present
		 */
		@Override
		public Object getAttribute(Object key) {
			if (key == JsonContext.class || key == ContextAttributes.class)
				return this;

			return super.getAttribute(key);
		}

		/**
		 * Returns {@code true} if this context contains no shared or per-call attributes.
		 *
		 * @return {@code true} if empty; {@code false} otherwise
		 */
		public boolean isEmpty() {
			return _shared.isEmpty() && _nonShared.isEmpty();
		}

		/**
		 * Returns a new {@code JsonContext} with the given shared attribute key and value, replacing any previous value.
		 * Shared attributes are visible to all operations using this context.
		 *
		 * @param key   the shared attribute key
		 * @param value the shared attribute value
		 * @return a new context with the updated shared attribute
		 */
		@Override
		public JsonContext withSharedAttribute(Object key, Object value) {
			if (_shared.isEmpty()) {
				return new JsonContext(Map.of(key, value));
			} else {
				Map<Object, Object> newShared = new HashMap<>(_shared);
				newShared.put(key, value);
				return new JsonContext(newShared);
			}
		}

		/**
		 * Returns a new {@code JsonContext} with the specified shared attributes, replacing all previous shared attributes.
		 *
		 * @param attributes the shared attributes to set (maybe {@code null} or empty)
		 * @return a new context with the specified shared attributes
		 */
		@Override
		public JsonContext withSharedAttributes(Map<?, ?> attributes) {
			return new JsonContext(attributes == null || attributes.isEmpty() ? Collections.emptyMap() : attributes);
		}

		/**
		 * Returns a new {@code JsonContext} without the specified shared attribute key.
		 * If the key does not exist, returns this context instance.
		 *
		 * @param key the shared attribute key to remove
		 * @return a new context without the specified shared attribute
		 */
		@Override
		public JsonContext withoutSharedAttribute(Object key) {
			if (_shared.isEmpty() || !_shared.containsKey(key))
				return this;

			if (_shared.size() == 1)
				return empty();

			Map<Object,Object> newShared = new HashMap<>(_shared);
			newShared.remove(key);
			return new JsonContext(newShared);
		}

		/**
		 * Returns a new {@code JsonContext} with the given per-call (non-shared) attribute key and value.
		 * Per-call attributes are visible only to a single serialization/deserialization operation.
		 * <p>
		 * If {@code value} is {@code null}, and the key exists in shared attributes,
		 * a special null surrogate is used to mask the shared value. If the key does not exist in shared or per-call attributes,
		 * the context is returned unchanged.
		 *
		 * @param key   the per-call attribute key
		 * @param value the per-call attribute value (maybe {@code null}, see behavior above)
		 * @return a new context with the updated per-call attribute
		 */
		@Override
		public JsonContext withPerCallAttribute(Object key, Object value) {
			// First: null value may need masking
			if (value == null) {
				// need to mask nulls to ensure default values won't be showing
				if (_shared.containsKey(key)) {
					value = NULL_SURROGATE;
				} else if ((_nonShared == null) || !_nonShared.containsKey(key)) {
					// except if an immutable shared list has no entry, we don't care
					return this;
				} else {
					//noinspection RedundantCollectionOperation
					if (_nonShared.containsKey(key)) // avoid exception on immutable map
						_nonShared.remove(key);
					return this;
				}
			}

			if (_nonShared == Collections.emptyMap())
				_nonShared = new HashMap<>();

			_nonShared.put(key, value);
			return this;
		}
	}
}