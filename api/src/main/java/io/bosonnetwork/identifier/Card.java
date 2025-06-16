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

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.core.JsonProcessingException;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.InvalidSignatureException;
import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.Json;

@JsonPropertyOrder({"id", "c", "s", "sat", "sig"})
public class Card {
	protected static final String DEFAULT_PROFILE_CREDENTIAL_ID = "profile";
	protected static final String DEFAULT_PROFILE_CREDENTIAL_TYPE = "BosonProfile";
	protected static final String DEFAULT_HOME_NODE_SERVICE_ID = "homeNode";
	protected static final String DEFAULT_HOME_NODE_SERVICE_TYPE = "BosonHomeNode";

	@JsonProperty("id")
	private final Id id;
	@JsonProperty("c")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<Credential> credentials;
	@JsonProperty("s")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<Service> services;
	@JsonProperty("sat")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Date signedAt;
	@JsonProperty("sig")
	private final byte[] signature;

	// Internal constructor used by JSON deserializer
	@JsonCreator
	protected Card(@JsonProperty(value = "id", required = true) Id id,
				   @JsonProperty(value = "c") List<Credential> credentials,
				   @JsonProperty(value = "s") List<Service> services,
				   @JsonProperty(value = "sat") Date signedAt,
				   @JsonProperty(value = "sig", required = true) byte[] signature) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(signedAt, "signedAt");
		Objects.requireNonNull(signature, "signature");

		this.id = id;
		this.credentials = credentials == null || credentials.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(credentials);
		this.services = services == null || services.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(services);
		this.signedAt = signedAt;
		this.signature = signature;
	}

	// Internal constructor used by CardBuilder
	// The caller should transfer ownership of the cards and services to the new instance
	protected Card(Id id, List<Credential> credentials, List<Service> services) {
		Objects.requireNonNull(id, "id");

		this.id = id;
		this.credentials = credentials == null || credentials.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(credentials);
		this.services = services == null || services.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(services);
		this.signedAt = null;
		this.signature = null;
	}

	protected Card(Card profile, Date signedAt, byte[] signature) {
		this.id = profile.id;
		this.credentials = profile.credentials;
		this.services = profile.services;

		this.signedAt = signedAt;
		this.signature = signature;
	}

	public Id getId() {
		return id;
	}

	public List<Credential> getCredentials() {
		return credentials;
	}

	public List<Credential> getCredentials(String type) {
		return credentials.stream()
				.filter(c -> c.getTypes().contains(type))
				.collect(Collectors.toList());
	}

	public Credential getCredential(String id) {
		Objects.requireNonNull(id, "id");

		return credentials.stream()
				.filter(c -> c.getId().equals(id))
				.findFirst()
				.orElse(null);
	}

	public Credential getProfileCredential() {
		return credentials.stream()
				.filter(c -> c.getId().equals(DEFAULT_PROFILE_CREDENTIAL_ID) &&
						c.getTypes().contains(DEFAULT_PROFILE_CREDENTIAL_TYPE))
				.findFirst()
				.orElse(null);
	}

	public List<Service> getServices() {
		return services;
	}

	public List<Service> getServices(String type) {
		return services.stream()
				.filter(s -> s.getType().equals(type))
				.collect(Collectors.toList());
	}

	public Service getService(String id) {
		Objects.requireNonNull(id, "id");

		return services.stream()
				.filter(s -> s.getId().equals(id))
				.findFirst()
				.orElse(null);
	}

	public Service getHomeNodeService() {
		return services.stream()
				.filter(s -> s.getId().equals(DEFAULT_HOME_NODE_SERVICE_ID) &&
						s.getType().equals(DEFAULT_HOME_NODE_SERVICE_TYPE))
				.findFirst()
				.orElse(null);
	}

	public Date getSignedAt() {
		return signedAt;
	}

	public byte[] getSignature() {
		return signature;
	}

	public boolean isGenuine() {
		if (signature == null || signature.length != Signature.BYTES)
			return false;

		return Signature.verify(getSignData(), signature, id.toSignatureKey());
	}

	public void validate() throws InvalidSignatureException {
		if (!isGenuine())
			throw new InvalidSignatureException();
	}

	protected byte[] getSignData() {
		if (signature != null)	// already signed
			return new Card(this, null, null).toBytes();
		else 					// unsigned
			return toBytes();
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, credentials, services, Arrays.hashCode(signature));
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;

		if (o instanceof Card that)
			return Objects.equals(id, that.id) &&
					Objects.equals(credentials, that.credentials) &&
					Objects.equals(services, that.services) &&
					Objects.equals(signedAt, that.signedAt) &&
					Arrays.equals(signature, that.signature);

		return false;
	}

	@Override
	public String toString() {
		try {
			return Json.objectMapper().writeValueAsString(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Card is not serializable", e);
		}
	}

	public String toPrettyString() {
		try {
			return Json.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Card is not serializable", e);
		}
	}

	public byte[] toBytes() {
		try {
			return Json.cborMapper().writeValueAsBytes(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Card is not serializable", e);
		}
	}

	public static Card parse(String json) {
		try {
			return Json.objectMapper().readValue(json, Card.class);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid JSON data for Card", e);
		}
	}

	public static Card parse(byte[] cbor) {
		try {
			return Json.cborMapper().readValue(cbor, Card.class);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid CBOR data for Card", e);
		}
	}

	public static CardBuilder builder(Identity subject) {
		Objects.requireNonNull(subject, "subject");
		return new CardBuilder(subject);
	}

	@JsonPropertyOrder({ "id", "t", "e" })
	public static class Service {
		@JsonProperty("id")
		private final String id;
		@JsonProperty("t")
		private final String type;
		@JsonProperty("e")
		private final String endpoint;
		@JsonAnyGetter
		@JsonAnySetter
		private final Map<String, Object> properties;

		// internal constructor used by JSON deserializer
		@JsonCreator
		protected Service(@JsonProperty(value = "id", required = true) String id,
						  @JsonProperty(value = "t", required = true) String type,
						  @JsonProperty(value = "e", required = true) String endpoint) {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(type, "type");
			Objects.requireNonNull(endpoint, "endpoint");

			this.id = id;
			this.type = type;
			this.endpoint = endpoint;
			this.properties = new LinkedHashMap<>();
		}

		// internal constructor, used by the CardBuilder.
		// the caller must transfer ownership of the properties to the new object
		protected Service(String id, String type, String endpoint, Map<String, Object> properties) {
			this.id = id;
			this.type = type;
			this.endpoint = endpoint;
			this.properties = properties == null ? Collections.emptyMap() : properties;
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
}