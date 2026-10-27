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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.cwt.CwtException;
import io.bosonnetwork.cwt.InvalidClaimException;

/**
 * What a node says about itself and the services it runs, signed by the node: its catalog.
 * <p>
 * The descriptor answers "what does node N run, and what does it speak" for anyone who asks - other
 * nodes, clients, administrators - with or without a federation relationship. It is a
 * {@link FederationStatement} of purpose {@link FederationStatement.Purpose#NODE_DESCRIPTOR
 * NODE_DESCRIPTOR}, served by the node over HTTPS rather than stored in the DHT, which could not hold it:
 * a mutable DHT value is limited to 896 bytes, and a node with all its services needs more. Since the
 * signature travels with it, a verified descriptor may be cached and served again by anyone, and checked
 * by whoever receives it; {@link #getEncoded()} is what to pass on.
 * <p>
 * A service is the node's if its descriptor lists it. Where to reach it is the service's own
 * announcement in the DHT, which is fresher; the endpoint listed here is a hint, safe to use directly
 * only over a connection that checks the service's identity (see {@link FederatedService#trustManager()}).
 *
 * <h2>Content</h2>
 * The statement's content is a map with these text keys, every one optional except {@code services};
 * readers ignore keys they don't know:
 * <ul>
 *   <li>{@code software}, {@code version}: what the node runs;</li>
 *   <li>{@code name}, {@code logo}, {@code website}, {@code contact}: who runs it;</li>
 *   <li>{@code mode}: its {@link FederationMode}, by {@linkplain FederationMode#wireName() wire name};</li>
 *   <li>{@code protocols}: the federation protocols it speaks, a list of text such as {@code federation/2};</li>
 *   <li>{@code capabilities}: a list of text;</li>
 *   <li>{@code services}: a list of maps, each with {@code type} (text), {@code peerId} (32 bytes),
 *       {@code endpoint} (text), {@code federation} (boolean: whether other nodes may use it), and
 *       optionally {@code name} (text) and {@code extra} (a map).</li>
 * </ul>
 */
public final class NodeDescriptor {
	private final Id nodeId;
	private final @Nullable String software;
	private final @Nullable String version;
	private final @Nullable String name;
	private final @Nullable String logo;
	private final @Nullable String website;
	private final @Nullable String contact;
	private final @Nullable FederationMode mode;
	private final List<String> protocols;
	private final Set<String> capabilities;
	private final List<Service> services;
	private final Instant issuedAt;
	private final Instant expiresAt;
	private final byte[] encoded;

	/**
	 * A service the node lists.
	 *
	 * @param type       the service type id ({@code io.bosonnetwork.photonmessaging}, ...)
	 * @param name       the display name, if any
	 * @param peerId     the service's peer id
	 * @param endpoint   the endpoint, as a hint
	 * @param federation whether other nodes may use the service
	 * @param extra      extra data, an unmodifiable map, empty if none
	 */
	public record Service(String type, @Nullable String name, Id peerId, String endpoint, boolean federation,
						  Map<String, Object> extra) {
		/**
		 * Creates a service entry.
		 *
		 * @param type       the service type id
		 * @param name       the display name, if any
		 * @param peerId     the service's peer id
		 * @param endpoint   the endpoint, as a hint
		 * @param federation whether other nodes may use the service
		 * @param extra      extra data, empty if none
		 */
		public Service {
			Objects.requireNonNull(type, "type");
			if (type.isEmpty())
				throw new IllegalArgumentException("Empty service type");
			Objects.requireNonNull(peerId, "peerId");
			Objects.requireNonNull(endpoint, "endpoint");
			extra = Map.copyOf(Objects.requireNonNull(extra, "extra"));
		}

		private Map<String, Object> toContent() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("type", type);
			if (name != null)
				map.put("name", name);
			map.put("peerId", peerId.getBytes());
			map.put("endpoint", endpoint);
			map.put("federation", federation);
			if (!extra.isEmpty())
				map.put("extra", extra);
			return map;
		}

		private static Service fromContent(Object value) throws InvalidClaimException {
			if (!(value instanceof Map<?, ?> m))
				throw new InvalidClaimException("Invalid service entry");

			if (!(m.get("type") instanceof String type) || type.isEmpty())
				throw new InvalidClaimException("Invalid service type");
			if (!(m.get("peerId") instanceof byte[] peerId) || peerId.length != Id.BYTES)
				throw new InvalidClaimException("Invalid service peer id");
			if (!(m.get("endpoint") instanceof String endpoint))
				throw new InvalidClaimException("Invalid service endpoint");

			Object name = m.get("name");
			if (name != null && !(name instanceof String))
				throw new InvalidClaimException("Invalid service name");

			Object federation = m.get("federation");
			if (federation != null && !(federation instanceof Boolean))
				throw new InvalidClaimException("Invalid service federation flag");

			Map<String, Object> extra = Map.of();
			Object x = m.get("extra");
			if (x != null) {
				if (!(x instanceof Map<?, ?> xm))
					throw new InvalidClaimException("Invalid service extra data");
				extra = textKeys(xm);
			}

			return new Service(type, (String) name, Id.of(peerId), endpoint, Boolean.TRUE.equals(federation), extra);
		}
	}

	private NodeDescriptor(Id nodeId, @Nullable String software, @Nullable String version, @Nullable String name,
						   @Nullable String logo, @Nullable String website, @Nullable String contact,
						   @Nullable FederationMode mode, List<String> protocols, Set<String> capabilities,
						   List<Service> services, Instant issuedAt, Instant expiresAt, byte[] encoded) {
		this.nodeId = nodeId;
		this.software = software;
		this.version = version;
		this.name = name;
		this.logo = logo;
		this.website = website;
		this.contact = contact;
		this.mode = mode;
		this.protocols = List.copyOf(protocols);
		this.capabilities = Set.copyOf(capabilities);
		this.services = List.copyOf(services);
		this.issuedAt = issuedAt;
		this.expiresAt = expiresAt;
		this.encoded = encoded;
	}

	/**
	 * Verifies a signed descriptor.
	 *
	 * @param encoded the descriptor as served
	 * @param nodeId  the node it must be from
	 * @return the verified descriptor
	 * @throws CwtException if the descriptor is invalid, expired, malformed or from another node
	 */
	public static NodeDescriptor verify(byte[] encoded, Id nodeId) throws CwtException {
		Objects.requireNonNull(encoded, "encoded");
		Objects.requireNonNull(nodeId, "nodeId");

		FederationStatement statement = FederationStatement.verifier(FederationStatement.Purpose.NODE_DESCRIPTOR)
				.issuer(nodeId)
				.verify(encoded);
		return fromStatement(statement, encoded.clone());
	}

	private static NodeDescriptor fromStatement(FederationStatement statement, byte[] encoded) throws InvalidClaimException {
		Map<String, Object> c = statement.getContent();

		List<String> protocols = textList(c.get("protocols"), "protocols");
		Set<String> capabilities = new LinkedHashSet<>(textList(c.get("capabilities"), "capabilities"));

		if (!(c.get("services") instanceof List<?> list))
			throw new InvalidClaimException("A node descriptor must list its services");
		List<Service> services = new ArrayList<>(list.size());
		for (Object entry : list)
			services.add(Service.fromContent(entry));

		return new NodeDescriptor(statement.getIssuer(), text(c, "software"), text(c, "version"), text(c, "name"),
				text(c, "logo"), text(c, "website"), text(c, "contact"),
				FederationMode.ofWireName(text(c, "mode")), protocols, capabilities, services,
				statement.getIssuedAt(), statement.getExpiresAt(), encoded);
	}

	private static @Nullable String text(Map<String, Object> content, String key) throws InvalidClaimException {
		Object value = content.get(key);
		if (value == null)
			return null;
		if (!(value instanceof String s))
			throw new InvalidClaimException("Invalid " + key);
		return s;
	}

	private static List<String> textList(@Nullable Object value, String key) throws InvalidClaimException {
		if (value == null)
			return List.of();
		if (!(value instanceof List<?> list))
			throw new InvalidClaimException("Invalid " + key);

		List<String> result = new ArrayList<>(list.size());
		for (Object o : list) {
			if (!(o instanceof String s))
				throw new InvalidClaimException("Invalid " + key);
			result.add(s);
		}
		return result;
	}

	private static Map<String, Object> textKeys(Map<?, ?> map) throws InvalidClaimException {
		Map<String, Object> result = new LinkedHashMap<>(map.size());
		for (Map.Entry<?, ?> e : map.entrySet()) {
			if (!(e.getKey() instanceof String key) || e.getValue() == null)
				throw new InvalidClaimException("Invalid extra data");
			result.put(key, e.getValue());
		}
		return result;
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
	 * @return an {@link Optional} with the mode, or empty if it says none or one this version doesn't know
	 */
	public Optional<FederationMode> getMode() {
		return Optional.ofNullable(mode);
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
	 * @return an unmodifiable set, empty if it says none
	 */
	public Set<String> getCapabilities() {
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
			if (s.type().equals(type))
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
		return services.stream().filter(s -> s.peerId().equals(peerId)).findFirst();
	}

	/**
	 * Returns when the node signed the descriptor, by its clock.
	 *
	 * @return the issue date
	 */
	public Instant getIssuedAt() {
		return issuedAt;
	}

	/**
	 * Returns when the descriptor expires, by the node's clock.
	 *
	 * @return the expiration date
	 */
	public Instant getExpiresAt() {
		return expiresAt;
	}

	/**
	 * Returns the signed descriptor, to serve or pass on.
	 *
	 * @return a copy of the encoded descriptor
	 */
	public byte[] getEncoded() {
		return encoded.clone();
	}

	/**
	 * Starts a descriptor.
	 *
	 * @return a new builder
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builds and signs a descriptor.
	 */
	public static final class Builder {
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
		private Duration lifetime = FederationStatement.Purpose.NODE_DESCRIPTOR.maxLifetime();

		private Builder() {
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
		 * Sets how long the descriptor lives. Defaults to, and may not exceed, a day.
		 *
		 * @param lifetime the lifetime
		 * @return this builder
		 */
		public Builder lifetime(Duration lifetime) {
			Objects.requireNonNull(lifetime, "lifetime");
			if (lifetime.isNegative() || lifetime.isZero() ||
					lifetime.compareTo(FederationStatement.Purpose.NODE_DESCRIPTOR.maxLifetime()) > 0)
				throw new IllegalArgumentException("Invalid descriptor lifetime: " + lifetime);

			this.lifetime = lifetime;
			return this;
		}

		/**
		 * Signs the descriptor.
		 *
		 * @param node the node's identity
		 * @return the signed descriptor
		 */
		public NodeDescriptor sign(Identity node) {
			Objects.requireNonNull(node, "node");

			Map<String, Object> content = new LinkedHashMap<>();
			putIfPresent(content, "software", software);
			putIfPresent(content, "version", version);
			putIfPresent(content, "name", name);
			putIfPresent(content, "logo", logo);
			putIfPresent(content, "website", website);
			putIfPresent(content, "contact", contact);
			content.put("mode", mode.wireName());
			if (!protocols.isEmpty())
				content.put("protocols", List.copyOf(protocols));
			if (!capabilities.isEmpty())
				content.put("capabilities", List.copyOf(capabilities));
			List<Map<String, Object>> listed = new ArrayList<>(services.size());
			for (Service s : services)
				listed.add(s.toContent());
			content.put("services", listed);

			byte[] encoded = FederationStatement.builder(node, FederationStatement.Purpose.NODE_DESCRIPTOR)
					.lifetime(lifetime)
					.content(content)
					.build();

			try {
				return verify(encoded, node.getId());
			} catch (CwtException e) {
				throw new IllegalStateException("INTERNAL ERROR: a descriptor just signed does not verify", e);
			}
		}

		private static void putIfPresent(Map<String, Object> map, String key, @Nullable String value) {
			if (value != null)
				map.put(key, value);
		}
	}
}
