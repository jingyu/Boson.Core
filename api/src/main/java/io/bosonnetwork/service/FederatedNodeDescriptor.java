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


package io.bosonnetwork.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;

/**
 * What a node says about itself and the services it runs: its catalog.
 * <p>
 * The descriptor answers "what does node N run, and what does it speak" for anyone who asks - other
 * nodes, clients, administrators - with or without a federation relationship. This class is the plain
 * value; what travels is the node's signed {@link FederationStatement} of purpose
 * {@link FederationStatement.Purpose#NODE_DESCRIPTOR NODE_DESCRIPTOR}, made by
 * {@link FederationStatement#nodeDescriptor(io.bosonnetwork.Identity, FederatedNodeDescriptor)
 * nodeDescriptor} and read back by {@link FederationStatement#asDescriptor(byte[], Id) asDescriptor}. The
 * node serves the statement over HTTPS rather than storing it in the DHT, which could not hold it: a
 * mutable DHT value is limited to 896 bytes, and a node with all its services needs more. Since the
 * signature travels with the statement, it may be cached and served again by anyone, and checked by
 * whoever receives it.
 * <p>
 * A service is the node's if its descriptor lists it. Where to reach it is the service's own
 * announcement in the DHT, which is fresher; the endpoint listed here is a hint, safe to use directly
 * only over a connection that checks the service's identity (see {@link FederatedService#trustManager()}).
 * <p>
 * A node that federates says how: a descriptor with no {@linkplain #getMode() mode}, or with one this
 * version doesn't know, is from a node that doesn't federate with this one.
 *
 * <h2>Serialized form</h2>
 * The class is serialized by Jackson, as the statement's content in CBOR and as JSON in the Admin API. An
 * {@link Id} is 32 bytes in CBOR and base58 text in JSON. The keys, every one optional except
 * {@code services}; readers ignore keys they don't know. The lists are always written, empty or not:
 * <ul>
 *   <li>{@code nodeId}: the node (JSON only: in a statement, the node is its issuer);</li>
 *   <li>{@code software}, {@code version}: what the node runs;</li>
 *   <li>{@code name}, {@code logo}, {@code website}, {@code contact}: who runs it;</li>
 *   <li>{@code mode}: its {@link FederationMode}, by {@linkplain FederationMode#wireName() wire name};</li>
 *   <li>{@code protocols}: the federation protocols it speaks, a list of text such as {@code federation/2};</li>
 *   <li>{@code capabilities}: a list of text;</li>
 *   <li>{@code services}: a list of maps, each with {@code type} (text), {@code peerId}, {@code endpoint}
 *       (text), {@code federation} (boolean: whether other nodes may use it), and optionally {@code name}
 *       (text) and {@code extra} (a map);</li>
 *   <li>{@code issuedAt}, {@code expiresAt}: the statement's dates, in epoch milliseconds (JSON only: in a
 *       statement, they are its own claims).</li>
 * </ul>
 * <p>
 * Immutable.
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE, getterVisibility = JsonAutoDetect.Visibility.NONE,
		isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonPropertyOrder({"nodeId", "software", "version", "name", "logo", "website", "contact", "mode", "protocols",
		"capabilities", "services", "issuedAt", "expiresAt"})
public final class FederatedNodeDescriptor {
	static final String NODE_ID = "nodeId";
	static final String ISSUED_AT = "issuedAt";
	static final String EXPIRES_AT = "expiresAt";

	@JsonProperty(NODE_ID)
	private final Id nodeId;
	@JsonProperty("software")
	private final @Nullable String software;
	@JsonProperty("version")
	private final @Nullable String version;
	@JsonProperty("name")
	private final @Nullable String name;
	@JsonProperty("logo")
	private final @Nullable String logo;
	@JsonProperty("website")
	private final @Nullable String website;
	@JsonProperty("contact")
	private final @Nullable String contact;
	private final @Nullable FederationMode mode;
	@JsonProperty("protocols")
	@JsonInclude(JsonInclude.Include.ALWAYS)
	private final List<String> protocols;
	@JsonProperty("capabilities")
	@JsonInclude(JsonInclude.Include.ALWAYS)
	private final List<String> capabilities;
	@JsonProperty("services")
	@JsonInclude(JsonInclude.Include.ALWAYS)
	private final List<Service> services;
	@JsonProperty(ISSUED_AT)
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	private final long issuedAt;
	@JsonProperty(EXPIRES_AT)
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	private final long expiresAt;

	/**
	 * A service a node lists. Immutable.
	 * <p>
	 * The endpoint is the one the node lists, a hint: where the service is now is its own announcement in
	 * the DHT.
	 */
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.NONE, getterVisibility = JsonAutoDetect.Visibility.NONE,
			isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
	@JsonPropertyOrder({"type", "name", "peerId", "endpoint", "federation", "extra"})
	public static final class Service {
		@JsonProperty("type")
		private final String type;
		@JsonProperty("name")
		private final @Nullable String name;
		@JsonProperty("peerId")
		private final Id peerId;
		@JsonProperty("endpoint")
		private final String endpoint;
		@JsonProperty("federation")
		@JsonInclude(JsonInclude.Include.ALWAYS)
		private final boolean federation;
		@JsonProperty("extra")
		private final Map<String, Object> extra;

		/**
		 * Creates a service entry.
		 *
		 * @param type       the service type id ({@code io.bosonnetwork.photonmessaging}, ...)
		 * @param name       the display name, if any
		 * @param peerId     the service's peer id
		 * @param endpoint   the endpoint, as a hint
		 * @param federation whether other nodes may use the service
		 * @param extra      extra data, empty if none
		 */
		public Service(String type, @Nullable String name, Id peerId, String endpoint, boolean federation,
					   Map<String, Object> extra) {
			Objects.requireNonNull(type, "type");
			if (type.isEmpty())
				throw new IllegalArgumentException("Empty service type");

			this.type = type;
			this.name = name;
			this.peerId = Objects.requireNonNull(peerId, "peerId");
			this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
			this.federation = federation;
			this.extra = Map.copyOf(Objects.requireNonNull(extra, "extra"));
		}

		// A service that doesn't say whether other nodes may use it is not offered to them.
		@JsonCreator
		private static Service of(@JsonProperty(value = "type", required = true) String type,
								  @JsonProperty("name") @Nullable String name,
								  @JsonProperty(value = "peerId", required = true) Id peerId,
								  @JsonProperty(value = "endpoint", required = true) String endpoint,
								  @JsonProperty("federation") @Nullable Boolean federation,
								  @JsonProperty("extra") @Nullable Map<String, Object> extra) {
			return new Service(type, name, peerId, endpoint, Boolean.TRUE.equals(federation),
					extra != null ? extra : Map.of());
		}

		/**
		 * Returns the service type id, such as {@code io.bosonnetwork.photonmessaging}.
		 *
		 * @return the type
		 */
		public String getType() {
			return type;
		}

		/**
		 * Returns the service's display name.
		 *
		 * @return an {@link Optional} with the name, or empty
		 */
		public Optional<String> getName() {
			return Optional.ofNullable(name);
		}

		/**
		 * Returns the service's peer id.
		 *
		 * @return the peer id
		 */
		public Id getPeerId() {
			return peerId;
		}

		/**
		 * Returns the endpoint the node lists for the service, a hint.
		 *
		 * @return the endpoint
		 */
		public String getEndpoint() {
			return endpoint;
		}

		/**
		 * Tells whether other nodes may use the service.
		 *
		 * @return {@code true} if they may
		 */
		public boolean isFederation() {
			return federation;
		}

		/**
		 * Returns the service's extra data.
		 *
		 * @return an unmodifiable map, empty if none
		 */
		public Map<String, Object> getExtra() {
			return extra;
		}

		@Override
		public int hashCode() {
			return Objects.hash(type, name, peerId, endpoint, federation, extra);
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;

			return obj instanceof Service that &&
					type.equals(that.type) &&
					Objects.equals(name, that.name) &&
					peerId.equals(that.peerId) &&
					endpoint.equals(that.endpoint) &&
					federation == that.federation &&
					extra.equals(that.extra);
		}

		@Override
		public String toString() {
			return "Service{type=" + type + ", peerId=" + peerId + ", endpoint=" + endpoint +
					", federation=" + federation + "}";
		}
	}

	private FederatedNodeDescriptor(Id nodeId, @Nullable String software, @Nullable String version,
									@Nullable String name, @Nullable String logo, @Nullable String website,
									@Nullable String contact, @Nullable FederationMode mode, List<String> protocols,
									List<String> capabilities, List<Service> services, long issuedAt, long expiresAt) {
		this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
		this.software = software;
		this.version = version;
		this.name = name;
		this.logo = logo;
		this.website = website;
		this.contact = contact;
		this.mode = mode;
		this.protocols = List.copyOf(protocols);
		this.capabilities = List.copyOf(capabilities);
		this.services = List.copyOf(services);
		this.issuedAt = issuedAt;
		this.expiresAt = expiresAt;
	}

	// A mode that isn't text, or that this version doesn't know - a newer version's - is left out.
	@JsonCreator
	private static FederatedNodeDescriptor of(@JsonProperty(value = NODE_ID, required = true) Id nodeId,
											  @JsonProperty("software") @Nullable String software,
											  @JsonProperty("version") @Nullable String version,
											  @JsonProperty("name") @Nullable String name,
											  @JsonProperty("logo") @Nullable String logo,
											  @JsonProperty("website") @Nullable String website,
											  @JsonProperty("contact") @Nullable String contact,
											  @JsonProperty("mode") @Nullable Object mode,
											  @JsonProperty("protocols") @Nullable List<String> protocols,
											  @JsonProperty("capabilities") @Nullable List<String> capabilities,
											  @JsonProperty(value = "services", required = true) List<Service> services,
											  @JsonProperty(ISSUED_AT) long issuedAt,
											  @JsonProperty(EXPIRES_AT) long expiresAt) {
		FederationMode m = null;
		if (mode instanceof String s) {
			try {
				m = FederationMode.ofWireName(s);
			} catch (IllegalArgumentException ignore) {
				// A mode this version doesn't know.
			}
		}

		return new FederatedNodeDescriptor(nodeId, software, version, name, logo, website, contact, m,
				protocols != null ? protocols : List.of(), capabilities != null ? capabilities : List.of(),
				Objects.requireNonNull(services, "services"), issuedAt, expiresAt);
	}

	/**
	 * Returns the node the descriptor is from.
	 *
	 * @return the node id
	 */
	public Id getNodeId() {
		return nodeId;
	}

	/**
	 * Returns the software the node runs.
	 *
	 * @return an {@link Optional} with the software name, or empty
	 */
	public Optional<String> getSoftware() {
		return Optional.ofNullable(software);
	}

	/**
	 * Returns the version of the software the node runs.
	 *
	 * @return an {@link Optional} with the version, or empty
	 */
	public Optional<String> getVersion() {
		return Optional.ofNullable(version);
	}

	/**
	 * Returns the node's display name.
	 *
	 * @return an {@link Optional} with the name, or empty
	 */
	public Optional<String> getName() {
		return Optional.ofNullable(name);
	}

	/**
	 * Returns the node's logo URL.
	 *
	 * @return an {@link Optional} with the logo, or empty
	 */
	public Optional<String> getLogo() {
		return Optional.ofNullable(logo);
	}

	/**
	 * Returns the node's website.
	 *
	 * @return an {@link Optional} with the website, or empty
	 */
	public Optional<String> getWebsite() {
		return Optional.ofNullable(website);
	}

	/**
	 * Returns how to contact the node's operator.
	 *
	 * @return an {@link Optional} with the contact, or empty
	 */
	public Optional<String> getContact() {
		return Optional.ofNullable(contact);
	}

	/**
	 * Returns how the node decides whom it federates with.
	 *
	 * @return an {@link Optional} with the mode, or empty if it says none, or one this version doesn't know
	 *         (a newer node's): either way, the node doesn't federate with this one
	 */
	public Optional<FederationMode> getMode() {
		return Optional.ofNullable(mode);
	}

	@JsonProperty("mode")
	private @Nullable String modeName() {
		return mode != null ? mode.wireName() : null;
	}

	/**
	 * Returns the federation protocols the node speaks.
	 *
	 * @return an unmodifiable list, empty if it says none
	 */
	public List<String> getProtocols() {
		return protocols;
	}

	/**
	 * Returns the node's capabilities.
	 *
	 * @return an unmodifiable list, empty if it says none
	 */
	public List<String> getCapabilities() {
		return capabilities;
	}

	/**
	 * Returns the services the node lists.
	 *
	 * @return an unmodifiable list
	 */
	public List<Service> getServices() {
		return services;
	}

	/**
	 * Returns the services of one type.
	 *
	 * @param type the service type id
	 * @return the matching services, in the node's order
	 */
	public List<Service> getServices(String type) {
		Objects.requireNonNull(type, "type");
		// Not Stream.toList(): core/api runs on Android API 33, which lacks it.
		List<Service> matching = new ArrayList<>();
		for (Service s : services) {
			if (s.type.equals(type))
				matching.add(s);
		}
		return List.copyOf(matching);
	}

	/**
	 * Returns the service with the given peer id.
	 *
	 * @param peerId the service's peer id
	 * @return an {@link Optional} with the service, or empty if the node doesn't list it
	 */
	public Optional<Service> getService(Id peerId) {
		Objects.requireNonNull(peerId, "peerId");
		for (Service s : services) {
			if (s.peerId.equals(peerId))
				return Optional.of(s);
		}
		return Optional.empty();
	}

	/**
	 * Tells whether the descriptor was read from a signed statement, and so carries its dates.
	 *
	 * @return {@code true} if it is signed; {@code false} for one built here
	 */
	public boolean isSigned() {
		return issuedAt != 0;
	}

	/**
	 * Returns when the node signed the descriptor, by its clock.
	 *
	 * @return the issue date; the epoch for a descriptor built here, which is not signed
	 */
	public Instant getIssuedAt() {
		return Instant.ofEpochMilli(issuedAt);
	}

	/**
	 * Returns when the signed descriptor expires, by the node's clock.
	 *
	 * @return the expiration date; the epoch for a descriptor built here, which is not signed
	 */
	public Instant getExpiresAt() {
		return Instant.ofEpochMilli(expiresAt);
	}

	@Override
	public String toString() {
		return "FederatedNodeDescriptor{nodeId=" + nodeId + ", name=" + name + ", mode=" + modeName() +
				", services=" + services.size() + "}";
	}

	/**
	 * Starts a descriptor, which {@link FederationStatement#nodeDescriptor(io.bosonnetwork.Identity,
	 * FederatedNodeDescriptor) FederationStatement.nodeDescriptor} signs.
	 *
	 * @param nodeId the node it describes
	 * @return a new builder
	 */
	public static Builder builder(Id nodeId) {
		return new Builder(nodeId);
	}

	/**
	 * Builds a descriptor, not signed: it has no dates until a statement carries it.
	 */
	public static final class Builder {
		private final Id nodeId;
		private @Nullable String software;
		private @Nullable String version;
		private @Nullable String name;
		private @Nullable String logo;
		private @Nullable String website;
		private @Nullable String contact;
		private FederationMode mode = FederationMode.OPEN;
		private final List<String> protocols = new ArrayList<>();
		private final Set<String> capabilities = new LinkedHashSet<>();
		private final List<Service> services = new ArrayList<>();

		private Builder(Id nodeId) {
			this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
		}

		/**
		 * Sets the software the node runs, and its version.
		 *
		 * @param software the software name
		 * @param version  the version
		 * @return this builder
		 */
		public Builder software(String software, String version) {
			this.software = Objects.requireNonNull(software, "software");
			this.version = Objects.requireNonNull(version, "version");
			return this;
		}

		/**
		 * Sets the node's display name.
		 *
		 * @param name the name
		 * @return this builder
		 */
		public Builder name(@Nullable String name) {
			this.name = name;
			return this;
		}

		/**
		 * Sets the node's logo URL.
		 *
		 * @param logo the logo
		 * @return this builder
		 */
		public Builder logo(@Nullable String logo) {
			this.logo = logo;
			return this;
		}

		/**
		 * Sets the node's website.
		 *
		 * @param website the website
		 * @return this builder
		 */
		public Builder website(@Nullable String website) {
			this.website = website;
			return this;
		}

		/**
		 * Sets how to contact the node's operator.
		 *
		 * @param contact the contact
		 * @return this builder
		 */
		public Builder contact(@Nullable String contact) {
			this.contact = contact;
			return this;
		}

		/**
		 * Sets how the node decides whom it federates with. Defaults to {@link FederationMode#OPEN}.
		 *
		 * @param mode the mode
		 * @return this builder
		 */
		public Builder mode(FederationMode mode) {
			this.mode = Objects.requireNonNull(mode, "mode");
			return this;
		}

		/**
		 * Adds a federation protocol the node speaks.
		 *
		 * @param protocol the protocol, such as {@code federation/2}
		 * @return this builder
		 */
		public Builder protocol(String protocol) {
			protocols.add(Objects.requireNonNull(protocol, "protocol"));
			return this;
		}

		/**
		 * Adds federation protocols the node speaks.
		 *
		 * @param protocols the protocols to add
		 * @return this builder
		 */
		public Builder protocols(List<String> protocols) {
			Objects.requireNonNull(protocols, "protocols").forEach(this::protocol);
			return this;
		}

		/**
		 * Adds a capability.
		 *
		 * @param capability the capability
		 * @return this builder
		 */
		public Builder capability(String capability) {
			capabilities.add(Objects.requireNonNull(capability, "capability"));
			return this;
		}

		/**
		 * Adds a service.
		 *
		 * @param service the service entry
		 * @return this builder
		 */
		public Builder service(Service service) {
			services.add(Objects.requireNonNull(service, "service"));
			return this;
		}

		/**
		 * Adds the given services.
		 *
		 * @param services the services to add
		 * @return this builder
		 */
		public Builder services(List<Service> services) {
			Objects.requireNonNull(services, "services").forEach(this::service);
			return this;
		}

		/**
		 * Builds the descriptor.
		 *
		 * @return the descriptor, not signed
		 */
		public FederatedNodeDescriptor build() {
			return new FederatedNodeDescriptor(nodeId, software, version, name, logo, website, contact, mode,
					protocols, List.copyOf(capabilities), services, 0, 0);
		}
	}
}
