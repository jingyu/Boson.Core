/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

package io.bosonnetwork.access.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.access.Permission.Access;
import io.bosonnetwork.json.Json;

public class PermissionTests {
	@Test
	void testPermission() {
		var serviceId = "test.service";
		Map<String, Object> props = new HashMap<>(Map.ofEntries(
				Map.entry("intValue", 10),
				Map.entry("strValye", "Foo bar")
			));

		var perm = new Permission(serviceId, Access.Allow, props);

		assertEquals(serviceId, perm.getTargetServiceId());
		assertEquals(Access.Allow, perm.getAccess());
		assertTrue(perm.isAllow());
		assertFalse(perm.isDeny());
		assertEquals(props.size(), perm.getProperties().size());
	}

	@Test
	void testNonPropertiesPermission() {
		var serviceId = "test.service";
		var perm = new Permission(serviceId, Access.Deny);

		assertEquals(serviceId, perm.getTargetServiceId());
		assertEquals(Access.Deny, perm.getAccess());
		assertFalse(perm.isAllow());
		assertTrue(perm.isDeny());
		assertTrue(perm.getProperties().isEmpty());
	}

	@Test
	void testSerializePermission() throws IOException {
		var serviceId = "test.service";
		Map<String, Object> props = new HashMap<>(Map.ofEntries(
				Map.entry("intValue", 10),
				Map.entry("strValye", "Foo bar")
			));

		var perm = new Permission(serviceId, Access.Allow, props);

		var json = Json.objectMapper().writeValueAsString(perm);
		System.out.println(json);

		var n = Json.objectMapper().readTree(json);
		assertEquals(3, n.size());

		var perm2 = Json.objectMapper().readValue(json, Permission.class);

		assertEquals(serviceId, perm2.getTargetServiceId());
		assertEquals(Access.Allow, perm2.getAccess());
		assertTrue(perm2.isAllow());
		assertFalse(perm2.isDeny());
		assertEquals(props.size(), perm2.getProperties().size());

		var json2 = Json.objectMapper().writeValueAsString(perm2);
		assertEquals(json, json2);
	}

	@Test
	void testSerializeNonPropertiesPermission() throws IOException {
		var serviceId = "test.service";
		var perm = new Permission(serviceId, Access.Deny);

		var json = Json.objectMapper().writeValueAsString(perm);
		System.out.println(json);

		var n = Json.objectMapper().readTree(json);
		assertEquals(2, n.size());

		var perm2 = Json.objectMapper().readValue(json, Permission.class);

		assertEquals(serviceId, perm2.getTargetServiceId());
		assertEquals(Access.Deny, perm2.getAccess());
		assertFalse(perm2.isAllow());
		assertTrue(perm2.isDeny());
		assertTrue(perm2.getProperties().isEmpty());

		var json2 = Json.objectMapper().writeValueAsString(perm2);
		assertEquals(json, json2);
	}
}