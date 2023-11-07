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
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonSetter;

@JsonPropertyOrder({ "service", "access", "properties" })
@JsonAutoDetect(
		fieldVisibility = Visibility.NONE,
		setterVisibility = Visibility.NONE,
		getterVisibility = Visibility.NONE,
		isGetterVisibility = Visibility.NONE,
		creatorVisibility = Visibility.NONE)
class Permission implements elastos.carrier.access.Permission, Comparable<Permission> {
	@JsonProperty("service")
	private String targetServiceId;

	@JsonProperty("access")
	private Access access;

	@JsonProperty("properties")
	@JsonInclude(Include.NON_EMPTY)
	@JsonPropertyOrder(alphabetic = true)
	private Map<String, Object> properties;

	Permission(String targetServiceId, Access access, Map<String, Object> properties) {
		this.targetServiceId = targetServiceId;
		this.access = access;
		this.properties = properties == null || properties.isEmpty() ?
				Collections.emptyMap() : Collections.unmodifiableMap(properties);;
	}

	@JsonCreator
	Permission(@JsonProperty(value = "service", required = true) String targetServiceId,
			@JsonProperty(value = "access", required = true) Access access) {
		this(targetServiceId, access, null);
	}

	static Permission allow(String targetServiceId, Map<String, Object> properties) {
		return new Permission(targetServiceId, Access.Allow, properties);
	}

	static Permission allow(String targetServiceId) {
		return new Permission(targetServiceId, Access.Allow, Collections.emptyMap());
	}

	static Permission deny(String targetServiceId) {
		return new Permission(targetServiceId, Access.Deny, Collections.emptyMap());
	}

	@Override
	public String getTargetServiceId() {
		return targetServiceId;
	}

	@Override
	public Access getAccess() {
		return access;
	}

	@Override
	public Map<String, Object> getProperties() {
		return properties == null || properties.isEmpty() ? Collections.emptyMap() : properties;
	}

	@JsonSetter("properties")
	protected void setProperties(Map<String, Object> properties) {
		this.properties = properties == null || properties.isEmpty() ?
				Collections.emptyMap() : Collections.unmodifiableMap(properties);
	}

	@Override
	public int compareTo(Permission other) {
		return this.targetServiceId.compareToIgnoreCase(other.targetServiceId);
	}

	void toString(StringBuilder repr, String padding) {
		repr.append(padding).append("Target service: ").append(targetServiceId).append("\n")
			.append(padding).append("Access:         ").append(access).append("\n");

		if (!properties.isEmpty())
			repr.append(padding).append("Properties:     ").append(properties).append("\n");

	}
	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder();
		toString(repr, "");
		return repr.toString();
	}
}
