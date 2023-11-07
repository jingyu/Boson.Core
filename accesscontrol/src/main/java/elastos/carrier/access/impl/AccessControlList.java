/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

package elastos.carrier.access.impl;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSetter;

import elastos.carrier.access.Permission.Access;

@JsonPropertyOrder({ "subscription", "permissions" })
@JsonAutoDetect(
		fieldVisibility = Visibility.NONE,
		setterVisibility = Visibility.NONE,
		getterVisibility = Visibility.NONE,
		isGetterVisibility = Visibility.NONE,
		creatorVisibility = Visibility.NONE)
public class AccessControlList {
	@JsonProperty("subscription")
	@JsonInclude(Include.NON_NULL)
	private Subscription subscription;

	private Map<String, Permission> permissions;

	private static Class<?> EMPTY_MAP_CLASS = Collections.EMPTY_MAP.getClass();
    private static Class<?> UNMODIFIABLE_MAP_CLASS = Collections.unmodifiableMap(Collections.emptyMap()).getClass();

	AccessControlList(Subscription subscription, Map<String, Permission> permissions) {
		this.subscription = subscription;
		this.permissions = permissions;
	}

	AccessControlList(Subscription subscription) {
		this(subscription, new HashMap<>());
	}

	@JsonCreator()
	AccessControlList() {
		this(Subscription.Free);
	}

	public Subscription getSubscription() {
		return subscription;
	}

	@JsonGetter("permissions")
	@JsonInclude(Include.NON_EMPTY)
	protected List<Permission> getPermissions() {
		return permissions.values().stream().sorted().collect(Collectors.toList());
	}

	@JsonSetter("permissions")
	protected void setPermissions(List<Permission> permissions) {
		this.permissions.clear();
		for (Permission permission : permissions)
			this.permissions.put(permission.getTargetServiceId(), permission);
	}

	void seal() {
		Class<?> clazz = permissions.getClass();
		if (clazz != EMPTY_MAP_CLASS && clazz != UNMODIFIABLE_MAP_CLASS)
			permissions = permissions.isEmpty() ?
					Collections.emptyMap() : Collections.unmodifiableMap(permissions);
	}

	public Permission getPermission(String serviceId) {
		if (permissions.containsKey(serviceId)) {
			return permissions.get(serviceId);
		} else {
			// Read-only AccessControlList - for specific node
			Class<?> clazz = permissions.getClass();
			if (clazz == EMPTY_MAP_CLASS || clazz == UNMODIFIABLE_MAP_CLASS)
				return null;

			// Writable AccessControlList - default(general) access control lists
			//
			// Optimized for the default(general) access control lists.
			// Only create one shared Permission object for one subscription,
			// and reuse it later.
			Permission permission;
			switch (subscription) {
			case Blocked:
				permission = new Permission(serviceId, Access.Deny);
				break;

			default:
				permission = new Permission(serviceId, Access.Allow);
				break;
			}

			permissions.put(permission.getTargetServiceId(), permission);
			return permission;
		}
	}

	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder();

		repr.append("Subscription: ").append(subscription).append("\n");
		if (permissions.isEmpty())
			return repr.toString();

		repr.append("Permissions:\n");
		for (Permission perm : permissions.values()) {
			repr.append(" - ").append(perm.getTargetServiceId()).append("\n");
			perm.toString(repr, "   ");
		}

		return repr.toString();
	}
}
