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
import java.util.Objects;
import java.util.TimeZone;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.io.SegmentedStringWriter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.util.ByteArrayBuilder;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.cfg.ContextAttributes;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import io.vertx.core.json.jackson.DatabindCodec;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;

/**
 * Common JSON utility methods for JSON process.
 */
public class Json {
	public static final TypeReference<HashMap<String, Object>> MAP_TYPE = new TypeReference<>() { };

	private final static SimpleModule bosonModule = createBosonModule();

	private final static ObjectMapper objectMapper = createObjectMapper();
	private final static CBORMapper cborMapper = createCBORMapper();
	private final static JsonFactory jsonFactory = createJSONFactory();
	private final static CBORFactory cborFactory = createCBORFactory();

	private final static TimeZone UTC = TimeZone.getTimeZone("UTC");
	private final static String ISO_8601 = "yyyy-MM-dd'T'HH:mm:ss'Z'";
	private final static String ISO_8601_WITH_MILLISECONDS = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

	public static boolean isTextFormat(JsonParser p) {
		// Now we only sport JSON, CBOR and TOML formats, CBOR is the only binary format
		return !(p instanceof CBORParser);
	}

	public static boolean isTextFormat(JsonGenerator gen) {
		// Now we only sport JSON, CBOR and TOML formats, CBOR is the only binary format
		return !(gen instanceof CBORGenerator);
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
			if (isTextFormat(gen))
				gen.writeString(value.toBase58String());
			else
				gen.writeBinary(value.bytes());
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
		public Id deserialize(JsonParser p, DeserializationContext ctx) throws IOException, JacksonException {
			boolean textFormat = isTextFormat(p);
			JsonToken expected = textFormat ? JsonToken.VALUE_STRING : JsonToken.VALUE_EMBEDDED_OBJECT;
			if (p.currentToken() == expected)
				return textFormat ? Id.of(p.getText()) : Id.of(p.getBinaryValue());
			else
				throw ctx.wrongTokenException(p, Id.class, expected, "Invalid id representation");
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
			if (isTextFormat(gen))
				gen.writeString(value.getHostAddress());
			else
				gen.writeBinary(value.getAddress()); // binary ip address
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
		public InetAddress deserialize(JsonParser p, DeserializationContext ctx) throws IOException, JacksonException {
			boolean textFormat = isTextFormat(p);
			JsonToken expected = textFormat ? JsonToken.VALUE_STRING : JsonToken.VALUE_EMBEDDED_OBJECT;
			if (p.currentToken() == expected)
				return textFormat ? InetAddress.getByName(p.getText()) : InetAddress.getByAddress(p.getBinaryValue());
			else
				throw ctx.wrongTokenException(p, InetAddress.class, expected, "Invalid InetAddress representation");
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
			if (isTextFormat(gen))
				gen.writeString(getDateFormat().format(value));
			else
				gen.writeNumber(value.getTime());
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
		public Date deserialize(JsonParser p, DeserializationContext ctx)
				throws IOException, JsonProcessingException {
			if (isTextFormat(p)) {
				if (!p.getCurrentToken().equals(JsonToken.VALUE_STRING))
					throw ctx.wrongTokenException(p, String.class, JsonToken.VALUE_STRING, "Invalid datetime string");

				String dateStr = p.getValueAsString();
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
			} else {
				if (!p.getCurrentToken().equals(JsonToken.VALUE_NUMBER_INT))
					throw ctx.wrongTokenException(p, Date.class, JsonToken.VALUE_NUMBER_INT, "Invalid datetime value");

				// epoch milliseconds
				return new Date(p.getValueAsLong());
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
			Optimized.serializeNodeInfo(gen, value, JsonContext.empty());
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
		public NodeInfo deserialize(JsonParser p, DeserializationContext ctx) throws IOException, JacksonException {
			return Optimized.deserializeNodeInfo(p, JsonContext.empty());
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
			Optimized.serializePeerInfo(gen, value, provider::getAttribute);
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
		public PeerInfo deserialize(JsonParser p, DeserializationContext ctx) throws IOException, JacksonException {
			return Optimized.deserializePeerInfo(p, ctx::getAttribute);
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
			Optimized.serializeValue(gen, value, JsonContext.empty());
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
		public Value deserialize(JsonParser p, DeserializationContext ctx) throws IOException, JacksonException {
			return Optimized.deserializeValue(p, JsonContext.empty());
		}
	}

	protected static SimpleModule createBosonModule() {
		String name = "io.bosonnetwork.utils.json.module";
		SimpleModule module = new SimpleModule(name);
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
		return module;
	}

	/**
	 * Creates the Jackson JSON factory, without auto-close the source and target.
	 *
	 * @return the {@code JsonFactory} object.
	 */
	protected static JsonFactory createJSONFactory() {
		JsonFactory factory = new JsonFactory();
		factory.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
		factory.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
		return factory;
	}

	/**
	 * Creates the Jackson CBOR factory, without auto-close the source and target.
	 *
	 * @return the {@code CBORFactory} object.
	 */
	protected static CBORFactory createCBORFactory() {
		CBORFactory factory = new CBORFactory();
		factory.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
		factory.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
		return factory;
	}

	/**
	 * Creates the Jackson object mapper, with basic Boson types support.
	 *
	 * @return the new {@code ObjectMapper} object.
	 */
	protected static ObjectMapper createObjectMapper() {
		return JsonMapper.builder(createJSONFactory()).disable(
				MapperFeature.AUTO_DETECT_CREATORS,
				MapperFeature.AUTO_DETECT_FIELDS,
				MapperFeature.AUTO_DETECT_GETTERS,
				MapperFeature.AUTO_DETECT_SETTERS,
				MapperFeature.AUTO_DETECT_IS_GETTERS)
			// .defaultDateFormat(getDateFormat())
			.defaultBase64Variant(Base64Variants.MODIFIED_FOR_URL)
			.addModule(bosonModule)
			.build();
	}

	/**
	 * Creates the Jackson CBOR mapper, with basic Boson types support.
	 *
	 * @return the new {@code CBORMapper} object.
	 */
	protected static CBORMapper createCBORMapper() {
		return CBORMapper.builder(createCBORFactory()).disable(
				MapperFeature.AUTO_DETECT_CREATORS,
				MapperFeature.AUTO_DETECT_FIELDS,
				MapperFeature.AUTO_DETECT_GETTERS,
				MapperFeature.AUTO_DETECT_SETTERS,
				MapperFeature.AUTO_DETECT_IS_GETTERS)
			// .defaultDateFormat(getDateFormat())
			.addModule(bosonModule)
			.build();
	}

	private static class ContextAttributesImpl extends ContextAttributes.Impl {
		private static final long serialVersionUID = 203154485301320453L;

		private final JsonContext ctx;

		protected ContextAttributesImpl(JsonContext ctx) {
			super(Collections.emptyMap());
			this.ctx = ctx;
		}

		public static ContextAttributes of(JsonContext ctx) {
			return new ContextAttributesImpl(ctx);
		}

		@Override
		public Object getAttribute(Object key) {
			// non-shared first
			if (_nonShared != null) {
				Object ob = _nonShared.get(key);
				if (ob != null) {
					if (ob == NULL_SURROGATE)
						return null;

					return ob;
				}
			}

			// then JsonContext
			Object ob = ctx.getAttribute(key);
			if (ob != null)
				return ob;

			// then shared
			return _shared.get(key);
		}
	}

	public static String toString(Object object, JsonContext context) {
		try {
			if (JsonContext.isEmpty(context))
				return objectMapper.writeValueAsString(object);
			else
				return objectMapper.writer(ContextAttributesImpl.of(context)).writeValueAsString(object);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("object can not be serialized", e);
		}
	}

	public static String toString(Object object) {
		return toString(object, null);
	}

	public static String toPrettyString(Object object, JsonContext context) {
		try {
			ObjectWriter writer = JsonContext.isEmpty(context) ?
					objectMapper.writerWithDefaultPrettyPrinter() :
					objectMapper.writerWithDefaultPrettyPrinter().with(ContextAttributesImpl.of(context));

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
			if (JsonContext.isEmpty(context))
				return cborMapper.writeValueAsBytes(object);
			else
				return cborMapper.writer(ContextAttributesImpl.of(context)).writeValueAsBytes(object);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("object can not be serialized", e);
		}
	}

	public static byte[] toBytes(Object object) {
		return toBytes(object, null);
	}

	public static Map<String, Object> parse(String json, JsonContext context) {
		return parse(json, MAP_TYPE, context);
	}

	public static Map<String, Object> parse(String json) {
		return parse(json, MAP_TYPE, null);
	}

	public static <T> T parse(String json, Class<T> clazz, JsonContext context) {
		try {
			if (JsonContext.isEmpty(context))
				return objectMapper.readValue(json, clazz);
			else
				return objectMapper.reader(ContextAttributesImpl.of(context)).forType(clazz).readValue(json);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("json can not be parsed", e);
		}
	}

	public static <T> T parse(String json, Class<T> clazz) {
		return parse(json, clazz, null);
	}

	public static <T> T parse(String json, TypeReference<T> type, JsonContext context) {
		try {
			if (JsonContext.isEmpty(context))
				return objectMapper.readValue(json, type);
			else
				return objectMapper.reader(ContextAttributesImpl.of(context)).forType(type).readValue(json);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("json can not be parsed", e);
		}
	}

	public static <T> T parse(String json, TypeReference<T> type) {
		return parse(json, type, null);
	}

	public static Map<String, Object> parse(byte[] cbor, JsonContext context) {
		return parse(cbor, MAP_TYPE, context);
	}

	public static Map<String, Object> parse(byte[] cbor) {
		return parse(cbor, MAP_TYPE, null);
	}

	public static <T> T parse(byte[] cbor, Class<T> clazz, JsonContext context) {
		try {
			if (JsonContext.isEmpty(context))
				return cborMapper.readValue(cbor, clazz);
			else
				return cborMapper.reader(ContextAttributesImpl.of(context)).forType(clazz).readValue(cbor);
		} catch (IOException e) {
			throw new IllegalArgumentException("cbor can not be parsed", e);
		}
	}

	public static <T> T parse(byte[] cbor, Class<T> clazz) {
		return parse(cbor, clazz, null);
	}

	public static <T> T parse(byte[] cbor, TypeReference<T> type, JsonContext context) {
		try {
			if (JsonContext.isEmpty(context))
				return cborMapper.readValue(cbor, type);
			else
				return cborMapper.reader(ContextAttributesImpl.of(context)).forType(type).readValue(cbor);
		} catch (IOException e) {
			throw new IllegalArgumentException("cbor can not be parsed", e);
		}
	}

	public static <T> T parse(byte[] cbor, TypeReference<T> type) {
		return parse(cbor, type, null);
	}

	public static void initializeBosonJsonModule() {
		if (DatabindCodec.mapper().getRegisteredModuleIds().stream()
				.anyMatch(id -> id.equals(bosonModule.getModuleName())))
			return; // already registered

		DatabindCodec.mapper().registerModule(bosonModule);
	}

	public static ObjectMapper objectMapper() {
		return objectMapper;
	}

	public static CBORMapper cborMapper() {
		return cborMapper;
	}

	public static JsonFactory jsonFactory() {
		return jsonFactory;
	}

	public static CBORFactory cborFactory() {
		return cborFactory;
	}

	@FunctionalInterface
	public interface JsonContext {
		Object getAttribute(Object key);

		default <T> T getAttribute(Object key, Class<T> clazz) {
			Object value = getAttribute(key);
			return value == null ? null : clazz.cast(value);
		}

		JsonContext EMPTY = key -> null;

		static JsonContext empty() {
			return EMPTY;
		}

		static JsonContext withAttribute(Object key, Object value) {
			return k -> Objects.equals(key, k) ? value : null;
		}

		static JsonContext withAttributes(Map<Object, Object> attributes) {
			return attributes::get;
		}

		static boolean isEmpty(JsonContext context) {
			return context == null || context == JsonContext.empty();
		}
	}

	public static final class Optimized {
		@SuppressWarnings("unchecked")
		public static <T> T parse(String json, Class<T> clazz, JsonContext context) throws IOException {
			if (context == null)
				context = JsonContext.empty();

			try (JsonParser parser = Json.jsonFactory().createParser(json)) {
				parser.nextToken();
				if (clazz == NodeInfo.class)
					return (T) deserializeNodeInfo(parser, context);
				else if (clazz == PeerInfo.class)
					return (T) deserializePeerInfo(parser, context);
				else if (clazz == Value.class)
					return (T) deserializeValue(parser, context);
				else
					throw new IllegalStateException();
			}
		}

		public static <T> T parse(String json, Class<T> clazz) throws IOException {
			return parse(json, clazz, null);
		}

		@SuppressWarnings("unchecked")
		public static <T> T parse(byte[] cbor, Class<T> clazz, JsonContext context) throws IOException {
			if (context == null)
				context = JsonContext.empty();

			try (JsonParser parser = Json.cborFactory().createParser(cbor)) {
				parser.nextToken();
				if (clazz == NodeInfo.class)
					return (T) deserializeNodeInfo(parser, context);
				else if (clazz == PeerInfo.class)
					return (T) deserializePeerInfo(parser, context);
				else if (clazz == Value.class)
					return (T) deserializeValue(parser, context);
				else
					throw new IllegalStateException();
			}
		}

		public static <T> T parse(byte[] cbor, Class<T> clazz) throws IOException {
			return parse(cbor, clazz, null);
		}

		private static void serializeNodeInfo(JsonGenerator gen, NodeInfo value, JsonContext context) throws IOException {
			var textFormat = isTextFormat(gen);

			// Format: triple
			//   [id, host, port]
			gen.writeStartArray();

			if (textFormat)
				gen.writeString(value.getId().toBase58String());
			else
				gen.writeBinary(value.getId().bytes());

			// host ip address or name
			// text format: IP address string or hostname string
			// binary format: binary ip address
			if (textFormat)
				gen.writeString(value.getAddress().getHostString());
			else
				gen.writeBinary(value.getAddress().getAddress().getAddress()); // binary ip address

			// port
			gen.writeNumber(value.getAddress().getPort());

			gen.writeEndArray();
		}

		private static NodeInfo deserializeNodeInfo(JsonParser p, JsonContext context) throws IOException, JacksonException {
			if (p.currentToken() != JsonToken.START_ARRAY)
				throw MismatchedInputException.from(p, NodeInfo.class, "Invalid NodeInfo, should be an array");

			boolean textFormat = isTextFormat(p);
			Id id;
			InetAddress addr;
			int port;

			// id
			p.nextToken();
			if (p.currentToken() != JsonToken.VALUE_NULL)
				id = textFormat ? Id.of(p.getText()) : Id.of(p.getBinaryValue());
			else
				throw MismatchedInputException.from(p, Id.class, "Invalid NodeInfo: node id can not be null");

			// address
			// text format: IP address string or hostname string
			// binary format: binary ip address or host name string
			p.nextToken();
			if (p.currentToken() != JsonToken.VALUE_NULL) {
				if (textFormat)
					addr = InetAddress.getByName(p.getText());
				else
					addr = p.currentToken() == JsonToken.VALUE_STRING ?
							InetAddress.getByName(p.getText()) : InetAddress.getByAddress(p.getBinaryValue());
			} else {
				throw MismatchedInputException.from(p, InetAddress.class, "Invalid NodeInfo: node address can not be null");
			}

			// port
			p.nextToken();
			port = p.getIntValue();
			if (port < 0 || port > 65535)
				throw InvalidFormatException.from(p, Integer.class, "Invalid NodeInfo: port " + port + " is out of range");

			if (p.nextToken() != JsonToken.END_ARRAY)
				throw MismatchedInputException.from(p, NodeInfo.class, "Invalid NodeInfo: too many elements in array");

			return new NodeInfo(id, addr, port);
		}

		public static String toString(NodeInfo value, JsonContext context) throws IOException {
			try (SegmentedStringWriter sw = new SegmentedStringWriter(Json.jsonFactory()._getBufferRecycler())) {
				var gen = Json.jsonFactory().createGenerator(sw);
				serializeNodeInfo(gen, value, context == null ? JsonContext.empty() : context);
				gen.close();
				return sw.getAndClear();
			}
		}

		public static String toString(NodeInfo value) throws IOException {
			return toString(value, JsonContext.empty());
		}

		public static byte[] toBytes(NodeInfo value, JsonContext context) throws IOException {
			try (ByteArrayBuilder bb = new ByteArrayBuilder(Json.cborFactory()._getBufferRecycler(), 256)) {
				var gen = Json.cborFactory().createGenerator(bb);
				serializeNodeInfo(gen, value, context == null ? JsonContext.empty() : context);
				gen.close();
				final byte[] result = bb.toByteArray();
				bb.release();
				return result;
			}
		}

		public static byte[] toBytes(NodeInfo value) throws IOException {
			return toBytes(value, JsonContext.empty());
		}

		private static void serializePeerInfo(JsonGenerator gen, PeerInfo value, JsonContext context) throws IOException {
			boolean textFormat = isTextFormat(gen);

			// Format: 6-tuple
			//   [peerId, nodeId, originNodeId, port, alternativeURL, signature]
			// If omit the peer id, format:
			//   [null, nodeId, originNodeId, port, alternativeURL, signature]

			gen.writeStartArray();

			// peer id
			Boolean attr = (Boolean) context.getAttribute("omitPeerId");
			boolean omitPeerId = attr != null && attr;
			if (!omitPeerId) {
				if (textFormat)
					gen.writeString(value.getId().toBase58String());
				else
					gen.writeBinary(value.getId().bytes());
			} else {
				gen.writeNull();
			}

			// node id
			if (textFormat)
				gen.writeString(value.getNodeId().toBase58String());
			else
				gen.writeBinary(value.getNodeId().bytes());

			// origin node id
			if (value.isDelegated()) {
				if (textFormat)
					gen.writeString(value.getOrigin().toBase58String());
				else
					gen.writeBinary(value.getOrigin().bytes());
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
			//if (textFormat)
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, sig, 0, sig.length);
			//else
			//	gen.writeBinary(sig);

			gen.writeEndArray();
		}

		private static PeerInfo deserializePeerInfo(JsonParser p, JsonContext context) throws IOException, JacksonException {
			if (p.currentToken() != JsonToken.START_ARRAY)
				throw MismatchedInputException.from(p, PeerInfo.class, "Invalid PeerInfo, should be an array");

			boolean textFormat = isTextFormat(p);

			Id peerId;
			Id nodeId;
			Id origin = null;
			int port;
			String alternativeURL;
			byte[] signature;

			// peer id
			p.nextToken();
			if (p.currentToken() != JsonToken.VALUE_NULL) {
				peerId = textFormat ? Id.of(p.getText()) : Id.of(p.getBinaryValue());
			} else {
				// peer id is omitted, should retrieve it from the context
				peerId = (Id) context.getAttribute("peerId");
				if (peerId == null)
					throw MismatchedInputException.from(p, Id.class, "Invalid PeerInfo: peer id can not be null");
			}

			// node id
			p.nextToken();
			if (p.currentToken() != JsonToken.VALUE_NULL)
				nodeId = textFormat ? Id.of(p.getText()) : Id.of(p.getBinaryValue());
			else
				throw MismatchedInputException.from(p, Id.class, "Invalid PeerInfo: node id can not be null");

			// origin node id
			p.nextToken();
			if (p.currentToken() != JsonToken.VALUE_NULL)
				origin = textFormat ? Id.of(p.getText()) : Id.of(p.getBinaryValue());

			// port
			p.nextToken();
			port = p.getIntValue();
			if (port < 0 || port > 65535)
				throw InvalidFormatException.from(p, Integer.class, "Invalid PeerInfo: port " + port + " is out of range");

			// alternative url
			p.nextToken();
			alternativeURL = p.currentToken() == JsonToken.VALUE_NULL ? null : p.getText();

			// signature
			p.nextToken();
			if (p.currentToken() != JsonToken.VALUE_NULL)
				signature = p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
			else
				throw MismatchedInputException.from(p, byte[].class, "Invalid PeerInfo: signature can not be null");

			if (p.nextToken() != JsonToken.END_ARRAY)
				throw MismatchedInputException.from(p, PeerInfo.class, "Invalid PeerInfo: too many elements in array");

			return PeerInfo.of(peerId, nodeId, origin, port, alternativeURL, signature);
		}

		public static String toString(PeerInfo value, JsonContext context) throws IOException {
			try (SegmentedStringWriter sw = new SegmentedStringWriter(Json.jsonFactory()._getBufferRecycler())) {
				var gen = Json.jsonFactory().createGenerator(sw);
				serializePeerInfo(gen, value, context == null ? JsonContext.empty() : context);
				gen.close();
				return sw.getAndClear();
			}
		}

		public static String toString(PeerInfo value) throws IOException {
			return toString(value, null);
		}

		public static byte[] toBytes(PeerInfo value, JsonContext context) throws IOException {
			try (ByteArrayBuilder bb = new ByteArrayBuilder(Json.cborFactory()._getBufferRecycler())) {
				var gen = Json.cborFactory().createGenerator(bb);
				serializePeerInfo(gen, value, context == null ? JsonContext.empty() : context);
				gen.close();
				final byte[] result = bb.toByteArray();
				bb.release();
				return result;
			}
		}

		public static byte[] toBytes(PeerInfo value) throws IOException {
			return toBytes(value, null);
		}

		private static void serializeValue(JsonGenerator gen, Value value, JsonContext context) throws IOException {
			boolean textFormat = isTextFormat(gen);
			gen.writeStartObject();

			if (value.getPublicKey() != null) {
				// public key
				if (textFormat)
					gen.writeStringField("k", value.getPublicKey().toBase58String());
				else
					gen.writeBinaryField("k", value.getPublicKey().bytes());

				// recipient
				if (value.getRecipient() != null) {
					if (textFormat)
						gen.writeStringField("rec", value.getRecipient().toBase58String());
					else
						gen.writeBinaryField("rec", value.getRecipient().bytes());
				}

				// nonce
				byte[] binary = value.getNonce();
				if (binary != null) {
					gen.writeFieldName("n");
					gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, binary, 0, binary.length);
				}

				// sequence number
				if (value.getSequenceNumber() >= 0) {
					gen.writeFieldName("seq");
					gen.writeNumber(value.getSequenceNumber());
				}

				// signature
				binary = value.getSignature();
				if (value.getSignature() != null) {
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

		private static Value deserializeValue(JsonParser p, JsonContext context) throws IOException, JacksonException {
			if (p.currentToken() != JsonToken.START_OBJECT)
				throw MismatchedInputException.from(p, Value.class, "Invalid Value: should be an object");

			boolean textFormat = isTextFormat(p);

			Id publicKey = null;
			Id recipient = null;
			byte[] nonce = null;
			int sequenceNumber = -1;
			byte[] signature = null;
			byte[] data = null;

			while (p.nextToken() != JsonToken.END_OBJECT) {
				String fieldName = p.currentName();
				p.nextToken();
				switch (fieldName) {
					case "k":
						if (p.currentToken() != JsonToken.VALUE_NULL)
							publicKey = textFormat ? Id.of(p.getText()) : Id.of(p.getBinaryValue());
						break;
					case "rec":
						if (p.currentToken() != JsonToken.VALUE_NULL)
							recipient = textFormat ? Id.of(p.getText()) : Id.of(p.getBinaryValue());
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
						else
							throw MismatchedInputException.from(p, byte[].class, "Invalid Value: data can not be null");
						break;
					default:
						p.skipChildren();
				}
			}

			return Value.of(publicKey, recipient, nonce, sequenceNumber, signature, data);
		}

		public static String toString(Value value, JsonContext context) throws IOException {
			try (SegmentedStringWriter sw = new SegmentedStringWriter(Json.jsonFactory()._getBufferRecycler())) {
				var gen = Json.jsonFactory().createGenerator(sw);
				serializeValue(gen, value, context == null ? JsonContext.empty() : context);
				gen.close();
				return sw.getAndClear();
			}
		}

		public static String toString(Value value) throws IOException {
			return toString(value, null);
		}

		public static byte[] toBytes(Value value, JsonContext context) throws IOException {
			try (ByteArrayBuilder bb = new ByteArrayBuilder(Json.cborFactory()._getBufferRecycler(), value.getData().length + 256)) {
				var gen = Json.cborFactory().createGenerator(bb);
				serializeValue(gen, value, context == null ? JsonContext.empty() : context);
				gen.close();
				final byte[] result = bb.toByteArray();
				bb.release();
				return result;
			}
		}

		public static byte[] toBytes(Value value) throws IOException {
			return toBytes(value, null);
		}
	}
}