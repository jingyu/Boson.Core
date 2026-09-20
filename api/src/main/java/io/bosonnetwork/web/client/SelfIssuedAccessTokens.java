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

package io.bosonnetwork.web.client;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.function.Supplier;

import io.vertx.core.Future;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.cwt.SignedCwt;
import io.bosonnetwork.service.AccessScope;

/**
 * The access tokens of a client that issues its own, signed with its own key, instead of obtaining
 * them from the service it calls. A Boson node accepts a token whose issuer is its subject (a user's
 * key) or its client id (a device's or a service's key), and grants what that principal is entitled
 * to, not what the token claims.
 * <p>
 * A self-issued token is only as good as the clock it is dated by. The node tolerates some skew
 * ({@code CwtAuthOptions} leeway, five minutes by default), and each token is backdated a little on
 * top of that. Beyond both, a node that rejects a token reveals its own clock in the {@code Date}
 * header of the answer: if that clock is far from ours, tokens are dated by it from then on, and the
 * rejected request is repeated once. For that to work, the caller must pass the date of the refusal
 * to {@link #rejected} and honour its answer - and the node must send the header, which
 * {@link io.bosonnetwork.web.ServerDateHandler} is for.
 * <p>
 * Instances are thread-safe, and are meant to be long-lived: one per client, per service it calls.
 */
public final class SelfIssuedAccessTokens implements AccessTokenSource {
	/** Lifetime of a token unless {@link Builder#lifetime} says otherwise. */
	public static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(10);

	/**
	 * How far back a token is dated, so that a node whose clock is a little behind ours does not see
	 * it as issued in the future.
	 */
	public static final Duration BACKDATE = Duration.ofMinutes(1);

	/**
	 * A node clock further than this from ours is taken as the reason a token was rejected. Less than
	 * {@link #BACKDATE}, so that any skew the backdating does not absorb gets corrected.
	 */
	public static final Duration MAX_CLOCK_SKEW = Duration.ofSeconds(30);

	// A token is renewed this long before it expires, so that no request carries a token that expires
	// while the request is in flight.
	private static final long REFRESH_MARGIN = 60 * 1000;

	private final Identity issuer;
	private final Id subject;
	private final @Nullable Id clientId;
	private final String scope;
	// The id of the node the tokens are for; a node accepts only tokens addressed to it. A supplier,
	// because a client may have to ask the node for its id before it can issue its first token.
	private final Supplier<Future<Id>> audience;
	private final long lifetime;
	private final Logger log;

	private final Object lock = new Object();
	// All guarded by lock: the cached token, and when it expires by our clock in epoch milliseconds;
	// how far the node's clock is ahead of ours; and the token whose rejection last corrected that.
	private @Nullable String token;
	private long expiresAt;
	private long clockOffset;
	private @Nullable String skewedToken;

	private SelfIssuedAccessTokens(Builder builder) {
		this.issuer = Objects.requireNonNull(builder.issuer, "issuer");
		this.subject = Objects.requireNonNull(builder.subject, "subject");
		this.clientId = builder.clientId;
		this.scope = Objects.requireNonNull(builder.scope, "scope");
		this.audience = Objects.requireNonNull(builder.audience, "audience");
		this.lifetime = builder.lifetime;
		this.log = builder.log != null ? builder.log : LoggerFactory.getLogger(SelfIssuedAccessTokens.class);
	}

	/**
	 * Creates a builder for tokens signed by an identity.
	 *
	 * @param issuer the key that signs the tokens: a user's, a device's or a service's
	 * @return a new builder
	 */
	public static Builder builder(Identity issuer) {
		return new Builder(issuer);
	}

	@Override
	public Future<String> token() {
		return audience.get().map(this::current);
	}

	// Returns the cached token, or a new one if it is about to expire.
	private String current(Id audienceId) {
		synchronized (lock) {
			long now = System.currentTimeMillis();
			String current = token;
			if (current != null && now < expiresAt - REFRESH_MARGIN)
				return current;

			long serviceNow = now + clockOffset;
			SignedCwt.Builder builder = SignedCwt.builder(issuer)
					.subject(subject)
					.audience(audienceId)
					.issuedAt(new Date(serviceNow - BACKDATE.toMillis()))
					.notBefore(new Date(serviceNow - BACKDATE.toMillis()))
					.expiration(new Date(serviceNow + lifetime))
					.scope(scope);
			if (clientId != null)
				builder.clientId(clientId);

			current = builder.buildToString();
			token = current;
			expiresAt = now + lifetime;
			return current;
		}
	}

	@Override
	public boolean rejected(String rejected, @Nullable Instant serverDate) {
		long now = System.currentTimeMillis();

		synchronized (lock) {
			// Most rejections are for the key, not the token, and a new token would fare no better; it is
			// dropped anyway, since issuing another costs only a signature.
			if (rejected.equals(token))
				token = null;

			if (serverDate != null) {
				long offset = serverDate.toEpochMilli() - now;
				if (Math.abs(offset - clockOffset) > MAX_CLOCK_SKEW.toMillis()) {
					log.warn("The service clock is {} seconds ahead of the local clock; dating access tokens by the service clock",
							offset / 1000);
					clockOffset = offset;
					token = null;
					skewedToken = rejected;
					return true;
				}
			}

			// Calls that carried the token that revealed the skew were rejected for the same reason, and
			// deserve the same repeat; they find the clock already corrected.
			return rejected.equals(skewedToken);
		}
	}

	/**
	 * How far the service's clock is ahead of the local one, as last learned from a refusal.
	 *
	 * @return the offset in milliseconds, negative if the service clock is behind; {@code 0} until a
	 *         refusal reveals otherwise
	 */
	public long clockOffset() {
		synchronized (lock) {
			return clockOffset;
		}
	}

	/**
	 * Builds a {@link SelfIssuedAccessTokens}. The subject, the scope and the audience are required.
	 * Not thread-safe.
	 */
	public static final class Builder {
		private final Identity issuer;
		private @Nullable Id subject;
		private @Nullable Id clientId;
		private @Nullable String scope;
		private @Nullable Supplier<Future<Id>> audience;
		private long lifetime = DEFAULT_LIFETIME.toMillis();
		private @Nullable Logger log;

		private Builder(Identity issuer) {
			this.issuer = Objects.requireNonNull(issuer, "issuer");
		}

		/**
		 * Sets the principal the tokens act for (required): the user, or the node a service acts for.
		 *
		 * @param subject the subject id
		 * @return this builder
		 */
		public Builder subject(Id subject) {
			this.subject = Objects.requireNonNull(subject, "subject");
			return this;
		}

		/**
		 * Sets the client the tokens act as - a device, or a service - whose key must then be the
		 * issuer. Left unset for a user's own tokens.
		 *
		 * @param clientId the client id
		 * @return this builder
		 */
		public Builder clientId(Id clientId) {
			this.clientId = Objects.requireNonNull(clientId, "clientId");
			return this;
		}

		/**
		 * Sets the access scope claimed (required).
		 *
		 * @param scope the scope
		 * @return this builder
		 */
		public Builder scope(AccessScope scope) {
			return scope(Objects.requireNonNull(scope, "scope").toString());
		}

		/**
		 * Sets the access scope claimed (required), as a string.
		 *
		 * @param scope the scope
		 * @return this builder
		 */
		public Builder scope(String scope) {
			this.scope = Objects.requireNonNull(scope, "scope");
			return this;
		}

		/**
		 * Sets the node the tokens are for (required), when its id is known.
		 *
		 * @param nodeId the id of the node that will accept the tokens
		 * @return this builder
		 */
		public Builder audience(Id nodeId) {
			Objects.requireNonNull(nodeId, "nodeId");
			Future<Id> resolved = Future.succeededFuture(nodeId);
			this.audience = () -> resolved;
			return this;
		}

		/**
		 * Sets how to find the node the tokens are for (required), when its id has to be looked up.
		 * The supplier is called before every token is issued, so it is expected to answer from a
		 * cache once it has an answer.
		 *
		 * @param nodeId supplies the id of the node that will accept the tokens
		 * @return this builder
		 */
		public Builder audience(Supplier<Future<Id>> nodeId) {
			this.audience = Objects.requireNonNull(nodeId, "nodeId");
			return this;
		}

		/**
		 * Sets how long a token is valid for; {@link #DEFAULT_LIFETIME} by default. Kept short is
		 * good: a token is a bearer credential, and issuing a new one costs a signature rather than a
		 * round trip.
		 *
		 * @param lifetime the lifetime, longer than the time it takes to make a request
		 * @return this builder
		 * @throws IllegalArgumentException if the lifetime is not positive
		 */
		public Builder lifetime(Duration lifetime) {
			Objects.requireNonNull(lifetime, "lifetime");
			if (lifetime.isNegative() || lifetime.isZero())
				throw new IllegalArgumentException("Invalid token lifetime: " + lifetime);
			this.lifetime = lifetime.toMillis();
			return this;
		}

		/**
		 * Sets the logger a clock correction is reported to; the owning client's, so that the warning
		 * names the component whose clock is off. Defaults to this class's own logger.
		 *
		 * @param log the logger
		 * @return this builder
		 */
		public Builder logger(Logger log) {
			this.log = Objects.requireNonNull(log, "log");
			return this;
		}

		/**
		 * Builds the token source.
		 *
		 * @return the token source
		 * @throws NullPointerException if the subject, the scope or the audience is missing
		 */
		public SelfIssuedAccessTokens build() {
			return new SelfIssuedAccessTokens(this);
		}
	}
}
