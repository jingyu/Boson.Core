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
 * Common JSON utility methods for JSON process.
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

	public static boolean isBinaryFormat(JsonParser p) {
		// Now we only sport JSON, CBOR and TOML formats, CBOR is the only binary format
		return p instanceof CBORParser;
	}

	public static boolean isBinaryFormat(JsonGenerator gen) {
		// Now we only sport JSON, CBOR and TOML formats, CBOR is the only binary format
		return gen instanceof CBORGenerator;
	}

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
	 * Date and time deserializer from RFC3339 / ISO8601 format, or epoch milliseconds.
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
	 * Creates the default date and time format object with ISO8601 format.
	 *
	 * @return the {@code DateFormat} object.
	 */
	public static DateFormat getDateFormat() {
		SimpleDateFormat dateFormat = new SimpleDateFormat(ISO_8601);
		dateFormat.setTimeZone(UTC);
		return dateFormat;
	}

	/**
	 * Create the fail-back date and time format object.
	 *
	 * @return the {@code DateFormat} object.
	 */
	protected static DateFormat getFailbackDateFormat() {
		SimpleDateFormat dateFormat = new SimpleDateFormat(ISO_8601_WITH_MILLISECONDS);
		dateFormat.setTimeZone(UTC);
		return dateFormat;
	}

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
				byte[] addr = value.getAddress().getAddress().getAddress();
				gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, addr, 0, addr.length); // binary ip address
			} else {
				gen.writeString(value.getId().toBase58String());
				gen.writeString(value.getAddress().getHostString()); // ip address or host name
			}

			gen.writeNumber(value.getAddress().getPort());

			gen.writeEndArray();
		}
	}

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
			//   [peerId, nodeId, originNodeId, port, alternativeURL, signature]
			// If omit the peer id, format:
			//   [null, nodeId, originNodeId, port, alternativeURL, signature]

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
			if (value.hasAlternativeURL())
				gen.writeString(value.getAlternativeURL());
			else
				gen.writeNull();

			// signature
			byte[] sig = value.getSignature();
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, sig, 0, sig.length);

			gen.writeEndArray();
		}
	}

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
			String alternativeURL = null;
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
				alternativeURL = p.getText();

			// signature
			if (p.nextToken() != JsonToken.VALUE_NULL)
				signature = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);

			if (p.nextToken() != JsonToken.END_ARRAY)
				throw MismatchedInputException.from(p, PeerInfo.class, "Invalid PeerInfo: too many elements in array");

			return PeerInfo.of(peerId, nodeId, origin, port, alternativeURL, signature);
		}
	}

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
	 * Creates the Jackson JSON factory, without auto-close the source and target.
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
	 * Creates the Jackson CBOR factory, without auto-close the source and target.
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

	public static String toString(Object object) {
		return toString(object, null);
	}

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

	public static String toPrettyString(Object object) {
		return toPrettyString(object, null);
	}

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

	public static byte[] toBytes(Object object) {
		return toBytes(object, null);
	}

	public static TypeReference<Map<String, Object>> mapType() {
		if (_mapType == null)
			_mapType = new TypeReference<>() { };

		return _mapType;
	}

	public static Map<String, Object> parse(String json, JsonContext context) {
		return parse(json, mapType(), context);
	}

	public static Map<String, Object> parse(String json) {
		return parse(json, mapType(), null);
	}

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

	public static <T> T parse(String json, Class<T> clazz) {
		return parse(json, clazz, null);
	}

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

	public static <T> T parse(String json, TypeReference<T> type) {
		return parse(json, type, null);
	}

	public static Map<String, Object> parse(byte[] cbor, JsonContext context) {
		return parse(cbor, mapType(), context);
	}

	public static Map<String, Object> parse(byte[] cbor) {
		return parse(cbor, mapType(), null);
	}

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

	public static <T> T parse(byte[] cbor, Class<T> clazz) {
		return parse(cbor, clazz, null);
	}

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

	public static <T> T parse(byte[] cbor, TypeReference<T> type) {
		return parse(cbor, type, null);
	}

	public static void initializeBosonJsonModule() {
		if (DatabindCodec.mapper().getRegisteredModuleIds().stream()
				.anyMatch(id -> id.equals(BOSON_JSON_MODULE_NAME)))
			return; // already registered

		DatabindCodec.mapper().registerModule(bosonJsonModule());
	}

	public static class JsonContext extends ContextAttributes.Impl {
		private static final long serialVersionUID = -385397772721358918L;

		protected JsonContext(Map<?,?> shared) {
			super(shared, new HashMap<>());
		}

		protected JsonContext(Map<?,?> shared, Map<Object,Object> nonShared) {
			super(shared, nonShared);
		}

		public static JsonContext empty() {
			return new JsonContext(Collections.emptyMap(), Collections.emptyMap());
		}

		public static JsonContext perCall() {
			return new JsonContext(Collections.emptyMap(), Collections.emptyMap());
		}

		public static JsonContext perCall(Object key, Object value) {
			Map<Object, Object> m = new HashMap<>();
			m.put(key, value);
			return new JsonContext(Collections.emptyMap(), m);
		}

		static JsonContext perCall(Object key1, Object value1, Object key2, Object value2) {
			Map<Object, Object> m = new HashMap<>();
			m.put(key1, value1);
			m.put(key2, value2);
			return new JsonContext(Collections.emptyMap(), m);
		}

		static JsonContext perCall(Object key1, Object value1, Object key2, Object value2, Object key3, Object value3) {
			Map<Object, Object> m = new HashMap<>();
			m.put(key1, value1);
			m.put(key2, value2);
			m.put(key3, value3);
			return new JsonContext(Collections.emptyMap(), m);
		}

		static JsonContext shared(Object key, Object value) {
			return new JsonContext(Map.of(key, value), Collections.emptyMap());
		}

		static JsonContext shared(Object key1, Object value1, Object key2, Object value2) {
			return new JsonContext(Map.of(key1, value1, key2, value2), Collections.emptyMap());
		}

		static JsonContext shared(Object key1, Object value1, Object key2, Object value2, Object key3, Object value3) {
			return new JsonContext(Map.of(key1, value1, key2, value2, key3, value3), Collections.emptyMap());
		}

		@Override
		public Object getAttribute(Object key) {
			if (key == JsonContext.class || key == ContextAttributes.class)
				return this;

			return super.getAttribute(key);
		}

		public boolean isEmpty() {
			return _shared.isEmpty() && _nonShared.isEmpty();
		}

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

		@Override
		public JsonContext withSharedAttributes(Map<?, ?> attributes) {
			return new JsonContext(attributes == null || attributes.isEmpty() ? Collections.emptyMap() : attributes);
		}

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

		@Override
		public JsonContext withPerCallAttribute(Object key, Object value) {
			// First: null value may need masking
			if (value == null) {
				// need to mask nulls to ensure default values won't be showing
				if (_shared.containsKey(key)) {
					value = NULL_SURROGATE;
				} else if ((_nonShared == null) || !_nonShared.containsKey(key)) {
					// except if non-mutable shared list has no entry, we don't care
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