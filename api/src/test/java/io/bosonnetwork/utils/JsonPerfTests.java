package io.bosonnetwork.utils;

import static io.bosonnetwork.utils.Json.isBinaryFormat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Date;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.Base64Variants;
import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.io.SegmentedStringWriter;
import com.fasterxml.jackson.core.util.ByteArrayBuilder;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.cfg.ContextAttributes;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.PeerInfo;
import io.bosonnetwork.Value;
import io.bosonnetwork.crypto.Signature;

// Functional tests for io.bosonnetwork.utils.Json
// Extra:
//   - performance benchmarks: DataBind/ObjectMapper vs. streaming API
public class JsonPerfTests {
	private static ObjectMapper serdeJsonMapper;
	private static CBORMapper serdeCborMapper;
	private static ObjectMapper mixinJsonMapper;
	private static CBORMapper mixinCborMapper;

	@BeforeAll
	public static void setup() {
		SimpleModule serdeModule = new SimpleModule();
		serdeModule.addSerializer(Date.class, new Json.DateSerializer());
		serdeModule.addDeserializer(Date.class, new Json.DateDeserializer());
		serdeModule.addSerializer(Id.class, new Json.IdSerializer());
		serdeModule.addDeserializer(Id.class, new Json.IdDeserializer());
		serdeModule.addSerializer(InetAddress.class, new Json.InetAddressSerializer());
		serdeModule.addDeserializer(InetAddress.class, new Json.InetAddressDeserializer());

		serdeModule.addSerializer(NodeInfo.class, new NodeInfoSerializer());
		serdeModule.addDeserializer(NodeInfo.class, new NodeInfoDeserializer());
		serdeModule.addSerializer(PeerInfo.class, new PeerInfoSerializer());
		serdeModule.addDeserializer(PeerInfo.class, new PeerInfoDeserializer());
		serdeModule.addSerializer(Value.class, new ValueSerializer());
		serdeModule.addDeserializer(Value.class, new ValueDeserializer());

		serdeJsonMapper = JsonMapper.builder(Json.jsonFactory())
				.disable(MapperFeature.AUTO_DETECT_CREATORS,
						MapperFeature.AUTO_DETECT_FIELDS,
						MapperFeature.AUTO_DETECT_GETTERS,
						MapperFeature.AUTO_DETECT_SETTERS,
						MapperFeature.AUTO_DETECT_IS_GETTERS)
				.defaultBase64Variant(Base64Variants.MODIFIED_FOR_URL)
				.addModule(serdeModule)
				.build();

		serdeCborMapper = CBORMapper.builder(Json.cborFactory())
				.disable(MapperFeature.AUTO_DETECT_CREATORS,
						MapperFeature.AUTO_DETECT_FIELDS,
						MapperFeature.AUTO_DETECT_GETTERS,
						MapperFeature.AUTO_DETECT_SETTERS,
						MapperFeature.AUTO_DETECT_IS_GETTERS)
				.defaultBase64Variant(Base64Variants.MODIFIED_FOR_URL)
				.addModule(serdeModule)
				.build();

		SimpleModule mixinModule = new SimpleModule();
		mixinModule.addSerializer(Date.class, new Json.DateSerializer());
		mixinModule.addDeserializer(Date.class, new Json.DateDeserializer());
		mixinModule.addSerializer(Id.class, new Json.IdSerializer());
		mixinModule.addDeserializer(Id.class, new Json.IdDeserializer());
		mixinModule.addSerializer(InetAddress.class, new Json.InetAddressSerializer());
		mixinModule.addDeserializer(InetAddress.class, new Json.InetAddressDeserializer());

		mixinJsonMapper = JsonMapper.builder(Json.jsonFactory())
				.disable(MapperFeature.AUTO_DETECT_CREATORS,
						MapperFeature.AUTO_DETECT_FIELDS,
						MapperFeature.AUTO_DETECT_GETTERS,
						MapperFeature.AUTO_DETECT_SETTERS,
						MapperFeature.AUTO_DETECT_IS_GETTERS)
				.defaultBase64Variant(Base64Variants.MODIFIED_FOR_URL)
				.addModule(mixinModule)
				.addMixIn(NodeInfo.class, NodeInfoMixin.class)
				.addMixIn(PeerInfo.class, PeerInfoMixin.class)
				.addMixIn(Value.class, ValueMixin.class)
				.build();

		mixinCborMapper = CBORMapper.builder(Json.cborFactory())
				.disable(MapperFeature.AUTO_DETECT_CREATORS,
						MapperFeature.AUTO_DETECT_FIELDS,
						MapperFeature.AUTO_DETECT_GETTERS,
						MapperFeature.AUTO_DETECT_SETTERS,
						MapperFeature.AUTO_DETECT_IS_GETTERS)
				.defaultBase64Variant(Base64Variants.MODIFIED_FOR_URL)
				.addModule(mixinModule)
				.addMixIn(NodeInfo.class, NodeInfoMixin.class)
				.addMixIn(PeerInfo.class, PeerInfoMixin.class)
				.addMixIn(Value.class, ValueMixin.class)
				.build();
	}

	@FunctionalInterface
	interface JsonContext {
		Object getAttribute(Object key);

		default <T> T getAttribute(Object key, Class<T> clazz) {
			Object value = getAttribute(key);
			return value == null ? null : clazz.cast(value);
		}

		static JsonContext EMPTY = key -> null;

		static JsonContext empty() {
			return EMPTY;
		}

		static JsonContext withAttribute(Object key, Object value) {
			return k -> Objects.equals(key, k) ? value : null;
		}

		static JsonContext withAttributes(Map<Object, Object> attributes) {
			return attributes::get;
		}
	}

	@SuppressWarnings("unchecked")
	static <T> T parse(String json, Class<T> clazz, JsonContext context) throws IOException {
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

	@SuppressWarnings("unchecked")
	static <T> T parse(byte[] cbor, Class<T> clazz, JsonContext context) throws IOException {
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

	// streaming serialization and deserialization
	static void serializeNodeInfo(JsonGenerator gen, NodeInfo value, JsonContext context) throws IOException {
		var binaryFormat = isBinaryFormat(gen);

		// Format: triple
		//   [id, host, port]
		//
		// host:
		//   text format: IP address string or hostname string
		//   binary format: binary ip address
		gen.writeStartArray();

		if (binaryFormat) {
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, value.getId().bytes(), 0, Id.BYTES);
			byte[] addr = value.getIpAddress().getAddress();
			gen.writeBinary(Base64Variants.MODIFIED_FOR_URL, addr, 0, addr.length); // binary ip address
		} else {
			gen.writeString(value.getId().toBase58String());
			gen.writeString(value.getHost());
		}

		// port
		gen.writeNumber(value.getPort());

		gen.writeEndArray();
	}

	static NodeInfo deserializeNodeInfo(JsonParser p, JsonContext context) throws IOException, JacksonException {
		if (p.currentToken() != JsonToken.START_ARRAY)
			throw MismatchedInputException.from(p, NodeInfo.class, "Invalid NodeInfo, should be an array");

		boolean binaryFormat = isBinaryFormat(p);
		Id id;
		InetAddress addr;
		int port;

		// id
		p.nextToken();
		if (p.currentToken() != JsonToken.VALUE_NULL)
			id = binaryFormat ? Id.of(p.getBinaryValue()) : Id.of(p.getText());
		else
			throw MismatchedInputException.from(p, Id.class, "Invalid NodeInfo: node id can not be null");

		// address
		// text format: IP address string or hostname string
		// binary format: binary ip address or host name string
		p.nextToken();
		if (p.currentToken() != JsonToken.VALUE_NULL) {
			if (binaryFormat)
				addr = p.currentToken() == JsonToken.VALUE_STRING ?
						InetAddress.getByName(p.getText()) : InetAddress.getByAddress(p.getBinaryValue());
			else
				addr = InetAddress.getByName(p.getText());
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

	private static String toString(NodeInfo value, JsonContext context) throws IOException {
		try (SegmentedStringWriter sw = new SegmentedStringWriter(Json.jsonFactory()._getBufferRecycler())) {
			var gen = Json.jsonFactory().createGenerator(sw);
			serializeNodeInfo(gen, value, context);
			gen.close();
			return sw.getAndClear();
		}
	}

	private static byte[] toBytes(NodeInfo value, JsonContext context) throws IOException {
		try (ByteArrayBuilder bb = new ByteArrayBuilder(Json.cborFactory()._getBufferRecycler(), 256)) {
			var gen = Json.cborFactory().createGenerator(bb);
			serializeNodeInfo(gen, value, context);
			gen.close();
			final byte[] result = bb.toByteArray();
			bb.release();
			return result;
		}
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
			serializeNodeInfo(gen, value, JsonContext.empty());
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
			return deserializeNodeInfo(p, JsonContext.empty());
		}
	}

	@JsonFormat(shape = JsonFormat.Shape.ARRAY)
	@JsonPropertyOrder({"id", "a", "p"})
	static abstract class NodeInfoMixin {
		@JsonCreator
		public NodeInfoMixin(@JsonProperty(value = "id", required = true) Id id,
							 @JsonProperty(value = "a", required = true) InetAddress addr,
							 @JsonProperty(value = "p", required = true) int port) {
		}

		@JsonProperty("id")
		public abstract Id getId();

		@JsonProperty("a")
		public abstract InetAddress getIpAddress();

		@JsonProperty("p")
		public abstract int getPort();
	}

	@Test
	void nodeInfoJsonTest() throws IOException {
		// Custom serializer/deserializer
		NodeInfo ni = new NodeInfo(Id.random(), "192.168.8.1", 8080);
		NodeInfo ni2 = null;
		String s = null;

		var start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = serdeJsonMapper.writeValueAsString(ni);
		var end = System.nanoTime();
		System.out.println(s);
		var mappingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with custom serializer: %.2f ms\n", mappingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			ni2 = serdeJsonMapper.readValue(s, NodeInfo.class);
		end = System.nanoTime();
		assertEquals(ni, ni2);
		var mappingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with custom serializer: %.2f ms\n", mappingDeserializeTime / 1000000.0);

		// MixIn
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = mixinJsonMapper.writeValueAsString(ni);
		end = System.nanoTime();
		System.out.println(s);
		System.out.printf(">>>>>>>> Serialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			ni2 = mixinJsonMapper.readValue(s, NodeInfo.class);
		end = System.nanoTime();
		assertEquals(ni, ni2);
		System.out.printf(">>>>>>>> Deserialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		// Streaming with generator
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			s = toString(ni, JsonContext.empty());
		}
		end = System.nanoTime();
		System.out.println(s);
		var streamingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with generator: %.2f ms\n", streamingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			ni2 = parse(s, NodeInfo.class, JsonContext.empty());
		}
		end = System.nanoTime();
		assertEquals(ni, ni2);
		var streamingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with generator: %.2f ms\n", streamingDeserializeTime / 1000000.0);

		System.out.println("\n================ JSON: NodeInfo");
		System.out.printf("  Serialize - Mapping : Streaming = %.2f : %.2f, %.4f\n",
					mappingSerializeTime / 1000000.0, streamingSerializeTime / 1000000.0,
				(double)mappingSerializeTime / (double)streamingSerializeTime);
		System.out.printf("Deserialize - Mapping : Streaming = %.2f : %.2f, %.4f\n\n",
				mappingDeserializeTime / 1000000.0, streamingDeserializeTime / 1000000.0,
				(double)mappingDeserializeTime / (double)streamingDeserializeTime);
	}

	@Test
	void nodeInfoCborTest() throws IOException {
		// Custom serializer/deserializer

		NodeInfo ni = new NodeInfo(Id.random(), "192.168.8.1", 8080);
		NodeInfo ni2 = null;
		byte[] s = null;

		var start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = serdeCborMapper.writeValueAsBytes(ni);
		var end = System.nanoTime();
		System.out.println(Hex.encode(s));
		var mappingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with custom serializer: %.2f ms\n", mappingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			ni2 = serdeCborMapper.readValue(s, NodeInfo.class);
		end = System.nanoTime();
		assertEquals(ni, ni2);
		var mappingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with custom serializer: %.2f ms\n", mappingDeserializeTime / 1000000.0);

		// MixIn
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = mixinCborMapper.writeValueAsBytes(ni);
		end = System.nanoTime();
		System.out.println(Hex.encode(s));
		System.out.printf(">>>>>>>> Serialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			ni2 = mixinCborMapper.readValue(s, NodeInfo.class);
		end = System.nanoTime();
		assertEquals(ni, ni2);
		System.out.printf(">>>>>>>> Deserialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		// Streaming with generator
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			s = toBytes(ni, JsonContext.empty());
		}
		end = System.nanoTime();
		System.out.println(Hex.encode(s));
		var streamingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with generator: %.2f ms\n", streamingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			ni2 = parse(s, NodeInfo.class, JsonContext.empty());
		}
		end = System.nanoTime();
		assertEquals(ni, ni2);
		var streamingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with generator: %.2f ms\n", streamingDeserializeTime / 1000000.0);

		System.out.println("\n================ CBOR: NodeInfo");
		System.out.printf("  Serialize - Mapping : Streaming = %.2f : %.2f, %.4f\n",
				mappingSerializeTime / 1000000.0, streamingSerializeTime / 1000000.0,
				(double)mappingSerializeTime / (double)streamingSerializeTime);
		System.out.printf("Deserialize - Mapping : Streaming = %.2f : %.2f, %.4f\n\n",
				mappingDeserializeTime / 1000000.0, streamingDeserializeTime / 1000000.0,
				(double)mappingDeserializeTime / (double)streamingDeserializeTime);
	}

	static void serializePeerInfo(JsonGenerator gen, PeerInfo value, JsonContext context) throws IOException {
		boolean binaryFormat = isBinaryFormat(gen);

		// Format: 6-tuple
		//   [peerId, nodeId, originNodeId, port, alternativeURL, signature]
		// If omit the peer id, format:
		//   [null, nodeId, originNodeId, port, alternativeURL, signature]

		gen.writeStartArray();

		// peer id
		Boolean attr = (Boolean) context.getAttribute(PeerInfo.ATTRIBUTE_OMIT_PEER_ID);
		boolean omitPeerId = attr != null && attr;
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

	static PeerInfo deserializePeerInfo(JsonParser p, JsonContext context) throws IOException, JacksonException {
		if (p.currentToken() != JsonToken.START_ARRAY)
			throw MismatchedInputException.from(p, PeerInfo.class, "Invalid PeerInfo, should be an array");

		boolean binaryFormat = isBinaryFormat(p);

		Id peerId;
		Id nodeId;
		Id origin = null;
		int port;
		String alternativeURL;
		byte[] signature;

		// peer id
		p.nextToken();
		if (p.currentToken() != JsonToken.VALUE_NULL) {
			peerId = binaryFormat ? Id.of(p.getBinaryValue()) : Id.of(p.getText());
		} else {
			// peer id is omitted, should retrieve it from the context
			peerId = (Id) context.getAttribute(PeerInfo.ATTRIBUTE_PEER_ID);
			if (peerId == null)
				throw MismatchedInputException.from(p, Id.class, "Invalid PeerInfo: peer id can not be null");
		}

		// node id
		p.nextToken();
		if (p.currentToken() != JsonToken.VALUE_NULL)
			nodeId = binaryFormat ? Id.of(p.getBinaryValue()) : Id.of(p.getText());
		else
			throw MismatchedInputException.from(p, Id.class, "Invalid PeerInfo: node id can not be null");

		// origin node id
		p.nextToken();
		if (p.currentToken() != JsonToken.VALUE_NULL)
			origin = binaryFormat ? Id.of(p.getBinaryValue()) : Id.of(p.getText());

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

	static String toString(PeerInfo value, JsonContext context) throws IOException {
		try (SegmentedStringWriter sw = new SegmentedStringWriter(Json.jsonFactory()._getBufferRecycler())) {
			var gen = Json.jsonFactory().createGenerator(sw);
			serializePeerInfo(gen, value, context);
			gen.close();
			return sw.getAndClear();
		}
	}

	static byte[] toBytes(PeerInfo value, JsonContext context) throws IOException {
		try (ByteArrayBuilder bb = new ByteArrayBuilder(Json.cborFactory()._getBufferRecycler())) {
			var gen = Json.cborFactory().createGenerator(bb);
			serializePeerInfo(gen, value, context);
			gen.close();
			final byte[] result = bb.toByteArray();
			bb.release();
			return result;
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
			serializePeerInfo(gen, value, provider::getAttribute);
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
			return deserializePeerInfo(p, ctx::getAttribute);
		}
	}

	@JsonFormat(shape = JsonFormat.Shape.ARRAY)
	@JsonPropertyOrder({"id", "n", "o", "p", "alt", "sig"})
	static abstract class PeerInfoMixin {
		@JsonCreator
		public PeerInfoMixin(@JsonProperty(value = "id", required = true) Id peerId,
							 @JsonProperty(value = "n", required = true) Id nodeId,
							 @JsonProperty(value = "o") Id origin,
							 @JsonProperty(value = "p", required = true) int port,
							 @JsonProperty(value = "alt") String alternativeURL,
							 @JsonProperty(value = "sig", required = true) byte[] signature) { }

		@JsonProperty("id")
		public abstract Id getId();

		@JsonProperty("n")
		public abstract Id getNodeId();

		@JsonProperty("o")
		public abstract Id getOrigin();

		@JsonProperty("p")
		public abstract int getPort();

		@JsonProperty("alt")
		public abstract String getAlternativeURL();

		@JsonProperty("sig")
		public abstract byte[] getSignature();
	}

	@Test
	void peerInfoJsonTest() throws IOException {
		Signature.KeyPair keypair = Signature.KeyPair.random();
		PeerInfo pi = PeerInfo.create(keypair, Id.random(), Id.random(), 8888, "https://echo.bns.io");
		PeerInfo pi2 = null;
		String s = null;

		// Custom serializer/deserializer
		var start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = serdeJsonMapper.writeValueAsString(pi);
		var end = System.nanoTime();
		System.out.println(s);
		var mappingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with custom serializer: %.2f ms\n", mappingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			pi2 = serdeJsonMapper.readValue(s, PeerInfo.class);
		end = System.nanoTime();
		assertEquals(pi, pi2);
		var mappingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with custom serializer: %.2f ms\n", mappingDeserializeTime / 1000000.0);

		// MixIn
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = mixinJsonMapper.writeValueAsString(pi);
		end = System.nanoTime();
		System.out.println(s);
		System.out.printf(">>>>>>>> Serialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			pi2 = mixinJsonMapper.readValue(s, PeerInfo.class);
		end = System.nanoTime();
		assertEquals(pi, pi2);
		System.out.printf(">>>>>>>> Deserialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		// Streaming with generator
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			s = toString(pi, JsonContext.empty());
		}
		end = System.nanoTime();
		System.out.println(s);
		var streamingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with generator: %.2f ms\n", streamingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			pi2 = parse(s, PeerInfo.class, JsonContext.empty());
		}
		end = System.nanoTime();
		assertEquals(pi, pi2);
		var streamingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with generator: %.2f ms\n", streamingDeserializeTime / 1000000.0);

		System.out.println("\n================ JSON: PeerInfo/full");
		System.out.printf("  Serialize - Mapping : Streaming = %.2f : %.2f, %.4f\n",
				mappingSerializeTime / 1000000.0, streamingSerializeTime / 1000000.0,
				(double) mappingSerializeTime / (double) streamingSerializeTime);
		System.out.printf("Deserialize - Mapping : Streaming = %.2f : %.2f, %.4f\n\n",
				mappingDeserializeTime / 1000000.0, streamingDeserializeTime / 1000000.0,
				(double) mappingDeserializeTime / (double) streamingDeserializeTime);
	}

	@Test
	void peerInfoOptionalJsonTest() throws IOException {
		// Custom serializer/deserializer
		Signature.KeyPair keypair = Signature.KeyPair.random();
		var peerId = Id.of(keypair.publicKey().bytes());
		PeerInfo pi = PeerInfo.create(keypair, Id.random(), null, 8888, null);
		PeerInfo pi2 = null;
		String s = null;

		var start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = serdeJsonMapper.writer(ContextAttributes.getEmpty().withPerCallAttribute(PeerInfo.ATTRIBUTE_OMIT_PEER_ID, true)).writeValueAsString(pi);
		var end = System.nanoTime();
		System.out.println(s);
		var mappingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with custom serializer: %.2f ms\n", mappingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			pi2 = serdeJsonMapper.reader(ContextAttributes.getEmpty().withPerCallAttribute(PeerInfo.ATTRIBUTE_PEER_ID, peerId)).readValue(s, PeerInfo.class);
		end = System.nanoTime();
		assertEquals(pi, pi2);
		var mappingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with custom serializer: %.2f ms\n", mappingDeserializeTime / 1000000.0);

		// MixIn
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = mixinJsonMapper.writer(ContextAttributes.getEmpty().withPerCallAttribute(PeerInfo.ATTRIBUTE_OMIT_PEER_ID, true)).writeValueAsString(pi);
		end = System.nanoTime();
		System.out.println(s);
		System.out.printf(">>>>>>>> Serialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			pi2 = mixinJsonMapper.reader(ContextAttributes.getEmpty().withPerCallAttribute(PeerInfo.ATTRIBUTE_PEER_ID, peerId)).readValue(s, PeerInfo.class);
		end = System.nanoTime();
		assertEquals(pi, pi2);
		System.out.printf(">>>>>>>> Deserialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		// Streaming with generator
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			s = toString(pi, JsonContext.withAttribute(PeerInfo.ATTRIBUTE_OMIT_PEER_ID, true));
		}
		end = System.nanoTime();
		System.out.println(s);
		var streamingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with generator: %.2f ms\n", streamingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			pi2 = parse(s, PeerInfo.class, JsonContext.withAttribute(PeerInfo.ATTRIBUTE_PEER_ID, peerId));
		}
		end = System.nanoTime();
		assertEquals(pi, pi2);
		var streamingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with generator: %.2f ms\n", streamingDeserializeTime / 1000000.0);

		System.out.println("\n================ JSON: PeerInfo/optional");
		System.out.printf("  Serialize - Mapping : Streaming = %.2f : %.2f, %.4f\n",
				mappingSerializeTime / 1000000.0, streamingSerializeTime / 1000000.0,
				(double)mappingSerializeTime / (double)streamingSerializeTime);
		System.out.printf("Deserialize - Mapping : Streaming = %.2f : %.2f, %.4f\n\n",
				mappingDeserializeTime / 1000000.0, streamingDeserializeTime / 1000000.0,
				(double)mappingDeserializeTime / (double)streamingDeserializeTime);
	}

	@Test
	void peerInfoCborTest() throws IOException {
		// Custom serializer/deserializer
		PeerInfo pi = PeerInfo.create(Signature.KeyPair.random(), Id.random(), Id.random(), 8888, "https://echo.bns.io");
		PeerInfo pi2 = null;
		byte[] s = null;

		var start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = serdeCborMapper.writeValueAsBytes(pi);
		var end = System.nanoTime();
		System.out.println(Hex.encode(s));
		var mappingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with custom serializer: %.2f ms\n", mappingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			pi2 = serdeCborMapper.readValue(s, PeerInfo.class);
		end = System.nanoTime();
		assertEquals(pi, pi2);
		var mappingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with custom serializer: %.2f ms\n", mappingDeserializeTime / 1000000.0);

		// MixIn
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = mixinCborMapper.writeValueAsBytes(pi);
		end = System.nanoTime();
		System.out.println(Hex.encode(s));
		System.out.printf(">>>>>>>> Serialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			pi2 = mixinCborMapper.readValue(s, PeerInfo.class);
		end = System.nanoTime();
		assertEquals(pi, pi2);
		System.out.printf(">>>>>>>> Deserialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		// Streaming with generator
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			s = toBytes(pi, JsonContext.empty());
		}
		end = System.nanoTime();
		System.out.println(Hex.encode(s));
		var streamingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with generator: %.2f ms\n", streamingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			pi2 = parse(s, PeerInfo.class, JsonContext.empty());
		}
		end = System.nanoTime();
		assertEquals(pi, pi2);
		var streamingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with generator: %.2f ms\n", streamingDeserializeTime / 1000000.0);

		System.out.println("\n================ CBOR: PeerInfo/full");
		System.out.printf("  Serialize - Mapping : Streaming = %.2f : %.2f, %.4f\n",
				mappingSerializeTime / 1000000.0, streamingSerializeTime / 1000000.0,
				(double)mappingSerializeTime / (double)streamingSerializeTime);
		System.out.printf("Deserialize - Mapping : Streaming = %.2f : %.2f, %.4f\n\n",
				mappingDeserializeTime / 1000000.0, streamingDeserializeTime / 1000000.0,
				(double)mappingDeserializeTime / (double)streamingDeserializeTime);
	}

	@Test
	void peerInfoOptionalCborTest() throws IOException {
		// Custom serializer/deserializer
		Signature.KeyPair keypair = Signature.KeyPair.random();
		var peerId = Id.of(keypair.publicKey().bytes());
		PeerInfo pi = PeerInfo.create(keypair, Id.random(), null, 8888, null);
		PeerInfo pi2 = null;
		byte[] s = null;

		var start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = serdeCborMapper.writer(ContextAttributes.getEmpty().withPerCallAttribute(PeerInfo.ATTRIBUTE_OMIT_PEER_ID, true)).writeValueAsBytes(pi);
		var end = System.nanoTime();
		System.out.println(Hex.encode(s));
		var mappingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with custom serializer: %.2f ms\n", mappingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			pi2 = serdeCborMapper.reader(ContextAttributes.getEmpty().withPerCallAttribute(PeerInfo.ATTRIBUTE_PEER_ID, peerId)).readValue(s, PeerInfo.class);
		end = System.nanoTime();
		assertEquals(pi, pi2);
		var mappingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with custom serializer: %.2f ms\n", mappingDeserializeTime / 1000000.0);

		// MixIn
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = mixinCborMapper.writer(ContextAttributes.getEmpty().withPerCallAttribute(PeerInfo.ATTRIBUTE_OMIT_PEER_ID, true)).writeValueAsBytes(pi);
		end = System.nanoTime();
		System.out.println(Hex.encode(s));
		System.out.printf(">>>>>>>> Serialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			pi2 = mixinCborMapper.reader(ContextAttributes.getEmpty().withPerCallAttribute(PeerInfo.ATTRIBUTE_PEER_ID, peerId)).readValue(s, PeerInfo.class);
		end = System.nanoTime();
		assertEquals(pi, pi2);
		System.out.printf(">>>>>>>> Deserialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		// Streaming with generator
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			s = toBytes(pi, JsonContext.withAttribute(PeerInfo.ATTRIBUTE_OMIT_PEER_ID, true));
		}
		end = System.nanoTime();
		System.out.println(Hex.encode(s));
		var streamingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with generator: %.2f ms\n", streamingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			pi2 = parse(s, PeerInfo.class, JsonContext.withAttribute(PeerInfo.ATTRIBUTE_PEER_ID, peerId));
		}
		end = System.nanoTime();
		assertEquals(pi, pi2);
		var streamingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with generator: %.2f ms\n", streamingDeserializeTime / 1000000.0);

		System.out.println("\n================ CBOR: PeerInfo/optional");
		System.out.printf("  Serialize - Mapping : Streaming = %.2f : %.2f, %.4f\n",
				mappingSerializeTime / 1000000.0, streamingSerializeTime / 1000000.0,
				(double)mappingSerializeTime / (double)streamingSerializeTime);
		System.out.printf("Deserialize - Mapping : Streaming = %.2f : %.2f, %.4f\n\n",
				mappingDeserializeTime / 1000000.0, streamingDeserializeTime / 1000000.0,
				(double)mappingDeserializeTime / (double)streamingDeserializeTime);
	}

	static void serializeValue(JsonGenerator gen, Value value, JsonContext context) throws IOException {
		boolean binaryFormat = isBinaryFormat(gen);
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

	static Value deserializeValue(JsonParser p, JsonContext context) throws IOException, JacksonException {
		if (p.currentToken() != JsonToken.START_OBJECT)
			throw MismatchedInputException.from(p, Value.class, "Invalid Value: should be an object");

		boolean binaryFormat = isBinaryFormat(p);

		Id publicKey = null;
		Id recipient = null;
		byte[] nonce = null;
		int sequenceNumber = 0;
		byte[] signature = null;
		byte[] data = null;

		while (p.nextToken() != JsonToken.END_OBJECT) {
			String fieldName = p.currentName();
			p.nextToken();
			switch (fieldName) {
				case "k":
					if (p.currentToken() != JsonToken.VALUE_NULL)
						publicKey = binaryFormat ? Id.of(p.getBinaryValue()) : Id.of(p.getText());
					break;
				case "rec":
					if (p.currentToken() != JsonToken.VALUE_NULL)
						recipient = binaryFormat ? Id.of(p.getBinaryValue()) : Id.of(p.getText());
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

	static String toString(Value value, JsonContext context) throws IOException {
		try (SegmentedStringWriter sw = new SegmentedStringWriter(Json.jsonFactory()._getBufferRecycler())) {
			var gen = Json.jsonFactory().createGenerator(sw);
			serializeValue(gen, value, context);
			gen.close();
			return sw.getAndClear();
		}
	}

	static byte[] toBytes(Value value, JsonContext context) throws IOException {
		try (ByteArrayBuilder bb = new ByteArrayBuilder(Json.cborFactory()._getBufferRecycler(), value.getData().length + 256)) {
			var gen = Json.cborFactory().createGenerator(bb);
			serializeValue(gen, value, context);
			gen.close();
			final byte[] result = bb.toByteArray();
			bb.release();
			return result;
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
			serializeValue(gen, value, JsonContext.empty());
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
			return deserializeValue(p, JsonContext.empty());
		}
	}

	@JsonPropertyOrder({"k", "rec", "n", "seq", "sig", "v"})
	static abstract class ValueMixin {
		@JsonCreator
		public ValueMixin(@JsonProperty(value = "k") Id publicKey,
						  @JsonProperty(value = "rec") Id recipient,
						  @JsonProperty(value = "n") byte[] nonce,
						  @JsonProperty(value = "seq") int sequenceNumber,
						  @JsonProperty(value = "sig") byte[] signature,
						  @JsonProperty(value = "v", required = true) byte[] data) {
		}

		@JsonProperty("k")
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public abstract Id getPublicKey();

		@JsonProperty("rec")
		@JsonInclude(JsonInclude.Include.NON_NULL)
		public abstract Id getRecipient();

		@JsonProperty("n")
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		public abstract byte[] getNonce();

		@JsonProperty("seq")
		public abstract int getSequenceNumber();

		@JsonProperty("sig")
		@JsonInclude(JsonInclude.Include.NON_EMPTY)
		public abstract byte[] getSignature();

		@JsonProperty("v")
		public abstract byte[] getData();
	}

	@ParameterizedTest
	@ValueSource(strings = {"immutable", "signed", "encrypted"})
	void valueJsonTest(String mode) throws Exception {
		// Custom serializer/deserializer
		Value v = switch (mode) {
			case "immutable" -> Value.createValue("Hello from bosonnetwork!\n".repeat(10).getBytes());
			case "signed" -> Value.createSignedValue("Hello from bosonnetwork!\n".repeat(10).getBytes());
			case "encrypted" -> Value.createEncryptedValue(Id.of(Signature.KeyPair.random().publicKey().bytes()),
					"Hello from bosonnetwork!\n".repeat(10).getBytes());
			default -> throw new IllegalArgumentException("Unexpected value: " + mode);
		};
		Value v2 = null;
		String s = null;

		var start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = serdeJsonMapper.writeValueAsString(v);
		var end = System.nanoTime();
		System.out.println(s);
		var mappingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with custom serializer: %.2f ms\n", mappingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			v2 = serdeJsonMapper.readValue(s, Value.class);
		end = System.nanoTime();
		assertEquals(v, v2);
		var mappingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with custom serializer: %.2f ms\n", mappingDeserializeTime / 1000000.0);

		// MixIn
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = mixinJsonMapper.writeValueAsString(v);
		end = System.nanoTime();
		System.out.println(s);
		System.out.printf(">>>>>>>> Serialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			v2 = mixinJsonMapper.readValue(s, Value.class);
		end = System.nanoTime();
		assertEquals(v, v2);
		System.out.printf(">>>>>>>> Deserialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		// Streaming with generator
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			s = toString(v, JsonContext.empty());
		}
		end = System.nanoTime();
		System.out.println(s);
		var streamingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with generator: %.2f ms\n", streamingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			v2 = parse(s, Value.class, JsonContext.empty());
		}
		end = System.nanoTime();
		assertEquals(v, v2);
		var streamingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with generator: %.2f ms\n", streamingDeserializeTime / 1000000.0);

		System.out.printf("\n================ JSON: Value/%s\n", mode);
		System.out.printf("  Serialize - Mapping : Streaming = %.2f : %.2f, %.4f\n",
				mappingSerializeTime / 1000000.0, streamingSerializeTime / 1000000.0,
				(double)mappingSerializeTime / (double)streamingSerializeTime);
		System.out.printf("Deserialize - Mapping : Streaming = %.2f : %.2f, %.4f\n\n",
				mappingDeserializeTime / 1000000.0, streamingDeserializeTime / 1000000.0,
				(double)mappingDeserializeTime / (double)streamingDeserializeTime);
	}

	@ParameterizedTest
	@ValueSource(strings = {"immutable", "signed", "encrypted"})
	void valueCborTest(String mode) throws Exception {
		// Custom serializer/deserializer
		Value v = switch (mode) {
			case "immutable" -> Value.createValue("Hello from bosonnetwork!\n".repeat(10).getBytes());
			case "signed" -> Value.createSignedValue("Hello from bosonnetwork!\n".repeat(10).getBytes());
			case "encrypted" -> Value.createEncryptedValue(Id.of(Signature.KeyPair.random().publicKey().bytes()),
					"Hello from bosonnetwork!\n".repeat(10).getBytes());
			default -> throw new IllegalArgumentException("Unexpected value: " + mode);
		};
		Value v2 = null;
		byte[] s = null;

		var start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = serdeCborMapper.writeValueAsBytes(v);
		var end = System.nanoTime();
		System.out.println(Hex.encode(s));
		var mappingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with custom serializer: %.2f ms\n", mappingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			v2 = serdeCborMapper.readValue(s, Value.class);
		end = System.nanoTime();
		assertEquals(v, v2);
		var mappingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with custom serializer: %.2f ms\n", mappingDeserializeTime / 1000000.0);

		// MixIn
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			s = mixinCborMapper.writeValueAsBytes(v);
		end = System.nanoTime();
		System.out.println(Hex.encode(s));
		System.out.printf(">>>>>>>> Serialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++)
			v2 = mixinCborMapper.readValue(s, Value.class);
		end = System.nanoTime();
		assertEquals(v, v2);
		System.out.printf(">>>>>>>> Deserialize with MixIn: %.2f ms\n", (end - start) / 1000000.0);

		// Streaming with generator
		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			s = toBytes(v, JsonContext.empty());
		}
		end = System.nanoTime();
		System.out.println(Hex.encode(s));
		var streamingSerializeTime = end - start;
		System.out.printf(">>>>>>>> Serialize with generator: %.2f ms\n", streamingSerializeTime / 1000000.0);

		start = System.nanoTime();
		for (int i = 0; i < 1000000; i++) {
			v2 = parse(s, Value.class, JsonContext.empty());
		}
		end = System.nanoTime();
		assertEquals(v, v2);
		var streamingDeserializeTime = end - start;
		System.out.printf(">>>>>>>> Deserialize with generator: %.2f ms\n", streamingDeserializeTime / 1000000.0);

		System.out.printf("\n================ CBOR: Value/%s\n", mode);
		System.out.printf("  Serialize - Mapping : Streaming = %.2f : %.2f, %.4f\n",
				mappingSerializeTime / 1000000.0, streamingSerializeTime / 1000000.0,
				(double)mappingSerializeTime / (double)streamingSerializeTime);
		System.out.printf("Deserialize - Mapping : Streaming = %.2f : %.2f, %.4f\n\n",
				mappingDeserializeTime / 1000000.0, streamingDeserializeTime / 1000000.0,
				(double)mappingDeserializeTime / (double)streamingDeserializeTime);
	}
}