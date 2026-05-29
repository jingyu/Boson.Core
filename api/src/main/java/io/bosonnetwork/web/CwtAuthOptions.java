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

package io.bosonnetwork.web;

import java.util.Objects;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;

/**
 * Authentication options for configuring the {@link CwtAuth} provider.
 * <p>
 * This class provides configuration properties for cryptographic identity, client
 * lookup, audience restrictions, token lifetime (TTL), and clock skew leeway.
 * </p>
 */
public class CwtAuthOptions {
	private static final int DEFAULT_LEEWAY = 5 * 60;           // 5 minutes in seconds
	private static final int DEFAULT_TTL = 14 * 24 * 60 * 60;   // 14 days in seconds

	private Identity identity;
	private ClientProvider clientProvider;
	private Id expectedAudience;
	private int leeway; // seconds
	private int defaultTtl; // seconds
	private String defaultScope;

	/**
	 * Creates a new instance of {@code CwtAuthOptions} with default settings.
	 * <p>
	 * The default clock skew leeway is 5 minutes (300 seconds) and the default
	 * token time-to-live (TTL) is 14 days.
	 * </p>
	 */
	public CwtAuthOptions() {
		this.leeway = DEFAULT_LEEWAY;
		this.defaultTtl = DEFAULT_TTL;
	}

	/**
	 * Returns the identity representing the server (super node or service) which
	 * will sign new tokens and verify received tokens.
	 *
	 * @return the signing and verification identity, or null if not configured
	 */
	public Identity getIdentity() {
		return identity;
	}

	/**
	 * Sets the identity representing the server which is used for cryptographic operations.
	 *
	 * @param identity the identity to set; must not be null
	 * @return this CwtAuthOptions instance for method chaining
	 * @throws NullPointerException if identity is null
	 */
	public CwtAuthOptions setIdentity(Identity identity) {
		this.identity = Objects.requireNonNull(identity, "identity cannot be null");
		return this;
	}

	/**
	 * Returns the provider used to resolve user and device client metadata during authentication.
	 *
	 * @return the client provider, or null if not configured
	 */
	public ClientProvider getClientProvider() {
		return clientProvider;
	}

	/**
	 * Sets the provider used to look up users and devices during token verification.
	 *
	 * @param clientProvider the client provider to set; must not be null
	 * @return this CwtAuthOptions instance for method chaining
	 * @throws NullPointerException if clientProvider is null
	 */
	public CwtAuthOptions setClientProvider(ClientProvider clientProvider) {
		this.clientProvider = Objects.requireNonNull(clientProvider, "clientProvider cannot be null");
		return this;
	}

	/**
	 * Returns the expected audience ID that incoming tokens must target.
	 *
	 * @return the expected audience ID, or null if audience check is disabled
	 */
	public Id getExpectedAudience() {
		return expectedAudience;
	}

	/**
	 * Sets the expected audience ID to enforce on received tokens.
	 * <p>
	 * Audience validation is <b>optional and opt-in</b>. When set, only tokens whose {@code aud}
	 * claim equals this ID are accepted. When left {@code null} (the default), the {@code aud} claim
	 * is not checked, so a token minted for another server can be replayed against this one;
	 * {@link CwtAuth} logs a warning at construction time in that case. Production deployments should
	 * set this to the local server (node or service) {@link Id}.
	 *
	 * @param expectedAudience the expected audience ID; if null, audience validation is disabled
	 * @return this CwtAuthOptions instance for method chaining
	 */
	public CwtAuthOptions setExpectedAudience(Id expectedAudience) {
		this.expectedAudience = expectedAudience;
		return this;
	}

	/**
	 * Returns the clock skew leeway in seconds allowed for time-based claim validation
	 * (e.g. expiration, not-before, and issued-at checks).
	 *
	 * @return the clock skew leeway in seconds
	 */
	public int getLeeway() {
		return leeway;
	}

	/**
	 * Sets the clock skew leeway allowed when validating time-based claims.
	 *
	 * @param leeway the allowed clock skew in seconds; must be non-negative
	 * @return this CwtAuthOptions instance for method chaining
	 * @throws IllegalArgumentException if leeway is negative
	 */
	public CwtAuthOptions setLeeway(int leeway) {
		if (leeway < 0)
			throw new IllegalArgumentException("leeway must be >= 0");
		this.leeway = leeway;
		return this;
	}

	/**
	 * Returns the default token time-to-live (TTL) in seconds used when generating new tokens.
	 *
	 * @return the default token TTL in seconds
	 */
	public int getDefaultTtl() {
		return defaultTtl;
	}

	/**
	 * Sets the default token time-to-live (TTL) used when generating tokens.
	 *
	 * @param defaultTtl the default TTL in seconds; must be non-negative
	 * @return this CwtAuthOptions instance for method chaining
	 * @throws IllegalArgumentException if defaultTtl is negative
	 */
	public CwtAuthOptions setDefaultTtl(int defaultTtl) {
		if (defaultTtl < 0)
			throw new IllegalArgumentException("defaultTtl must be >= 0");
		this.defaultTtl = defaultTtl;
		return this;
	}

	/**
	 * Returns the default access scope to be used for token generation.
	 *
	 * @return the default access scope, or null if no default scope is configured
	 */
	public String getDefaultScope() {
		return defaultScope;
	}

	/**
	 * Sets the default access scope to be used for token generation.
	 *
	 * @param defaultScope the default access scope to set; may be null to disable default scope
	 * @return this CwtAuthOptions instance for method chaining
	 */
	public CwtAuthOptions setDefaultScope(String defaultScope) {
		this.defaultScope = defaultScope;
		return this;
	}
}