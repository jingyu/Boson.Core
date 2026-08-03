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

package io.bosonnetwork.kademlia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.json.Json;

/**
 * Checks the shipped {@code node.yaml} template against the parser that actually reads it.
 * <p>
 * A configuration template is documentation that the compiler never sees: a key renamed in the
 * parser leaves the template quietly describing a setting nobody reads, which is how both the
 * messaging and the active proxy services once shipped a whole block that was ignored. These tests
 * load the real resource off the class path rather than a copy.
 */
class NodeConfigTemplateTests {
	/** Placeholders the template carries in place of values only the operator can supply. */
	private static final String HOST_PLACEHOLDER = "PUBLIC_IPV4_ADDRESS";
	private static final String KEY_PLACEHOLDER = "NODE_PRIVATE_KEY";

	private static final String HOST = "203.0.113.5";
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

	private static Map<String, Object> template() throws Exception {
		try (InputStream in = NodeConfigTemplateTests.class.getResourceAsStream("/node.yaml")) {
			assertNotNull(in, "node.yaml is missing from the class path");
			String yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8)
					.replace(HOST_PLACEHOLDER, HOST)
					.replace(KEY_PLACEHOLDER, PRIVATE_KEY);
			return Json.yamlMapper().readValue(yaml, Json.mapType());
		}
	}

	@Test
	void testTemplateLoads() throws Exception {
		NodeConfiguration config = NodeConfiguration.builder()
				.vertx(vertx)
				.fromMap(template())
				.build();

		assertEquals(HOST, config.listen().host4());
		assertEquals(39001, config.listen().port());
		assertEquals("jdbc:sqlite:node.db", config.database().uri());
		assertTrue(config.security().spamThrottling());
		assertTrue(config.security().suspiciousNodeDetector());
		// The template ships developer mode off: it lets a node join over private addresses, which
		// silently breaks a public deployment.
		assertFalse(config.security().developerMode());
		// The template's leading '~' has to be expanded, not taken as a directory of that name.
		assertTrue(config.dataDir().isAbsolute(), "Actual: " + config.dataDir());
		assertTrue(config.dataDir().endsWith(Path.of("boson", "node")), "Actual: " + config.dataDir());
	}

	@Test
	void testEveryKeyInTheTemplateIsOneTheParserReads() throws Exception {
		Map<String, Object> document = template();
		Map<String, Object> parsed = NodeConfiguration.builder()
				.vertx(vertx)
				.fromMap(document)
				.build()
				.toMap();

		// A key the configuration does not write back is a key it did not read: either the template
		// is describing a setting that no longer exists, or the parser has renamed it.
		for (Map.Entry<String, Object> entry : document.entrySet()) {
			assertTrue(parsed.containsKey(entry.getKey()),
					"node.yaml sets '" + entry.getKey() + "', which the configuration does not read");

			if (entry.getValue() instanceof Map<?, ?> block) {
				@SuppressWarnings("unchecked")
				Map<String, Object> parsedBlock = (Map<String, Object>) parsed.get(entry.getKey());
				for (Object key : block.keySet())
					assertTrue(parsedBlock.containsKey(key),
							"node.yaml sets '" + entry.getKey() + "." + key + "', which the configuration does not read");
			}
		}
	}
}
