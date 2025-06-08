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

package io.bosonnetwork.identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({"id", "type", "serviceEndpoint"})
public class Service {
	@JsonProperty("id")
	private final String id;
	@JsonProperty("type")
	private final String type;
	@JsonProperty("serviceEndpoint")
	private final String endpoint;
	@JsonAnyGetter
	@JsonAnySetter
	private final Map<String, Object> properties;

	@JsonCreator
	protected Service(@JsonProperty(value = "id", required = true) String id,
					  @JsonProperty(value = "type", required = true) String type,
					  @JsonProperty(value = "serviceEndpoint", required = true) String endpoint) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(endpoint, "serviceEndpoint");

		this.id = id;
		this.type = type;
		this.endpoint = endpoint;
		this.properties = new LinkedHashMap<>();
	}

	protected Service(String id, String type, String endpoint, Map<String, Object> properties) {
		this.id = id;
		this.type = type;
		this.endpoint = endpoint;
		this.properties = properties == null || properties.isEmpty() ? Collections.emptyMap() : properties;
	}

	public String getId() {
		return id;
	}

	public String getType() {
		return type;
	}

	public String getEndpoint() {
		return endpoint;
	}

	public Map<String, Object> getProperties() {
		return Collections.unmodifiableMap(properties);
	}

	@SuppressWarnings("unchecked")
	public <T> T getProperty(String name) {
		return (T) properties.get(name);
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, type, endpoint, properties);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof Service that)
			return Objects.equals(id, that.id) &&
					Objects.equals(type, that.type) &&
					Objects.equals(endpoint, that.endpoint) &&
					Objects.equals(properties, that.properties);

		return false;
	}
}