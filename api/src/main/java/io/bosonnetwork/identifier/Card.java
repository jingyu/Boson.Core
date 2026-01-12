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
import io.bosonnetwork.json.Json;

/**
 * Compact representation of a DID Document for the Boson project.
 * <p>
 * A Card encapsulates the essential identity information of a DID subject in a
 * more compact JSON/CBOR serialization format than a full DID Document.
 * <ul>
 *   <li>{@code id} – the DID identifier</li>
 *   <li>{@code c} – a list of credentials</li>
 *   <li>{@code s} – a list of services</li>
 *   <li>{@code sat} – the timestamp when the card was signed</li>
 *   <li>{@code sig} – the digital signature over the card contents</li>
 * </ul>
 * Cards are signed and can be verified using {@link #isGenuine()} and {@link #validate()}.
 */
@JsonPropertyOrder({"id", "c", "s", "sat", "sig"})
public class Card {
	/** Default id used for profile credential entries. */
	protected static final String DEFAULT_PROFILE_CREDENTIAL_ID = "profile";
	/** Default type used for profile credential entries. */
	protected static final String DEFAULT_PROFILE_CREDENTIAL_TYPE = "BosonProfile";
	/** Default id for the home node service. */
	protected static final String DEFAULT_HOME_NODE_SERVICE_ID = "homeNode";
	/** Default type for the home node service. */
	protected static final String DEFAULT_HOME_NODE_SERVICE_TYPE = "BosonHomeNode";

	/** The DID identifier of the subject. */
	@JsonProperty("id")
	private final Id id;

	/**
	 * List of credentials associated with this Card.
	 *
	 * Compact JSON property name "c".
	 */
	@JsonProperty("c")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<Credential> credentials;

	/**
	 * List of services associated with this Card.
	 *
	 * Compact JSON property name "s".
	 */
	@JsonProperty("s")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final List<Service> services;

	/**
	 * Timestamp when this Card was signed.
	 *
	 * Compact JSON property name "sat".
	 */
	@JsonProperty("sat")
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private final Date signedAt;

	/**
	 * Digital signature over the contents of this Card.
	 *
	 * Compact JSON property name "sig".
	 */
	@JsonProperty("sig")
	private final byte[] signature;

	/**
	 * Internal constructor used by JSON deserializer.
	 *
	 * @param id          the DID identifier (required)
	 * @param credentials list of credentials (optional)
	 * @param services    list of services (optional)
	 * @param signedAt    timestamp of signature (required)
	 * @param signature   digital signature bytes (required)
	 */
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

	/**
	 * Internal constructor used by CardBuilder.
	 * The caller should transfer ownership of the credentials and services to the new instance.
	 *
	 * @param id          the DID identifier
	 * @param credentials list of credentials (may be null or empty)
	 * @param services    list of services (may be null or empty)
	 */
	protected Card(Id id, List<Credential> credentials, List<Service> services) {
		Objects.requireNonNull(id, "id");

		this.id = id;
		this.credentials = credentials == null || credentials.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(credentials);
		this.services = services == null || services.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(services);
		this.signedAt = null;
		this.signature = null;
	}

	/**
	 * Internal copy constructor used to create a signed Card instance.
	 *
	 * @param profile   the unsigned Card instance
	 * @param signedAt  timestamp of signature
	 * @param signature digital signature bytes
	 */
	protected Card(Card profile, Date signedAt, byte[] signature) {
		this.id = profile.id;
		this.credentials = profile.credentials;
		this.services = profile.services;

		this.signedAt = signedAt;
		this.signature = signature;
	}

	/**
	 * Returns the DID identifier of the subject.
	 *
	 * @return the DID identifier
	 */
	public Id getId() {
		return id;
	}

	/**
	 * Returns an unmodifiable list of all credentials associated with this Card.
	 *
	 * @return list of credentials, never null
	 */
	public List<Credential> getCredentials() {
		return credentials;
	}

	/**
	 * Returns a list of credentials that contain the specified type.
	 *
	 * @param type the credential type to filter by
	 * @return list of credentials matching the specified type, never null
	 */
	public List<Credential> getCredentials(String type) {
		return credentials.stream()
				.filter(c -> c.getTypes().contains(type))
				.collect(Collectors.toList());
	}

	/**
	 * Returns the credential with the specified id.
	 *
	 * @param id the credential id to look for
	 * @return the credential with the matching id, or null if not found
	 * @throws NullPointerException if id is null
	 */
	public Credential getCredential(String id) {
		Objects.requireNonNull(id, "id");

		return credentials.stream()
				.filter(c -> c.getId().equals(id))
				.findFirst()
				.orElse(null);
	}

	/**
	 * Returns the profile credential, if present.
	 * The profile credential is identified by id "profile" and type "BosonProfile".
	 *
	 * @return the profile credential, or null if not found
	 */
	public Credential getProfileCredential() {
		return credentials.stream()
				.filter(c -> c.getId().equals(DEFAULT_PROFILE_CREDENTIAL_ID) &&
						c.getTypes().contains(DEFAULT_PROFILE_CREDENTIAL_TYPE))
				.findFirst()
				.orElse(null);
	}

	/**
	 * Returns an unmodifiable list of all services associated with this Card.
	 *
	 * @return list of services, never null
	 */
	public List<Service> getServices() {
		return services;
	}

	/**
	 * Returns a list of services that match the specified type.
	 *
	 * @param type the service type to filter by
	 * @return list of services matching the specified type, never null
	 */
	public List<Service> getServices(String type) {
		return services.stream()
				.filter(s -> s.getType().equals(type))
				.collect(Collectors.toList());
	}

	/**
	 * Returns the service with the specified id.
	 *
	 * @param id the service id to look for
	 * @return the service with the matching id, or null if not found
	 * @throws NullPointerException if id is null
	 */
	public Service getService(String id) {
		Objects.requireNonNull(id, "id");

		return services.stream()
				.filter(s -> s.getId().equals(id))
				.findFirst()
				.orElse(null);
	}

	/**
	 * Returns the home node service, if present.
	 * The home node service is identified by id "homeNode" and type "BosonHomeNode".
	 *
	 * @return the home node service, or null if not found
	 */
	public Service getHomeNodeService() {
		return services.stream()
				.filter(s -> s.getId().equals(DEFAULT_HOME_NODE_SERVICE_ID) &&
						s.getType().equals(DEFAULT_HOME_NODE_SERVICE_TYPE))
				.findFirst()
				.orElse(null);
	}

	/**
	 * Returns the timestamp when this Card was signed.
	 *
	 * @return the signature timestamp, or null if unsigned
	 */
	public Date getSignedAt() {
		return signedAt;
	}

	/**
	 * Returns the digital signature bytes over this Card's contents.
	 *
	 * @return the signature bytes, or null if unsigned
	 */
	public byte[] getSignature() {
		return signature;
	}

	/**
	 * Verifies the digital signature of this Card.
	 * <p>
	 * Checks if the signature is present and has the correct length, then uses the
	 * public key from the DID identifier to verify the signature over the Card's
	 * signing data.
	 *
	 * @return true if the signature is valid, false otherwise
	 */
	public boolean isGenuine() {
		// Signature must be present and have expected length
		if (signature == null || signature.length != Signature.BYTES)
			return false;

		// Verify signature over the signing data using the DID's public key
		return Signature.verify(getSignData(), signature, id.toSignatureKey());
	}

	/**
	 * Validates the Card by verifying its digital signature.
	 *
	 * @throws InvalidSignatureException if the signature is missing or invalid
	 */
	public void validate() throws InvalidSignatureException {
		if (!isGenuine())
			throw new InvalidSignatureException();
	}

	/**
	 * Returns the byte array over which the signature is computed.
	 * <p>
	 * If this Card is already signed (signature present), returns the unsigned form
	 * of this Card for signature verification (signature and timestamp omitted).
	 * Otherwise returns the serialized form of this Card.
	 *
	 * @return byte array representing the signing data
	 */
	protected byte[] getSignData() {
		if (signature != null)	// already signed
			return new Card(this, null, null).toBytes();
		else 					// unsigned
			return toBytes();
	}

	@Override
	public int hashCode() {
		return Objects.hash(id, credentials, services, signedAt, Arrays.hashCode(signature));
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

	/**
	 * Returns the JSON string representation of this Card.
	 *
	 * @return JSON string of this Card
	 * @throws IllegalStateException if serialization fails
	 */
	@Override
	public String toString() {
		try {
			return Json.objectMapper().writeValueAsString(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Card is not serializable", e);
		}
	}

	/**
	 * Returns a pretty-printed JSON string representation of this Card.
	 *
	 * @return pretty-printed JSON string of this Card
	 * @throws IllegalStateException if serialization fails
	 */
	public String toPrettyString() {
		try {
			return Json.objectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Card is not serializable", e);
		}
	}

	/**
	 * Returns the CBOR byte array representation of this Card.
	 *
	 * @return CBOR bytes of this Card
	 * @throws IllegalStateException if serialization fails
	 */
	public byte[] toBytes() {
		try {
			return Json.cborMapper().writeValueAsBytes(this);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("INTERNAL ERROR: Card is not serializable", e);
		}
	}

	/**
	 * Parses a Card from its JSON string representation.
	 *
	 * @param json JSON string representing a Card
	 * @return the parsed Card instance
	 * @throws IllegalArgumentException if the JSON is invalid or cannot be parsed
	 */
	public static Card parse(String json) {
		try {
			return Json.objectMapper().readValue(json, Card.class);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid JSON data for Card", e);
		}
	}

	/**
	 * Parses a Card from its CBOR byte array representation.
	 *
	 * @param cbor CBOR bytes representing a Card
	 * @return the parsed Card instance
	 * @throws IllegalArgumentException if the CBOR is invalid or cannot be parsed
	 */
	public static Card parse(byte[] cbor) {
		try {
			return Json.cborMapper().readValue(cbor, Card.class);
		} catch (IOException e) {
			throw new IllegalArgumentException("Invalid CBOR data for Card", e);
		}
	}

	/**
	 * Creates a new CardBuilder for the specified subject Identity.
	 *
	 * @param subject the Identity subject for the Card
	 * @return a new CardBuilder instance
	 * @throws NullPointerException if subject is null
	 */
	public static CardBuilder builder(Identity subject) {
		Objects.requireNonNull(subject, "subject");
		return new CardBuilder(subject);
	}

	/**
	 * Represents a compact service entry in a Card.
	 * <p>
	 * A Service contains an id, type, endpoint, and additional arbitrary properties.
	 * The JSON representation uses compact property names:
	 * <ul>
	 *   <li>{@code id} - service identifier</li>
	 *   <li>{@code t} - service type</li>
	 *   <li>{@code e} - service endpoint</li>
	 * </ul>
	 */
	@JsonPropertyOrder({ "id", "t", "e" })
	public static class Service {
		/**
		 * The identifier of the service.
		 */
		@JsonProperty("id")
		private final String id;
		/**
		 * The type of the service.
		 * <p>
		 * Compact JSON property name "t".
		 */
		@JsonProperty("t")
		private final String type;
		/**
		 * The endpoint URL or address of the service.
		 * <p>
		 * Compact JSON property name "e".
		 */
		@JsonProperty("e")
		private final String endpoint;
		/**
		 * Additional arbitrary properties of the service.
		 */
		@JsonAnyGetter
		@JsonAnySetter
		private final Map<String, Object> properties;

		/**
		 * Internal constructor used by JSON deserializer.
		 *
		 * @param id       the service identifier (required)
		 * @param type     the service type (required)
		 * @param endpoint the service endpoint (required)
		 */
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

		/**
		 * Internal constructor used by the CardBuilder.
		 * The caller must transfer ownership of the properties to the new object.
		 *
		 * @param id         the service identifier
		 * @param type       the service type
		 * @param endpoint   the service endpoint
		 * @param properties additional properties map, may be null
		 */
		protected Service(String id, String type, String endpoint, Map<String, Object> properties) {
			this.id = id;
			this.type = type;
			this.endpoint = endpoint;
			this.properties = properties == null ? Collections.emptyMap() : properties;
		}

		/**
		 * Returns the service identifier.
		 *
		 * @return the service id
		 */
		public String getId() {
			return id;
		}

		/**
		 * Returns the service type.
		 *
		 * @return the service type
		 */
		public String getType() {
			return type;
		}

		/**
		 * Returns the service endpoint.
		 *
		 * @return the service endpoint
		 */
		public String getEndpoint() {
			return endpoint;
		}

		/**
		 * Returns an unmodifiable view of the additional properties.
		 *
		 * @return map of additional properties
		 */
		public Map<String, Object> getProperties() {
			return Collections.unmodifiableMap(properties);
		}

		/**
		 * Returns the value of a specific property by name.
		 *
		 * @param <T>  the expected type of the property value
		 * @param name the property name
		 * @return the property value, or null if not present
		 */
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