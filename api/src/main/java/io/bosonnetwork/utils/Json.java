package io.bosonnetwork.utils;

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;

import io.bosonnetwork.Id;
import io.vertx.core.json.jackson.DatabindCodec;

/**
 * Common JSON utility methods for JSON process.
 */
public class Json {
	public static final TypeReference<HashMap<String, Object>> MAP_TYPE = new TypeReference<HashMap<String, Object>>() {};

	private final static ObjectMapper objectMapper = createObjectMapper();
	private final static CBORMapper cborMapper = createCBORMapper();
	private final static JsonFactory jsonFactory = createJSONFactory();
	private final static CBORFactory cborFactory = createCBORFactory();

	private final static TimeZone UTC = TimeZone.getTimeZone("UTC");
	private final static String ISO_8601 = "yyyy-MM-dd'T'HH:mm:ss'Z'";
	private final static String ISO_8601_WITH_MILLISECONDS = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";

	/**
	 * Id serializer for CBOR format.
	 */
	static class IdBytesSerializer extends StdSerializer<Id> {
		private static final long serialVersionUID = -1352630613285716899L;

		protected IdBytesSerializer() {
			this(null);
		}

		protected IdBytesSerializer(Class<Id> t) {
			super(t);
		}

		@Override
		public void serialize(Id value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			gen.writeBinary(value.bytes());
		}
	}

	/**
	 * Id deserializer for CBOR format.
	 */
	static class IdBytesDeserializer extends StdDeserializer<Id> {
		private static final long serialVersionUID = 8977820243454538303L;

		protected IdBytesDeserializer() {
			this(null);
		}

		protected IdBytesDeserializer(Class<?> vc) {
			super(vc);
		}

		@Override
		public Id deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
			return Id.of(p.getBinaryValue());
		}
	}

	/**
	 * Id serializer for JSON format.
	 */
	static class IdStringSerializer extends StdSerializer<Id> {
		private static final long serialVersionUID = -1352630613285716899L;

		protected IdStringSerializer() {
			this(null);
		}

		protected IdStringSerializer(Class<Id> t) {
			super(t);
		}

		@Override
		public void serialize(Id value, JsonGenerator gen, SerializerProvider provider) throws IOException {
			gen.writeString(value.toString());
		}
	}

	/**
	 * Id deserializer for JSON format.
	 */
	static class IdStringDeserializer extends StdDeserializer<Id> {
		private static final long serialVersionUID = 8977820243454538303L;

		protected IdStringDeserializer() {
			this(null);
		}

		protected IdStringDeserializer(Class<?> vc) {
			super(vc);
		}

		@Override
		public Id deserialize(JsonParser p, DeserializationContext ctxt) throws IOException, JacksonException {
			return Id.of(p.getText());
		}
	}

	/**
	 * Date and time deserializer from RFC3339 / ISO8601 format.
	 */
	static class DateDeserializer extends StdDeserializer<Date> {
		private static final long serialVersionUID = -4252894239212420927L;

		public DateDeserializer() {
			this(null);
		}

		public DateDeserializer(Class<?> t) {
			super(t);
		}

		@Override
		public Date deserialize(JsonParser p, DeserializationContext ctxt)
				throws IOException, JsonProcessingException {
			JsonToken token = p.getCurrentToken();
			if (!token.equals(JsonToken.VALUE_STRING))
				throw ctxt.weirdStringException(p.getText(),
						Date.class, "Invalid datetime string");

			String dateStr = p.getValueAsString();
			try {
				return getDateFormat().parse(dateStr);
			} catch (ParseException ignore) {
			}

			// Fail-back to ISO 8601 format.
			try {
				return getFailbackDateFormat().parse(dateStr);
			} catch (ParseException e) {
				throw ctxt.weirdStringException(p.getText(),
						Date.class, "Invalid datetime string");
			}
		}
	}

	static class BytesSerializer extends StdSerializer<byte[]> {
		private static final long serialVersionUID = -3272555472993394308L;

		protected BytesSerializer() {
			this(null);
		}

		protected BytesSerializer(Class<byte[]> t) {
			super(t);
		}

		@Override
		public void serialize(byte[] value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value, 0, value.length);
		}
	}

	static class BytesDeserializer extends StdDeserializer<byte[]> {
		private static final long serialVersionUID = 1859211926952692672L;

		public BytesDeserializer() {
			this(null);
		}

		public BytesDeserializer(Class<?> t) {
			super(t);
		}

		@Override
		public byte[] deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
			return p.getBinaryValue(Base64Variants.MODIFIED_FOR_URL);
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
		SimpleModule module = new SimpleModule();
		module.addDeserializer(Date.class, new DateDeserializer());
		module.addSerializer(Id.class, new IdStringSerializer());
		module.addDeserializer(Id.class, new IdStringDeserializer());
		module.addSerializer(byte[].class, new BytesSerializer());
		module.addDeserializer(byte[].class, new BytesDeserializer());

		ObjectMapper mapper = JsonMapper.builder(createJSONFactory()).disable(
				MapperFeature.AUTO_DETECT_CREATORS,
				MapperFeature.AUTO_DETECT_FIELDS,
				MapperFeature.AUTO_DETECT_GETTERS,
				MapperFeature.AUTO_DETECT_SETTERS,
				MapperFeature.AUTO_DETECT_IS_GETTERS)
			.defaultDateFormat(getDateFormat())
			.addModule(module)
			.build();

		return mapper;
	}

	/**
	 * Creates the Jackson CBOR mapper, with basic Boson types support.
	 *
	 * @return the new {@code CBORMapper} object.
	 */
	protected static CBORMapper createCBORMapper() {
		SimpleModule module = new SimpleModule();
		module.addDeserializer(Date.class, new DateDeserializer());
		module.addSerializer(Id.class, new IdBytesSerializer());
		module.addDeserializer(Id.class, new IdBytesDeserializer());

		CBORMapper mapper = CBORMapper.builder(createCBORFactory()).disable(
				MapperFeature.AUTO_DETECT_CREATORS,
				MapperFeature.AUTO_DETECT_FIELDS,
				MapperFeature.AUTO_DETECT_GETTERS,
				MapperFeature.AUTO_DETECT_SETTERS,
				MapperFeature.AUTO_DETECT_IS_GETTERS)
			.defaultDateFormat(getDateFormat())
			.addModule(module)
			.build();

		return mapper;
	}

	public static String toString(Object object) {
		try {
			return objectMapper.writeValueAsString(object);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("object can not be serialized", e);
		}
	}

	public static String toPrettyString(Object object) {
		try {
			return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(object);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("object can not be serialized", e);
		}

	}

	public static byte[] toBytes(Object object) {
		try {
			return cborMapper.writeValueAsBytes(object);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("object can not be serialized", e);
		}
	}

	public static Map<String, Object> parse(String json) {
		try {
			return objectMapper.readValue(json, MAP_TYPE);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("json can not be parsed", e);
		}
	}

	public static <T> T parse(String json, Class<T> clazz) {
		try {
			return objectMapper.readValue(json, clazz);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("json can not be parsed", e);
		}
	}

	public static <T> T parse(String json, TypeReference<T> type) {
		try {
			return objectMapper.readValue(json, type);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("json can not be parsed", e);
		}
	}

	public static Map<String, Object> parse(byte[] cbor) {
		try {
			return cborMapper.readValue(cbor, MAP_TYPE);
		} catch (IOException e) {
			throw new IllegalArgumentException("cbor can not be parsed", e);
		}
	}

	public static <T> T parse(byte[] cbor, Class<T> clazz) {
		try {
			return cborMapper.readValue(cbor, clazz);
		} catch (IOException e) {
			throw new IllegalArgumentException("cbor can not be parsed", e);
		}
	}

	public static <T> T parse(byte[] cbor, TypeReference<T> type) {
		try {
			return cborMapper.readValue(cbor, type);
		} catch (IOException e) {
			throw new IllegalArgumentException("cbor can not be parsed", e);
		}
	}

	public static void initializeBosonJsonModule() {
		String name = "io.bosonnetwork.utils.json.module";

		if (DatabindCodec.mapper().getRegisteredModuleIds().stream().anyMatch(id -> id.equals(name)))
			return; // already registered

		SimpleModule module = new SimpleModule(name);
		module.addDeserializer(Date.class, new DateDeserializer());
		module.addSerializer(Id.class, new IdStringSerializer());
		module.addDeserializer(Id.class, new IdStringDeserializer());
		DatabindCodec.mapper().registerModule(module);
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

}
