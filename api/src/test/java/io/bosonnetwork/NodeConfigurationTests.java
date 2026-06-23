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
}
