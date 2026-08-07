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

package io.bosonnetwork;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.bosonnetwork.json.Json;
import io.bosonnetwork.utils.FileUtils;

class NodeConfigurationTests {
	private static final String PRIVATE_KEY =
			"5P46autoGX9fifw4dV9c97xJTwPV7XKuxsq1sXZvc56uVFHsxPXLHqnjPL6vr8MU8XSmicv4XdBA6cMX6g8fg12E";

	private static Vertx vertx;

	@BeforeAll
	static void setup() {
		vertx = Vertx.vertx();
	}

	@AfterAll
	static void teardown() {
		if (vertx != null)
			vertx.close();
	}

	private static NodeConfiguration.Builder baseBuilder() {
		return NodeConfiguration.builder()
				.vertx(vertx)
				.host4("192.168.1.100")
				.generateKeyPair();
	}

	@Test
	void testInterfaceRoundTrip() {
		NodeConfiguration config = NodeConfiguration.builder()
				.vertx(vertx)
				.networkInterface4("eth0")
				.networkInterface6("wlan0")
				.generateKeyPair()
				.build();

		assertEquals("eth0", config.listen().networkInterface4());
		assertEquals("wlan0", config.listen().networkInterface6());

		// Round-trip through the builder to ensure Vert.x is provided
		NodeConfiguration restored = NodeConfiguration.builder()
				.vertx(vertx)
				.fromMap(config.toMap())
				.build();

		assertEquals("eth0", restored.listen().networkInterface4());
		assertEquals("wlan0", restored.listen().networkInterface6());
	}

	@Test
	void testEveryComponentSurvivesTheMapRoundTrip(@TempDir Path tempDir) {
		// The whole configuration at once, compared with record equality, so that a component the
		// writer drops or mangles fails here rather than in whichever consumer happens to read it.
		// dataDir in particular is only exercised when it is actually set.
		NodeConfiguration config = NodeConfiguration.builder()
				.vertx(vertx)
				.host4("192.168.1.100")
				.host6("2001:db8::9")
				.port(39002)
				.privateKey(PRIVATE_KEY)
				.dataDir(tempDir)
				.database("jdbc:sqlite:round-trip.db", 4)
				.databaseSchemaName("kademlia")
				.alpha(5)
				.k(32)
				.replacements(16)
				.concurrentTasks(256)
				.addBootstrap(Id.random(), "203.0.113.5", 1234, "2001:db8::1", 5678)
				.spamThrottling(false)
				.suspiciousNodeDetector(false)
				.developerMode(true)
				.build();

		NodeConfiguration restored = NodeConfiguration.builder()
				.vertx(vertx)
				.fromMap(config.toMap())
				.build();

		assertEquals(config, restored);
	}

	@Test
	void testDataDirSurvivesTheYamlRoundTrip(@TempDir Path tempDir) throws Exception {
		// What the shell's --save-config does: write the map as YAML, then load it again. A Path
		// written as an object rather than a string comes back as a file: URI, which parses into a
		// relative directory named "file:" without raising anything.
		NodeConfiguration config = NodeConfiguration.builder()
				.vertx(vertx)
				.host4("192.168.1.100")
				.privateKey(PRIVATE_KEY)
				.dataDir(tempDir)
				.build();

		String yaml = Json.yamlMapper().writeValueAsString(config.toMap());
		Map<String, Object> reloaded = Json.yamlMapper().readValue(yaml, Json.mapType());

		NodeConfiguration restored = NodeConfiguration.builder()
				.vertx(vertx)
				.fromMap(reloaded)
				.build();

		assertEquals(config.dataDir(), restored.dataDir());
		assertEquals(config, restored);
	}

	@Test
	void testFromMapIsAnOverlayNotAReplacement() {
		// The launcher and the shell layer command line arguments and a configuration file onto one
		// builder, so a document that is silent about a setting must leave it alone.
		// fromMap comes last on purpose: everything asserted below was set BEFORE it, and the
		// one-key document must not disturb any of it.
		Id id = Id.random();
		Path dataDir = FileUtils.normalizePath(Path.of("/tmp/boson-overlay"));
		NodeConfiguration config = NodeConfiguration.builder()
				.vertx(vertx)
				.host4("192.168.1.100")
				.dataDir(dataDir)
				.generateKeyPair()
				.addBootstrap(id, "203.0.113.5", 1234)
				.developerMode(true)
				.fromMap(Map.of("port", 39002))
				.build();

		assertEquals(39002, config.listen().port());
		assertEquals("192.168.1.100", config.listen().host4());
		assertEquals(dataDir, config.dataDir());
		assertEquals(Set.of(NodeInfo.of(id, "203.0.113.5", 1234)), config.bootstraps());
		assertTrue(config.security().developerMode());
	}

	@Test
	void testDataDirDefaultsWhenNobodyNamesOne() {
		// Non-null throughout: a node always needs somewhere to put the routing table caches and the
		// database file, so neither the builder nor a document leaves it unset.
		assertEquals(NodeConfiguration.Builder.defaultDataDir(), baseBuilder().build().dataDir());

		NodeConfiguration fromDocument = NodeConfiguration.builder()
				.vertx(vertx)
				.fromMap(Map.of("host4", "192.168.1.1", "privateKey", PRIVATE_KEY))
				.build();
		assertEquals(NodeConfiguration.Builder.defaultDataDir(), fromDocument.dataDir());
	}

	@Test
	void testFromMapWithoutPrivateKeyLeavesTheCallerToSupplyOne() {
		// The shell generates a key pair when the configuration file has none; reading the file must
		// not fail before it gets the chance.
		NodeConfiguration.Builder builder = NodeConfiguration.builder()
				.vertx(vertx)
				.fromMap(Map.of("host4", "192.168.1.1"));

		assertFalse(builder.hasKeyPair());
		assertEquals("192.168.1.1", builder.generateKeyPair().build().listen().host4());
	}

	@Test
	void testFromMapReplacesAnAddressFamilyAsAUnit() {
		// A document naming host4 wins over an interface4 the caller set, rather than combining with
		// it into the "both specified" error.
		NodeConfiguration config = NodeConfiguration.builder()
				.vertx(vertx)
				.networkInterface4("eth0")
				.generateKeyPair()
				.fromMap(Map.of("host4", "192.168.1.1"))
				.build();

		assertEquals("192.168.1.1", config.listen().host4());
		assertNull(config.listen().networkInterface4());
	}

	@Test
	void testFromMapValidatesHostsAsTheSettersDo() {
		NodeConfiguration.Builder builder = NodeConfiguration.builder().vertx(vertx);

		assertThrows(IllegalArgumentException.class,
				() -> builder.fromMap(Map.of("host4", "this-is-not-an-address")));
		// An IPv6 address in the IPv4 slot is a configuration error, not an address to bind.
		assertThrows(IllegalArgumentException.class, () -> builder.fromMap(Map.of("host4", "::1")));
	}

	@Test
	void testEmptyHostIsTreatedAsAbsent() {
		// "host4:" with no value is how a document says "not configured"; an empty string reaching a
		// bind call would silently mean the wildcard address instead.
		NodeConfiguration config = NodeConfiguration.builder()
				.vertx(vertx)
				.generateKeyPair()
				.fromMap(Map.of("host4", "", "interface4", "", "host6", "2001:db8::1"))
				.build();

		assertNull(config.listen().host4());
		assertNull(config.listen().networkInterface4());
		// Stored in the expanded form the setter resolves it to, not as written.
		assertEquals("2001:db8:0:0:0:0:0:1", config.listen().host6());
	}

	@Test
	void testBootstrapRoundTripDualStack() {
		Id id = Id.random();
		NodeConfiguration config = baseBuilder()
				.addBootstrap(id, "203.0.113.5", 1234, "2001:db8::1", 5678)
				.build();

		NodeInfo bootstrap = config.bootstraps().iterator().next();
		assertTrue(bootstrap.hasMultiAddresses());

		// Round-trip through the YAML-style map representation.
		NodeConfiguration restored = NodeConfiguration.builder()
				.vertx(vertx)
				.fromMap(config.toMap())
				.build();

		assertEquals(config.bootstraps(), restored.bootstraps());
	}

	@Test
	void testBootstrapRoundTripIPv6Only() {
		Id id = Id.random();
		NodeConfiguration config = baseBuilder()
				.addBootstrap(NodeInfo.of(id, "2001:db8::2", 5678))
				.build();

		NodeInfo bootstrap = config.bootstraps().iterator().next();
		assertTrue(bootstrap.hasAddress6());
		assertFalse(bootstrap.hasAddress4());

		// An IPv6-only node is written as a 3-tuple; loading must not force it into the IPv4 slot.
		NodeConfiguration restored = NodeConfiguration.builder()
				.vertx(vertx)
				.fromMap(config.toMap())
				.build();

		assertEquals(config.bootstraps(), restored.bootstraps());
	}

	@Test
	void testBootstrapLoadIsFamilyAwareRegardlessOfOrder() {
		// Hand-written config that lists the IPv6 address before the IPv4 address.
		Id id = Id.random();
		List<Object> entry = new ArrayList<>();
		entry.add(id.toString());
		entry.add("2001:db8::3");
		entry.add(5678);
		entry.add("203.0.113.7");
		entry.add(1234);

		NodeConfiguration config = baseBuilder()
				.fromMap(Map.of("bootstraps", List.of(entry)))
				.build();

		NodeInfo bootstrap = config.bootstraps().iterator().next();
		assertEquals(id, bootstrap.getId());
		assertEquals(1234, bootstrap.getPort4());
		assertEquals(5678, bootstrap.getPort6());
	}

	@Test
	void testBothHostAndInterfaceIPv4() {
		NodeConfiguration.Builder builder = NodeConfiguration.builder()
				.vertx(vertx)
				.host4("192.168.1.1")
				.networkInterface4("eth0")
				.generateKeyPair();

		IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
		assertTrue(ex.getMessage().contains("Both IPv4 host and network interface are specified"), "Actual message: " + ex.getMessage());
	}

	@Test
	void testBothHostAndInterfaceIPv6() {
		NodeConfiguration.Builder builder = NodeConfiguration.builder()
				.vertx(vertx)
				.host6("2001:0db8:85a3:0000:0000:8a2e:0370:7356")
				.networkInterface6("eth0")
				.generateKeyPair();

		IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
		assertTrue(ex.getMessage().contains("Both IPv6 host and network interface are specified"), "Actual message: " + ex.getMessage());
	}

	@Test
	void testNoNetworkConfig() {
		NodeConfiguration.Builder builder = NodeConfiguration.builder()
				.vertx(vertx)
				.generateKeyPair();

		IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
		assertTrue(ex.getMessage().contains("either IPv4 or IPv6 host or network interface must be provided"), "Actual message: " + ex.getMessage());
	}

	@Test
	void testMissingPrivateKey() {
		NodeConfiguration.Builder builder = NodeConfiguration.builder()
				.vertx(vertx)
				.host4("192.168.1.1");

		IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
		assertTrue(ex.getMessage().contains("The node's key pair must be provided"), "Actual message: " + ex.getMessage());
	}

	@Test
	void testVertxNotProvided() {
		// Never fall back to Vertx.vertx(): that hands back a configuration owning an event loop
		// group nobody asked for and nobody closes.
		NodeConfiguration.Builder builder = NodeConfiguration.builder()
				.host4("192.168.1.1")
				.generateKeyPair();

		IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
		assertTrue(ex.getMessage().contains("Vert.x instance must be provided"), "Actual message: " + ex.getMessage());
	}

	@Test
	void testKademliaDefaults() {
		NodeConfiguration config = baseBuilder().build();
		assertEquals(new NodeConfiguration.KademliaOptions(NodeConfiguration.DEFAULT_ALPHA,
				NodeConfiguration.DEFAULT_K, NodeConfiguration.DEFAULT_REPLACEMENTS,
				NodeConfiguration.DEFAULT_CONCURRENT_TASKS), config.kademlia());
	}

	@Test
	void testKademliaParameters() {
		NodeConfiguration config = baseBuilder()
				.alpha(5)
				.k(32)
				.replacements(8)
				.concurrentTasks(256)
				.build();

		NodeConfiguration restored = NodeConfiguration.builder()
				.vertx(vertx)
				.fromMap(config.toMap())
				.build();

		assertEquals(new NodeConfiguration.KademliaOptions(5, 32, 8, 256), restored.kademlia());
	}

	@Test
	void testKademliaBlockIsReadWhole() {
		// The keys a named block leaves out fall back to their own defaults, not to the builder's
		// values, exactly as a service's configuration block does.
		NodeConfiguration config = baseBuilder()
				.alpha(5)
				.k(32)
				.fromMap(Map.of("kademlia", Map.of("alpha", 7)))
				.build();

		assertEquals(7, config.kademlia().alpha());
		assertEquals(NodeConfiguration.DEFAULT_K, config.kademlia().k());
	}

	@Test
	void testKademliaRejectsValuesBelowOne() {
		// A value the operator wrote down is reported, never quietly replaced by the default: a node
		// silently running parameters nobody asked for is worse than a node that refuses to start.
		NodeConfiguration.Builder builder = baseBuilder();
		assertThrows(IllegalArgumentException.class, () -> builder.alpha(0));
		assertThrows(IllegalArgumentException.class, () -> builder.k(-1));
		assertThrows(IllegalArgumentException.class, () -> builder.replacements(0));
		assertThrows(IllegalArgumentException.class, () -> builder.concurrentTasks(0));

		assertThrows(IllegalArgumentException.class,
				() -> builder.fromMap(Map.of("kademlia", Map.of("alpha", 0))));
		assertThrows(IllegalArgumentException.class,
				() -> new NodeConfiguration.KademliaOptions(0, 16, 8, 32));
	}

	@Test
	void testKademliaRejectsValuesAboveRange() {
		// The upper bounds exist because the limits derived from these parameters were reasoned about
		// over a bounded range: an absurd k makes lookups quadratically expensive and would overrun the
		// response MTU budget, and an absurd alpha multiplies the load one node puts on the network.
		NodeConfiguration.Builder builder = baseBuilder();
		assertThrows(IllegalArgumentException.class,
				() -> builder.alpha(NodeConfiguration.KademliaOptions.MAX_ALPHA + 1));
		assertThrows(IllegalArgumentException.class,
				() -> builder.k(NodeConfiguration.KademliaOptions.MAX_K + 1));
		assertThrows(IllegalArgumentException.class,
				() -> builder.replacements(NodeConfiguration.KademliaOptions.MAX_REPLACEMENTS + 1));

		// The boundary values themselves must be accepted, so the test cannot pass by rejecting everything.
		assertDoesNotThrow(() -> new NodeConfiguration.KademliaOptions(
				NodeConfiguration.KademliaOptions.MAX_ALPHA,
				NodeConfiguration.KademliaOptions.MAX_K,
				NodeConfiguration.KademliaOptions.MAX_REPLACEMENTS,
				NodeConfiguration.KademliaOptions.MIN_CONCURRENT_TASKS));
		assertDoesNotThrow(() -> new NodeConfiguration.KademliaOptions(
				NodeConfiguration.KademliaOptions.MIN_ALPHA,
				NodeConfiguration.KademliaOptions.MIN_K,
				NodeConfiguration.KademliaOptions.MIN_REPLACEMENTS,
				NodeConfiguration.KademliaOptions.MIN_CONCURRENT_TASKS));
	}

	/**
	 * The Builder and the record must agree on what is valid.
	 * <p>
	 * They previously did not: the record gained ranges while the Builder still tested only for
	 * positivity, so a value like {@code k = 2} was accepted by the setter and then thrown from
	 * {@code build()}, far from the call that caused it. Both now validate through the same checks;
	 * this pins that down, since the failure mode is silent from the Builder's point of view.
	 */
	@Test
	void testBuilderAndRecordAgreeOnValidity() {
		int[] invalidK = {NodeConfiguration.KademliaOptions.MIN_K - 1, NodeConfiguration.KademliaOptions.MAX_K + 1};
		for (int k : invalidK) {
			assertThrows(IllegalArgumentException.class, () -> baseBuilder().k(k),
					"Builder must reject k=" + k + " at the setter, not at build()");
			assertThrows(IllegalArgumentException.class,
					() -> new NodeConfiguration.KademliaOptions(3, k, 8, 32),
					"record must reject the same k=" + k);
		}

		// A value the Builder accepts must survive build(), or the two have diverged again.
		assertDoesNotThrow(() -> baseBuilder()
				.alpha(NodeConfiguration.KademliaOptions.MAX_ALPHA)
				.k(NodeConfiguration.KademliaOptions.MAX_K)
				.replacements(NodeConfiguration.KademliaOptions.MAX_REPLACEMENTS)
				.concurrentTasks(NodeConfiguration.KademliaOptions.MIN_CONCURRENT_TASKS)
				.build());
	}

	@Test
	void testSecurityDefaults() {
		NodeConfiguration config = baseBuilder().build();
		assertEquals(new NodeConfiguration.SecurityOptions(NodeConfiguration.DEFAULT_SPAM_THROTTLING,
				NodeConfiguration.DEFAULT_SUSPICIOUS_NODE_DETECTOR,
				NodeConfiguration.DEFAULT_DEVELOPER_MODE), config.security());
	}

	@Test
	void testFromMapEmpty() {
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> NodeConfiguration.fromMap(Map.of()));
		assertTrue(ex.getMessage().contains("Configuration map is empty"), "Actual: " + ex.getMessage());

		NullPointerException npe = assertThrows(NullPointerException.class, () -> NodeConfiguration.fromMap(null));
		assertTrue(npe.getMessage().contains("Configuration map must not be null"), "Actual: " + npe.getMessage());
	}

	@Test
	void testInvalidPort() {
		NodeConfiguration.Builder builder = baseBuilder();
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> builder.port(0));
		assertTrue(ex.getMessage().contains("Invalid DHT port: 0"), "Actual: " + ex.getMessage());

		ex = assertThrows(IllegalArgumentException.class, () -> builder.port(65536));
		assertTrue(ex.getMessage().contains("Invalid DHT port: 65536"), "Actual: " + ex.getMessage());
	}

	@Test
	void testInvalidDatabaseUri() {
		NodeConfiguration.Builder builder = baseBuilder();
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> builder.databaseUri("mysql://localhost"));
		assertTrue(ex.getMessage().contains("Unsupported database URI: mysql://localhost"), "Actual: " + ex.getMessage());

		// And from a document, where the record's own constructor is the one that refuses it.
		assertThrows(IllegalArgumentException.class,
				() -> builder.fromMap(Map.of("database", Map.of("uri", "mysql://localhost"))));
	}

	@Test
	void testInvalidDatabasePoolSize() {
		NodeConfiguration.Builder builder = baseBuilder();
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> builder.databasePoolSize(-1));
		assertTrue(ex.getMessage().contains("Invalid database pool size: -1"), "Actual: " + ex.getMessage());
	}

	@Test
	void testInvalidBootstrapFromMap() {
		// Wrong number of fields
		NodeConfiguration.Builder builder = baseBuilder();
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> builder.fromMap(Map.of("bootstraps", List.of(List.of("id", "host")))));
		assertTrue(ex.getMessage().contains("Invalid bootstrap node entry size: 2"), "Actual: " + ex.getMessage());

		// Duplicate IPv4
		List<Object> entry = List.of(Id.random().toString(), "1.2.3.4", 1234, "5.6.7.8", 5678);
		ex = assertThrows(IllegalArgumentException.class,
				() -> builder.fromMap(Map.of("bootstraps", List.of(entry))));
		assertTrue(ex.getMessage().contains("Duplicate IPv4 address found in bootstrap node"), "Actual: " + ex.getMessage());
	}

	@Test
	void testInvalidPrivateKey() {
		NodeConfiguration.Builder builder = baseBuilder();
		// Too short
		assertThrows(IllegalArgumentException.class, () -> builder.privateKey(new byte[32]));

		// Invalid Base58 character
		assertThrows(IllegalArgumentException.class, () -> builder.privateKey("0OI"));
	}
}