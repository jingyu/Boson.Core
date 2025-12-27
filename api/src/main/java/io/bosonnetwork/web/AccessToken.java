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

import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.utils.Json;

/**
 * Helper class for generating Compact Web Tokens (CWT) signed by an Identity.
 * <p>
 * This class is useful for clients (Users, Devices, Peers) to generate tokens
 * for authentication with the Boson Director or other services.
 * </p>
 */
public class AccessToken {
	private static final Base64.Encoder B64encoder = Base64.getUrlEncoder().withoutPadding();

	private final Identity issuer;

	/**
	 * Creates a new AccessToken generator for the given identity.
	 * 
	 * @param issuer the identity that will issue and sign the tokens
	 */
	public AccessToken(Identity issuer) {
		this.issuer = Objects.requireNonNull(issuer, "issuer cannot be null");
	}

	/**
	 * Generates a signed token.
	 * 
	 * @param subject the subject ID (usually the same as issuer or a user ID if issuer is a device)
	 * @param associated the associated entity ID (optional, e.g. device ID)
	 * @param audience the audience ID (the server node ID)
	 * @param scope the scope string (optional)
	 * @param ttl the time-to-live in seconds
	 * @return the generated token string
	 */
	private String generate(Id subject, Id associated, Id audience, String scope, long ttl) {
		Objects.requireNonNull(subject, "subject cannot be null");
        Objects.requireNonNull(audience, "audience cannot be null");
		if (ttl <= 0)
			throw new IllegalArgumentException("ttl must be positive");

		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("jti", Random.randomBytes(24));
		claims.put("iss", issuer.getId().bytes());
		claims.put("sub", subject.bytes());
		if (associated != null)
			claims.put("asc", associated.bytes());
		claims.put("aud", audience.bytes());
        if (scope != null && !scope.isEmpty())
			claims.put("scp", scope);
		
		long now = System.currentTimeMillis() / 1000;
		claims.put("exp", now + ttl);

		byte[] payload;
		try {
			payload = Json.cborMapper().writeValueAsBytes(claims);
		} catch (IOException e) {
			throw new RuntimeException("Failed to serialize token claims", e);
		}

		byte[] sig = issuer.sign(payload);

		return B64encoder.encodeToString(payload) + "." + B64encoder.encodeToString(sig);
	}

	/**
	 * Generates a signed token as the subject and without associated entity.
	 *
	 * @param audience the audience ID (the server node ID)
	 * @param scope the scope string (optional)
	 * @param ttl the time-to-live in seconds
	 * @return the generated token string
	 */
	public String generate(Id audience, String scope, long ttl) {
		return generate(issuer.getId(), null, audience, scope, ttl);
	}

	/**
	 * Generates a signed token as the associated entity.
	 *
	 * @param subject the subject ID (usually the same as issuer or a user ID if issuer is a device)
	 * @param audience the audience ID (the server node ID)
	 * @param scope the scope string (optional)
	 * @param ttl the time-to-live in seconds
	 * @return the generated token string
	 */
	public String generate(Id subject, Id audience, String scope, long ttl) {
		return generate(subject, issuer.getId(), audience, scope, ttl);
	}
}