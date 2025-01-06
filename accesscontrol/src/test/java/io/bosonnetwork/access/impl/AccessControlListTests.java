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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.google.common.collect.Maps;

import io.bosonnetwork.access.Permission.Access;
import io.bosonnetwork.utils.Json;

public class AccessControlListTests {
	@Test
	void EmptyACLsTests() {
		var subscriptions = EnumSet.allOf(Subscription.class);

		for (var subscription : subscriptions) {
			AccessControlList acl = new AccessControlList(subscription);
			assertEquals(subscription, acl.getSubscription());

			boolean allow = subscription != Subscription.Blocked;

			var perm = acl.getPermission("test.service");
			assertNotNull(perm);
			assertEquals(allow, perm.isAllow());
			assertEquals(!allow, perm.isDeny());

			acl.seal();

			perm = acl.getPermission("test.service");
			assertNotNull(perm);
			assertEquals(allow, perm.isAllow());
			assertEquals(!allow, perm.isDeny());

			perm = acl.getPermission("test.service2");
			assertNull(perm);
		}
	}

	@Test
	void ACLsTests() {
		var subscriptions = EnumSet.allOf(Subscription.class);

		for (var subscription : subscriptions) {
			Map<String, Permission> permissions = new HashMap<>();

			var svcFoo = "test.service.foo";
			var access = subscription == Subscription.Blocked ? Access.Deny : Access.Allow;

			Map<String, Object> props = new HashMap<>();
			props.put("foo.config", "test");
			props.put("foo.port", 8118);
			props.put("foo.data", Map.of("intVal", 1234, "strValue", "foo"));

			var permFoo = new Permission(svcFoo, access, props);
			permissions.put(permFoo.getTargetServiceId(), permFoo);

			var svcBar = "test.service.bar";

			props = new HashMap<>();
			props.put("bar.banner", "Bar");
			props.put("foo.enable", true);
			props.put("foo.password", "secret");

			var permBar = new Permission(svcBar, access, props);
			permissions.put(permBar.getTargetServiceId(), permBar);

			var svcXyz = "test.service.xyz";

			props = new HashMap<>();
			props.put("xyz.ipv6", false);
			props.put("foo.logfile", "/path/to/log/file");

			var permXyz = new Permission(svcXyz, Access.Deny, props);
			permissions.put(permXyz.getTargetServiceId(), permXyz);

			AccessControlList acl = new AccessControlList(subscription, permissions);
			acl.seal();

			assertEquals(subscription, acl.getSubscription());

			var p = acl.getPermission(svcFoo);
			assertTrue(p == permFoo);

			p = acl.getPermission(svcBar);
			assertTrue(p == permBar);

			p = acl.getPermission(svcXyz);
			assertTrue(p == permXyz);

			p = acl.getPermission("test.service.abc");
			assertNull(p);
		}
	}

	static boolean equals(Permission a, Permission b) {
		return a.getTargetServiceId().equals(b.getTargetServiceId()) &&
				a.getAccess() == b.getAccess() &&
				Maps.difference(a.getProperties(), b.getProperties()).areEqual();
	}

	@Test
	void ACLSerializeTests() throws IOException {
		var subscriptions = EnumSet.allOf(Subscription.class);

		for (var subscription : subscriptions) {
			Map<String, Permission> permissions = new HashMap<>();

			var svcFoo = "test.service.foo";
			var access = subscription == Subscription.Blocked ? Access.Deny : Access.Allow;

			Map<String, Object> props = new HashMap<>();
			props.put("foo.config", "test");
			props.put("foo.port", 8118);
			props.put("foo.data", Map.of("intVal", 1234, "strValue", "foo"));

			var permFoo = new Permission(svcFoo, access, props);
			permissions.put(permFoo.getTargetServiceId(), permFoo);

			var svcBar = "test.service.bar";

			props = new HashMap<>();
			props.put("bar.banner", "Bar");
			props.put("foo.enable", true);
			props.put("foo.password", "secret");

			var permBar = new Permission(svcBar, access, props);
			permissions.put(permBar.getTargetServiceId(), permBar);

			var svcXyz = "test.service.xyz";

			props = new HashMap<>();
			props.put("xyz.ipv6", false);
			props.put("xyz.logfile", "/path/to/log/file");

			var permXyz = new Permission(svcXyz, Access.Deny, props);
			permissions.put(permXyz.getTargetServiceId(), permXyz);

			AccessControlList acl = new AccessControlList(subscription, permissions);
			acl.seal();

			var json = Json.objectMapper().writeValueAsString(acl);
			System.out.println(json);

			var n = Json.objectMapper().readTree(json);
			assertEquals(2, n.size());
			assertEquals(subscription.toString(), n.get("subscription").asText());
			var perms = n.get("permissions");
			assertEquals(JsonNodeType.ARRAY, perms.getNodeType());
			assertEquals(3, perms.size());

			var acl2 = Json.objectMapper().readValue(json, AccessControlList.class);
			acl2.seal();
			assertEquals(subscription, acl2.getSubscription());

			var p = acl2.getPermission(svcFoo);
			assertTrue(equals(permFoo, p));

			p = acl2.getPermission(svcBar);
			assertTrue(equals(permBar, p));

			p = acl2.getPermission(svcXyz);
			assertTrue(equals(permXyz, p));

			p = acl2.getPermission("test.service.abc");
			assertNull(p);
		}
	}
}
