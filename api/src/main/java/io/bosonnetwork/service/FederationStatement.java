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
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.cwt.Claim;
import io.bosonnetwork.cwt.CwtException;
import io.bosonnetwork.cwt.InvalidClaimException;
import io.bosonnetwork.cwt.ReplayCache;
import io.bosonnetwork.cwt.SignedCwt;

/**
 * A statement a node signs about itself for another node or for anyone: a proposal, an answer, a
 * revocation, or the node's {@link NodeDescriptor descriptor}.
 * <p>
 * A statement is a CWT whose issuer and subject are the node, and whose {@link Claim#PURPOSE purpose}
 * claim is the label of what it is ({@code boson:sig:1:federation-propose}, ...). The label is inside
 * the signed payload, so a statement of one kind can't be taken for another, nor for an access token:
 * an access token never carries the claim, and {@link io.bosonnetwork.web.CwtAuth} refuses any token
 * that does. Nothing a remote party chooses is signed except as {@link Builder#content content} of a
 * statement the node composes itself.
 * <p>
 * The {@linkplain Purpose#isAddressed() addressed} purposes are meant for one node: they carry its id
 * as the audience, live minutes, and carry a random 16-byte "CWT ID" (cti) so that the receiver can
 * accept each once, with a {@link ReplayCache}. A {@linkplain Purpose#NODE_DESCRIPTOR descriptor} is
 * for anyone: it has no audience and no id, and may be cached and served again by others, since the
 * signature travels with it.
 */
public final class FederationStatement {
	/** The size of the "CWT ID" (cti) of an addressed statement, in bytes. */
	public static final int TOKEN_ID_BYTES = 16;
	/** The clock skew a verifier tolerates by default, in seconds. */
	public static final int DEFAULT_LEEWAY = 120;

	/** The longest note a proposal may carry, in characters. */
	public static final int MAX_NOTE_LENGTH = 256;
	/** The largest statement a node sends or accepts, in bytes; a descriptor with every service fits. */
	public static final int MAX_STATEMENT_BYTES = 8 * 1024;

	private static final String LABEL_PREFIX = "boson:sig:1:";

	private static final String NOTE = "note";
	private static final String IN_REPLY_TO = "inReplyTo";

	/**
	 * What a statement is. Each purpose has its own label, maximum lifetime, and whether it is
	 * addressed to one node.
	 */
	public enum Purpose {
		/** A node proposes a federation relationship to another. */
		PROPOSE("federation-propose", Duration.ofMinutes(5), true),
		/** A node accepts a proposal it received. */
		ACCEPT("federation-accept", Duration.ofMinutes(5), true),
		/** A node declines a proposal it received. */
		DECLINE("federation-decline", Duration.ofMinutes(5), true),
		/** A node tells another that it has suspended or ended their relationship. */
		REVOKE("federation-revoke", Duration.ofMinutes(5), true),
		/** A node describes itself and the services it runs, for anyone. */
		NODE_DESCRIPTOR("node-descriptor", Duration.ofDays(1), false);

		private final String label;
		private final Duration maxLifetime;
		private final boolean addressed;

		Purpose(String name, Duration maxLifetime, boolean addressed) {
			this.label = LABEL_PREFIX + name;
			this.maxLifetime = maxLifetime;
			this.addressed = addressed;
		}

		/**
		 * Returns the label carried in the {@link Claim#PURPOSE purpose} claim.
		 *
		 * @return the label, a Boson string in the {@code sig} label namespace
		 */
		public String label() {
			return label;
		}

		/**
		 * Returns the longest a statement of this purpose may live, from issue to expiration.
		 *
		 * @return the maximum lifetime
		 */
		public Duration maxLifetime() {
			return maxLifetime;
		}

		/**
		 * Tells whether a statement of this purpose is meant for one node: it then carries the node's id
		 * as its audience and a "CWT ID" (cti) for replay protection.
		 *
		 * @return {@code true} if the purpose is addressed
		 */
		public boolean isAddressed() {
			return addressed;
		}

		static @Nullable Purpose ofLabel(@Nullable String label) {
			for (Purpose p : values()) {
				if (p.label.equals(label))
					return p;
			}

			return null;
		}
	}

	private final Purpose purpose;
	private final Id issuer;
	private final @Nullable Id audience;
	private final Instant issuedAt;
	private final Instant expiresAt;
	private final byte @Nullable [] tokenId;
	private final Map<String, Object> content;

	private FederationStatement(Purpose purpose, Id issuer, @Nullable Id audience, Instant issuedAt,
								Instant expiresAt, byte @Nullable [] tokenId, Map<String, Object> content) {
		this.purpose = purpose;
		this.issuer = issuer;
		this.audience = audience;
		this.issuedAt = issuedAt;
		this.expiresAt = expiresAt;
		this.tokenId = tokenId;
		this.content = content;
	}

	/**
	 * Returns what the statement is.
	 *
	 * @return the purpose
	 */
	public Purpose getPurpose() {
		return purpose;
	}

	/**
	 * Returns the node that made the statement: its issuer, which is also its subject.
	 *
	 * @return the issuing node's id
	 */
	public Id getIssuer() {
		return issuer;
	}

	/**
	 * Returns the node the statement is for.
	 *
	 * @return the audience, or {@code null} for a statement meant for anyone
	 */
	public @Nullable Id getAudience() {
		return audience;
	}

	/**
	 * Returns when the statement was issued, by its issuer's clock.
	 *
	 * @return the issue date
	 */
	public Instant getIssuedAt() {
		return issuedAt;
	}

	/**
	 * Returns when the statement expires, by its issuer's clock.
	 *
	 * @return the expiration date
	 */
	public Instant getExpiresAt() {
		return expiresAt;
	}

	/**
	 * Returns the statement's "CWT ID" (cti).
	 *
	 * @return a copy of the id, or {@code null} for a statement that is not addressed
	 */
	public byte @Nullable [] getTokenId() {
		return tokenId == null ? null : tokenId.clone();
	}

	/**
	 * Returns the statement's content.
	 *
	 * @return an unmodifiable map, empty if the statement has none
	 */
	public Map<String, Object> getContent() {
		return content;
	}

	/**
	 * Returns the note a proposal carries, for the other node's operator.
	 *
	 * @return the note, or {@code null} if the statement has none, or one that is blank, too long, or not
	 *         text
	 */
	public @Nullable String note() {
		Object note = content.get(NOTE);
		return note instanceof String n && !n.isBlank() && n.length() <= MAX_NOTE_LENGTH ? n : null;
	}

	/**
	 * Returns the "CWT ID" (cti) of the proposal an answer replies to.
	 *
	 * @return a copy of the id, or {@code null} if the statement names none
	 */
	public byte @Nullable [] inReplyTo() {
		return content.get(IN_REPLY_TO) instanceof byte[] b ? b.clone() : null;
	}

	/**
	 * Starts a statement of the given purpose, made by the given node.
	 *
	 * @param issuer  the node's identity, which signs the statement
	 * @param purpose what the statement is
	 * @return a new builder
	 */
	public static Builder builder(Identity issuer, Purpose purpose) {
		return new Builder(issuer, purpose);
	}

	/**
	 * Starts a verifier that accepts statements of the given purposes only.
	 *
	 * @param purposes the purposes to accept, at least one
	 * @return a new verifier
	 * @throws IllegalArgumentException if no purpose is given
	 */
	public static Verifier verifier(Purpose... purposes) {
		return new Verifier(purposes);
	}

	/**
	 * Builds a propose federation statement issued by the given node and addressed to the given node.
	 * If a note is provided, it is included in the statement content.
	 *
	 * @param node the identity of the node issuing and signing the propose statement
	 * @param toNodeId the identifier of the node the propose statement is addressed to
	 * @param note the optional note to include in the statement, or null for no note
	 * @return the signed propose federation statement as a byte array
	 */
	public static byte[] propose(Identity node, Id toNodeId, @Nullable String note) {
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(toNodeId, "toNodeId");
		if (note != null && note.length() > MAX_NOTE_LENGTH)
			throw new IllegalArgumentException("Note longer than " + MAX_NOTE_LENGTH + " characters");

		Map<String, Object> content = note != null ? Map.of(NOTE, note) : Map.of();
		return FederationStatement.builder(node, Purpose.PROPOSE).audience(toNodeId).content(content).build();
	}

	/**
	 * Builds an acceptance federation statement issued by the given node, addressed to the given node, and referencing the statement being accepted.
	 *
	 * @param node the identity of the node issuing and signing the acceptance statement
	 * @param toNodeId the identifier of the node the acceptance statement is addressed to
	 * @param inReplyTo the identifier of the statement being accepted
	 * @return the signed acceptance federation statement as a byte array
	 */
	public static byte[] accept(Identity node, Id toNodeId, byte[] inReplyTo) {
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(toNodeId, "toNodeId");
		Objects.requireNonNull(inReplyTo, "inReplyTo");

		Map<String, Object> content = Map.of(IN_REPLY_TO, inReplyTo);
		return FederationStatement.builder(node, Purpose.ACCEPT).audience(toNodeId).content(content).build();
	}

	/**
	 * Builds a decline federation statement issued by the given node, addressed to the given node, and referencing the statement being declined.
	 *
	 * @param node the identity of the node issuing and signing the decline statement
	 * @param toNodeId the identifier of the node the decline statement is addressed to
	 * @param inReplyTo the identifier of the statement being declined
	 * @return the signed decline federation statement as a byte array
	 */
	public static byte[] decline(Identity node, Id toNodeId, byte[] inReplyTo) {
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(toNodeId, "toNodeId");
		Objects.requireNonNull(inReplyTo, "inReplyTo");

		Map<String, Object> content = Map.of(IN_REPLY_TO, inReplyTo);
		return FederationStatement.builder(node, Purpose.DECLINE).audience(toNodeId).content(content).build();
	}

	/**
	 * Builds a revoke federation statement issued by the given node and addressed to the given node.
	 *
	 * @param node the identity of the node issuing and signing the revoke statement
	 * @param toNodeId the identifier of the node the revoke statement is addressed to
	 * @return the signed revoke federation statement as a byte array
	 */
	public static byte[] revoke(Identity node, Id toNodeId) {
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(toNodeId, "toNodeId");

		return FederationStatement.builder(node, Purpose.REVOKE).audience(toNodeId).build();
	}

	/**
	 * Signs this node descriptor into a node descriptor federation statement.
	 * The statement contains the descriptor content, the given lifetime or the default maximum lifetime, and is signed by the given identity.
	 *
	 * @param node the signing identity; its identifier must match the descriptor's node identifier
	 * @param descriptor the node descriptor content
	 * @param lifetime the statement lifetime, or null to use the maximum allowed lifetime
	 * @return the signed federation statement as a byte array
	 * @throws NullPointerException if node is null
	 * @throws IllegalArgumentException if the node identifier does not match the descriptor's node identifier, or if lifetime is negative, zero, or exceeds the maximum allowed lifetime
	 */
	public static byte[] nodeDescriptor(Identity node, NodeDescriptor descriptor, @Nullable Duration lifetime) {
		Objects.requireNonNull(node, "node");
		Objects.requireNonNull(descriptor, "descriptor");
		if (!node.getId().equals(descriptor.getNodeId()))
			throw new IllegalArgumentException("Node identifier must be equal to the descriptor's node identifier");
		if (lifetime != null) {
			if (lifetime.isNegative() || lifetime.isZero() ||
					lifetime.compareTo(FederationStatement.Purpose.NODE_DESCRIPTOR.maxLifetime()) > 0)
				throw new IllegalArgumentException("Invalid descriptor lifetime: " + lifetime);
		} else {
			lifetime = Purpose.NODE_DESCRIPTOR.maxLifetime();
		}

		Map<String, Object> content = new LinkedHashMap<>();
		descriptor.getSoftware().ifPresent(s -> content.put("software", s));
		descriptor.getVersion().ifPresent(v -> content.put("version", v));
		descriptor.getName().ifPresent(n -> content.put("name", n));
		descriptor.getLogo().ifPresent(l -> content.put("logo", l));
		descriptor.getWebsite().ifPresent(w -> content.put("website", w));
		descriptor.getContact().ifPresent(c -> content.put("contact", c));
		descriptor.getMode().ifPresent(m -> content.put("mode", m.wireName()));
		if (!descriptor.getProtocols().isEmpty())
			content.put("protocols", List.copyOf(descriptor.getProtocols()));
		if (!descriptor.getCapabilities().isEmpty())
			content.put("capabilities", List.copyOf(descriptor.getCapabilities()));
		List<Map<String, Object>> listed = new ArrayList<>(descriptor.getServices().size());
		for (NodeDescriptor.Service s : descriptor.getServices())
			listed.add(s.toMap());
		content.put("services", listed);

		return FederationStatement.builder(node, Purpose.NODE_DESCRIPTOR)
				.lifetime(lifetime)
				.content(content)
				.build();
	}

	/**
	 * Signs the given node descriptor into a node descriptor federation statement using the default maximum lifetime.
	 * The statement contains the descriptor content and is signed by the given identity.
	 *
	 * @param node the signing identity; its identifier must match the descriptor's node identifier
	 * @param descriptor the node descriptor to sign
	 * @return the signed federation statement as a byte array
	 */
	public static byte[] nodeDescriptor(Identity node, NodeDescriptor descriptor) {
		return nodeDescriptor(node, descriptor, null);
	}

	/**
	 * Verifies and decodes a signed descriptor.
	 * <p>
	 * Readers ignore what they don't know: keys this version doesn't read are skipped, and a mode it
	 * doesn't know - one a newer version added - is left out, as if the node said none. Everything it does
	 * read must be well formed.
	 *
	 * @param encoded        the descriptor as served
	 * @param expectedNodeId the node it must be from
	 * @return the verified descriptor, dated as signed
	 * @throws CwtException if the descriptor is invalid, expired, malformed or from another node
	 */
	public static NodeDescriptor asDescriptor(byte[] encoded, Id expectedNodeId) throws CwtException {
		Objects.requireNonNull(encoded, "encoded");
		Objects.requireNonNull(expectedNodeId, "nodeId");

		FederationStatement statement = FederationStatement.verifier(FederationStatement.Purpose.NODE_DESCRIPTOR)
				.issuer(expectedNodeId)
				.verify(encoded);

		Map<String, Object> c = statement.getContent();

		List<String> protocols = textList(c.get("protocols"), "protocols");
		Set<String> capabilities = new LinkedHashSet<>(textList(c.get("capabilities"), "capabilities"));

		if (!(c.get("services") instanceof List<?> list))
			throw new InvalidClaimException("A node descriptor must list its services");
		List<NodeDescriptor.Service> services = new ArrayList<>(list.size());
		for (Object entry : list) {
			if (!(entry instanceof Map<?, ?> m))
				throw new InvalidClaimException("Invalid service entry");

			services.add(NodeDescriptor.Service.fromMap(m));
		}

		FederationMode mode = null;
		if (c.get("mode") instanceof String m) {
			try {
				mode = FederationMode.ofWireName(m);
			} catch (IllegalArgumentException ignore) {
				// A mode this version doesn't know.
			}
		}

		return new NodeDescriptor(statement.getIssuer(), text(c, "software"), text(c, "version"), text(c, "name"),
				text(c, "logo"), text(c, "website"), text(c, "contact"),
				mode, protocols, capabilities, services,
				statement.getIssuedAt().toEpochMilli(), statement.getExpiresAt().toEpochMilli());
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

	/**
	 * Builds and signs a statement.
	 */
	public static final class Builder {
		private final Identity issuer;
		private final Purpose purpose;
		private @Nullable Id audience;
		private Duration lifetime;
		private Map<String, Object> content = Map.of();

		private Builder(Identity issuer, Purpose purpose) {
			this.issuer = Objects.requireNonNull(issuer, "issuer");
			this.purpose = Objects.requireNonNull(purpose, "purpose");
			this.lifetime = purpose.maxLifetime;
		}

		/**
		 * Sets the node the statement is for. Required for an addressed purpose; not allowed for any other.
		 *
		 * @param audience the receiving node's id
		 * @return this builder
		 */
		public Builder audience(Id audience) {
			this.audience = Objects.requireNonNull(audience, "audience");
			return this;
		}

		/**
		 * Sets how long the statement lives. Defaults to, and may not exceed, the purpose's
		 * {@linkplain Purpose#maxLifetime() maximum lifetime}.
		 *
		 * @param lifetime the lifetime, positive
		 * @return this builder
		 */
		public Builder lifetime(Duration lifetime) {
			Objects.requireNonNull(lifetime, "lifetime");
			if (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(purpose.maxLifetime) > 0)
				throw new IllegalArgumentException("Invalid lifetime for " + purpose + ": " + lifetime);

			this.lifetime = lifetime;
			return this;
		}

		/**
		 * Sets the statement's content: a map with text keys whose values CBOR can carry (text, numbers,
		 * booleans, byte arrays, and lists and maps of them), laid out by the purpose.
		 *
		 * @param content the content
		 * @return this builder
		 */
		public Builder content(Map<String, Object> content) {
			this.content = Map.copyOf(Objects.requireNonNull(content, "content"));
			return this;
		}

		/**
		 * Signs the statement.
		 *
		 * @return the statement, a COSE_Sign1 CWT
		 * @throws IllegalStateException if an addressed statement has no audience, or another has one
		 */
		public byte[] build() {
			if (purpose.addressed && audience == null)
				throw new IllegalStateException(purpose + " statement needs an audience");
			if (!purpose.addressed && audience != null)
				throw new IllegalStateException(purpose + " statement is for anyone, it takes no audience");

			long now = System.currentTimeMillis() / 1000;
			SignedCwt.Builder cwt = SignedCwt.builder(issuer)
					.subject(issuer.getId())
					.issuedAt(new Date(now * 1000))
					.expiration(new Date((now + lifetime.toSeconds()) * 1000))
					.claim(Claim.PURPOSE.getValue(), purpose.label);

			if (audience != null)
				cwt.audience(audience);
			if (purpose.addressed)
				cwt.tokenId(Random.randomBytesSecure(TOKEN_ID_BYTES));
			if (!content.isEmpty())
				cwt.claim(Claim.CONTENT.getValue(), new LinkedHashMap<>(content));

			return cwt.build();
		}
	}

	/**
	 * Verifies statements of one or more purposes. The statement's own label says which it is, so it is
	 * parsed once whatever the number of purposes.
	 */
	public static final class Verifier {
		private final List<Purpose> purposes;
		private @Nullable Id expectedIssuer;
		private @Nullable Id expectedAudience;
		private @Nullable ReplayCache replayCache;
		private int leeway = DEFAULT_LEEWAY;

		private Verifier(Purpose... purposes) {
			Objects.requireNonNull(purposes, "purposes");
			if (purposes.length == 0)
				throw new IllegalArgumentException("No purpose to verify");

			this.purposes = List.of(purposes);
		}

		/**
		 * Accepts statements made by this node only.
		 *
		 * @param issuer the expected issuing node
		 * @return this verifier
		 */
		public Verifier issuer(Id issuer) {
			this.expectedIssuer = Objects.requireNonNull(issuer, "issuer");
			return this;
		}

		/**
		 * Sets this node's id, which an addressed statement must carry as its audience. Required if any of
		 * the purposes is addressed; a statement of a purpose that is not must carry no audience at all.
		 *
		 * @param audience this node's id
		 * @return this verifier
		 */
		public Verifier audience(Id audience) {
			this.expectedAudience = Objects.requireNonNull(audience, "audience");
			return this;
		}

		/**
		 * Accepts each addressed statement once, remembered in the given cache. Use one cache for every
		 * addressed purpose a node verifies.
		 *
		 * @param replayCache the cache
		 * @return this verifier
		 */
		public Verifier replayCache(ReplayCache replayCache) {
			this.replayCache = Objects.requireNonNull(replayCache, "replayCache");
			return this;
		}

		/**
		 * Sets the clock skew tolerated on the issue, not-before and expiration dates.
		 *
		 * @param seconds the leeway, in seconds, not negative
		 * @return this verifier
		 */
		public Verifier leeway(int seconds) {
			if (seconds < 0)
				throw new IllegalArgumentException("Invalid leeway: " + seconds);

			this.leeway = seconds;
			return this;
		}

		/**
		 * Verifies a statement.
		 *
		 * @param statement the statement, a COSE_Sign1 CWT
		 * @return the verified statement
		 * @throws CwtException if the statement is invalid, expired, of none of the purposes, not for this
		 *                      node, not from the expected node, or already seen
		 * @throws IllegalStateException if a purpose is addressed and no audience was set, whatever the
		 *                               statement
		 */
		public FederationStatement verify(byte[] statement) throws CwtException {
			Objects.requireNonNull(statement, "statement");
			// A mistake in the verifier, not in the statement: refused before reading it, so that it shows
			// whatever the statement turns out to be.
			if (expectedAudience == null) {
				for (Purpose p : purposes) {
					if (p.addressed)
						throw new IllegalStateException("Verifying " + p + " statements needs this node's id as the audience");
				}
			}

			SignedCwt.Parser parser = SignedCwt.parser().setLeeway(leeway);
			if (expectedIssuer != null)
				parser.requireIssuer(expectedIssuer);

			SignedCwt cwt = parser.parse(statement);

			Id issuer = cwt.getClaimAsId(Claim.ISSUER.getValue());
			if (issuer == null)
				throw new InvalidClaimException("Missing issuer");

			Id subject;
			try {
				subject = cwt.getClaimAsId(Claim.SUBJECT.getValue());
			} catch (Exception e) {
				throw new InvalidClaimException("Invalid subject", e);
			}
			if (!issuer.equals(subject))
				throw new InvalidClaimException("A statement's subject must be its issuer");

			String label = cwt.getClaimAsString(Claim.PURPOSE.getValue());

			Purpose purpose = Purpose.ofLabel(label);
			if (purpose == null || !purposes.contains(purpose))
				throw new InvalidClaimException("Not " + purposes.stream().map(Purpose::name)
						.collect(Collectors.joining(" or ")) + " statement: " + label);

			Number iat = cwt.getClaim(Claim.ISSUED_AT.getValue());
			Number exp = cwt.getClaim(Claim.EXPIRATION.getValue());
			if (iat == null || exp == null)
				throw new InvalidClaimException("A statement must carry its issue and expiration dates");
			if (exp.longValue() - iat.longValue() > purpose.maxLifetime.toSeconds())
				throw new InvalidClaimException(purpose + " statement lives longer than " + purpose.maxLifetime);

			Id audience = null;
			byte[] tokenId = null;
			if (purpose.addressed) {
				try {
					audience = cwt.getClaimAsId(Claim.AUDIENCE.getValue());
				} catch (Exception e) {
					throw new InvalidClaimException("Invalid audience", e);
				}
				// Checked up front: an addressed purpose has an expected audience.
				if (!Objects.requireNonNull(expectedAudience).equals(audience))
					throw new InvalidClaimException("Audience mismatch: expected " + expectedAudience + ", got " + audience);

				Object cti = cwt.getClaim(Claim.CWT_ID.getValue());
				if (!(cti instanceof byte[] b) || b.length != TOKEN_ID_BYTES)
					throw new InvalidClaimException("A " + purpose + " statement needs a " + TOKEN_ID_BYTES + "-byte id");
				tokenId = b.clone();

				if (replayCache != null)
					replayCache.accept(issuer, tokenId, exp.longValue() + leeway);
			} else if (cwt.containsClaim(Claim.AUDIENCE.getValue())) {
				throw new InvalidClaimException(purpose + " statement is for anyone, it takes no audience");
			}

			Object c = cwt.getClaim(Claim.CONTENT.getValue());
			Map<String, Object> content;
			if (c == null) {
				content = Map.of();
			} else if (c instanceof Map<?, ?> m) {
				Map<String, Object> copy = new LinkedHashMap<>(m.size());
				for (Map.Entry<?, ?> e : m.entrySet()) {
					if (!(e.getKey() instanceof String key))
						throw new InvalidClaimException("Content keys must be text");
					copy.put(key, e.getValue());
				}
				content = Collections.unmodifiableMap(copy);
			} else {
				throw new InvalidClaimException("Content must be a map");
			}

			return new FederationStatement(purpose, issuer, audience, Instant.ofEpochSecond(iat.longValue()),
					Instant.ofEpochSecond(exp.longValue()), tokenId, content);
		}
	}
}
