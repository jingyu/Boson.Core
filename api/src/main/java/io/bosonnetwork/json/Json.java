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

package io.bosonnetwork.json;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.vertx.core.json.jackson.DatabindCodec;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.json.internal.DateDeserializer;
import io.bosonnetwork.json.internal.DateSerializer;
import io.bosonnetwork.json.internal.IdDeserializer;
import io.bosonnetwork.json.internal.IdSerializer;
import io.bosonnetwork.json.internal.InetAddressDeserializer;
import io.bosonnetwork.json.internal.InetAddressSerializer;
import io.bosonnetwork.json.internal.NodeInfoDeserializer;
import io.bosonnetwork.json.internal.NodeInfoSerializer;
import io.bosonnetwork.json.internal.PeerInfoDeserializer;
import io.bosonnetwork.json.internal.PeerInfoSerializer;
import io.bosonnetwork.json.internal.ValueDeserializer;
import io.bosonnetwork.json.internal.ValueSerializer;


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
 *   <li>Context-aware serialization using {@link JsonContext} for configuration of serialization details.</li>
 * </ul>
 */
public class Json {
	private static final String BOSON_JSON_MODULE_NAME = "io.bosonnetwork.utils.json.module";

	/** Pre-configured Base64 encoder for URL-safe encoding without padding. */
	public static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
	/** Pre-configured Base64 decoder for URL-safe decoding. */
	public static final Base64.Decoder BASE64_DECODER = Base64.getUrlDecoder();

	private static TypeReference<Map<String, Object>> _mapType;

	private static SimpleModule _bosonJsonModule;

	private static JsonFactory _jsonFactory;
	private static CBORFactory _cborFactory;

	private static ObjectMapper _objectMapper;
	private static CBORMapper _cborMapper;
	private static YAMLMapper _yamlMapper;

	/**
	 * Returns the Jackson module for Boson types.
	 *
	 * @return the {@link SimpleModule} object
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
	 * Returns the Jackson JSON factory.
	 *
	 * @return the {@link JsonFactory} object
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
	 * Returns the Jackson CBOR factory.
	 *
	 * @return the {@link CBORFactory} object
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
		try {
			return objectMapper().writeValueAsString(object);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("object can not be serialized", e);
		}
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
		try {
			return objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(object);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("object can not be serialized", e);
		}
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
		try {
			return cborMapper().writeValueAsBytes(object);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("object can not be serialized", e);
		}
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
		return parse(json, mapType());
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
		try {
			return objectMapper().readValue(json, clazz);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("json can not be parsed", e);
		}
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
		try {
			return objectMapper().readValue(json, type);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("json can not be parsed", e);
		}
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
		return parse(cbor, mapType());
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
		try {
			return cborMapper().readValue(cbor, clazz);
		} catch (IOException e) {
			throw new IllegalArgumentException("cbor can not be parsed", e);
		}
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
		try {
			return cborMapper().readValue(cbor, type);
		} catch (IOException e) {
			throw new IllegalArgumentException("cbor can not be parsed", e);
		}
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
}