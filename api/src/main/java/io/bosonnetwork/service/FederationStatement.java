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
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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

	private static final String LABEL_PREFIX = "boson:sig:1:";

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
	 * Starts a verifier that accepts statements of the given purpose only.
	 *
	 * @param purpose the purpose to accept
	 * @return a new verifier
	 */
	public static Verifier verifier(Purpose purpose) {
		return new Verifier(purpose);
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
	 * Verifies statements of one purpose.
	 */
	public static final class Verifier {
		private final Purpose purpose;
		private @Nullable Id expectedIssuer;
		private @Nullable Id expectedAudience;
		private @Nullable ReplayCache replayCache;
		private int leeway = DEFAULT_LEEWAY;

		private Verifier(Purpose purpose) {
			this.purpose = Objects.requireNonNull(purpose, "purpose");
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
		 * Sets this node's id, which an addressed statement must carry as its audience. Required to verify
		 * an addressed purpose.
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
		 * @throws CwtException if the statement is invalid, expired, not of this purpose, not for this node,
		 *                      not from the expected node, or already seen
		 * @throws IllegalStateException if the purpose is addressed and no audience was set
		 */
		public FederationStatement verify(byte[] statement) throws CwtException {
			Objects.requireNonNull(statement, "statement");
			if (purpose.addressed && expectedAudience == null)
				throw new IllegalStateException("Verifying " + purpose + " statements needs this node's id as the audience");

			SignedCwt.Parser parser = SignedCwt.parser().setLeeway(leeway);
			if (expectedIssuer != null)
				parser.requireIssuer(expectedIssuer);
			if (expectedAudience != null && purpose.addressed)
				parser.requireAudience(expectedAudience);

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
			if (Purpose.ofLabel(label) != purpose)
				throw new InvalidClaimException("Not a " + purpose + " statement: " + label);

			Number iat = cwt.getClaim(Claim.ISSUED_AT.getValue());
			Number exp = cwt.getClaim(Claim.EXPIRATION.getValue());
			if (iat == null || exp == null)
				throw new InvalidClaimException("A statement must carry its issue and expiration dates");
			if (exp.longValue() - iat.longValue() > purpose.maxLifetime.toSeconds())
				throw new InvalidClaimException(purpose + " statement lives longer than " + purpose.maxLifetime);

			Id audience = null;
			byte[] tokenId = null;
			if (purpose.addressed) {
				// The parser has checked the audience against this node's id; it is present.
				audience = cwt.getClaimAsId(Claim.AUDIENCE.getValue());

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
