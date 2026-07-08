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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NodeConfigurationTests {
	private static Vertx vertx;

	@BeforeAll
	static void setup() {
		vertx = Vertx.vertx();
	}

	@Test
	void testInterfaceRoundTrip() {
		NodeConfiguration config = NodeConfiguration.builder()
				.vertx(vertx)
				.networkInterface4("eth0")
				.networkInterface6("wlan0")
				.generatePrivateKey()
				.build();

		assertEquals("eth0", config.networkInterface4());
		assertEquals("wlan0", config.networkInterface6());

		// Round-trip through the builder to ensure Vert.x is provided
		NodeConfiguration restored = NodeConfiguration.builder()
				.vertx(vertx)
				.fromMap(config.toMap())
				.build();

		assertEquals("eth0", restored.networkInterface4());
		assertEquals("wlan0", restored.networkInterface6());
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
				.generatePrivateKey();
	}

	@Test
	void testBootstrapRoundTripDualStack() {
		Id id = Id.random();
		NodeConfiguration config = baseBuilder()
				.addBootstrap(id, "203.0.113.5", 1234, "2001:db8::1", 5678)
				.build();

		NodeInfo bootstrap = config.bootstrapNodes().iterator().next();
		assertTrue(bootstrap.hasMultiAddresses());

		// Round-trip through the YAML-style map representation.
		NodeConfiguration restored = NodeConfiguration.builder()
				.vertx(vertx)
				.fromMap(config.toMap())
				.build();

		assertEquals(config.bootstrapNodes(), restored.bootstrapNodes());
	}

	@Test
	void testBootstrapRoundTripIPv6Only() {
		Id id = Id.random();
		NodeConfiguration config = baseBuilder()
				.addBootstrap(NodeInfo.of(id, "2001:db8::2", 5678))
				.build();

		NodeInfo bootstrap = config.bootstrapNodes().iterator().next();
		assertTrue(bootstrap.hasAddress6());
		assertTrue(!bootstrap.hasAddress4());

		// An IPv6-only node is written as a 3-tuple; loading must not force it into the IPv4 slot.
		NodeConfiguration restored = NodeConfiguration.builder()
				.vertx(vertx)
				.fromMap(config.toMap())
				.build();

		assertEquals(config.bootstrapNodes(), restored.bootstrapNodes());
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

		Map<String, Object> map = Map.of("bootstraps", List.of(entry));

		NodeConfiguration config = NodeConfiguration.builder()
				.vertx(vertx)
				.host4("192.168.1.100")
				.generatePrivateKey()
				.fromMap(map)
				.build();

		NodeInfo bootstrap = config.bootstrapNodes().iterator().next();
		assertEquals(id, bootstrap.getId());
		assertEquals(1234, bootstrap.getPort4());
		assertEquals(5678, bootstrap.getPort6());
	}

	@Test
	void testVertxNotProvided() {
		// Builder.build() wraps NPE/IAE into IllegalStateException
		// NodeConfiguration constructor: Objects.requireNonNull(builder.vertx, "Vertx instance must be provided");
		NodeConfiguration.Builder builder = NodeConfiguration.builder();
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
			builder.host4("192.168.1.1").generatePrivateKey().build();
		});
		assertTrue(ex.getMessage().contains("Vert.x instance must be provided"), "Actual message: " + ex.getMessage());
	}

	@Test
	void testBothHostAndInterfaceIPv4() {
		NodeConfiguration.Builder builder = NodeConfiguration.builder()
				.vertx(Vertx.vertx())
				.host4("192.168.1.1")
				.networkInterface4("eth0")
				.generatePrivateKey();

		IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
		assertTrue(ex.getMessage().contains("Both IPv4 host and network interface are specified"), "Actual message: " + ex.getMessage());
	}

	@Test
	void testBothHostAndInterfaceIPv6() {
		NodeConfiguration.Builder builder = NodeConfiguration.builder()
				.vertx(vertx)
				.host6("2001:0db8:85a3:0000:0000:8a2e:0370:7356")
				.networkInterface6("eth0")
				.generatePrivateKey();

		IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
		assertTrue(ex.getMessage().contains("Both IPv6 host and network interface are specified"), "Actual message: " + ex.getMessage());
	}

	@Test
	void testNoNetworkConfig() {
		NodeConfiguration.Builder builder = NodeConfiguration.builder()
				.vertx(vertx)
				.generatePrivateKey();

		IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
		assertTrue(ex.getMessage().contains("either IPv4 or IPv6 host or network interface must be provided"), "Actual message: " + ex.getMessage());
	}

	@Test
	void testMissingPrivateKey() {
		NodeConfiguration.Builder builder = NodeConfiguration.builder()
				.vertx(vertx)
				.host4("192.168.1.1");

		IllegalStateException ex = assertThrows(IllegalStateException.class, builder::build);
		assertTrue(ex.getMessage().contains("The node's private key must be provided"), "Actual message: " + ex.getMessage());
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
		Map<String, Object> map = Map.of("bootstraps", List.of(List.of("id", "host")));
		NodeConfiguration.Builder builder = baseBuilder();
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> builder.fromMap(map));
		assertTrue(ex.getMessage().contains("Invalid bootstrap node entry size: 2"), "Actual: " + ex.getMessage());

		// Duplicate IPv4
		List<Object> entry = List.of(Id.random().toString(), "1.2.3.4", 1234, "5.6.7.8", 5678);
		Map<String, Object> map2 = Map.of("bootstraps", List.of(entry));
		ex = assertThrows(IllegalArgumentException.class, () -> builder.fromMap(map2));
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
