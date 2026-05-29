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

import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.AuthenticationProvider;
import io.vertx.ext.auth.authentication.CredentialValidationException;
import io.vertx.ext.auth.authentication.Credentials;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.authorization.Authorization;
import io.vertx.ext.auth.authorization.RoleBasedAuthorization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.cwt.Claim;
import io.bosonnetwork.cwt.SignedCwt;
import io.bosonnetwork.service.ClientDevice;
import io.bosonnetwork.service.ClientUser;
import io.bosonnetwork.service.Role;
import io.bosonnetwork.service.ServiceInfo;
import io.bosonnetwork.service.SuperNodeInfo;

/**
 * A CBOR Web Token (CWT) authentication provider.
 * <p>
 * This class implements a custom authentication mechanism using the CWT format
 * designed for efficiency. The token structure is based on the CWT standard (RFC 8392)
 * using CBOR and Base64Url encoding.
 */
public class CwtAuth implements AuthenticationProvider {
	private static final Logger log = LoggerFactory.getLogger(CwtAuth.class);

	private final Identity identity;
	private final ClientProvider clientProvider;
	private final int defaultTtl;
	private final String defaultScope;
	private final SignedCwt.Parser cwtParser;

	private CwtAuth(CwtAuthOptions options) {
		this.identity = Objects.requireNonNull(options.getIdentity(), "identity");
		this.clientProvider = Objects.requireNonNull(options.getClientProvider(), "clientProvider");
		this.defaultTtl = options.getDefaultTtl();
		this.defaultScope = options.getDefaultScope();

		this.cwtParser = SignedCwt.parser().setLeeway(options.getLeeway());
		if (options.getExpectedAudience() != null) {
			this.cwtParser.requireAudience(options.getExpectedAudience());
		} else {
			log.warn("CwtAuth: no expected audience configured - tokens are accepted regardless of their " +
					"'aud' claim, so a token minted for another server can be replayed against this one. " +
					"Set CwtAuthOptions.setExpectedAudience(localNodeId) to restrict tokens to this server.");
		}
	}

	/**
	 * Creates a new instance of {@code CwtAuth} using the specified authentication options.
	 *
	 * @param options the authentication options to configure the instance; must not be null
	 * @return a new {@code CwtAuth} instance configured with the provided options
	 * @throws NullPointerException if {@code options} is null
	 */
	public static CwtAuth create(CwtAuthOptions options) {
		Objects.requireNonNull(options, "options");
		return new CwtAuth(options);
	}

	/**
	 * Authenticates a user using the provided credentials.
	 * <p>
	 * Expected credentials type is {@link TokenCredentials}. The token is parsed, validated
	 * (signature, expiration, claims), and resolved against the {@link ClientProvider}.
	 * </p>
	 *
	 * @param credentials the credentials containing the token
	 * @return a Future containing the authenticated {@link User}
	 */
	@Override
	public Future<User> authenticate(Credentials credentials) {
		final TokenCredentials authInfo;
		try {
			// cast
			try {
				authInfo = (TokenCredentials) credentials;
			} catch (ClassCastException e) {
				throw new CredentialValidationException("Invalid credentials type", e);
			}
			// check
			authInfo.checkValid(null);
		} catch (RuntimeException e) {
			return Future.failedFuture(e);
		}

		final SignedCwt cwt;
		try {
			cwt = cwtParser.parse(authInfo.getToken());
		} catch (Exception e) {
			return Future.failedFuture(e);
		}

		// The token signature and basic structure have already been verified by the parser.
		// Extract the issuer; this is expected to be present if the parse succeeded.
		final Id issuerId = cwt.getClaimAsId(Claim.ISSUER.getValue());

		final Id userId;
		try {
			userId = cwt.getClaimAsId(Claim.SUBJECT.getValue());
		} catch (Exception e) {
			return Future.failedFuture("Invalid authorization token: invalid subject");
		}
		if (userId == null)
			return Future.failedFuture("Invalid authorization token: missing subject");

		final Id clientId;
		try {
			clientId = cwt.getClaimAsId(Claim.CLIENT_ID.getValue());
		} catch (Exception e) {
			return Future.failedFuture("Invalid authorization token: invalid client_id");
		}

		// The token may be issued by this identity (super node or service), by a user (self-issued),
		// or by a client (e.g., a user's device or a federated service).
		if (!issuerId.equals(identity.getId()) && 
			!issuerId.equals(userId) && 
			!(clientId != null && issuerId.equals(clientId)))
			return Future.failedFuture("Invalid authorization token: unacceptable issuer");

		final String scope = cwt.getClaimAsString(Claim.SCOPE.getValue());

		Future<?> getClient;
		if (clientId == null) {
			getClient = clientProvider.getUser(userId).transform(ar -> {
				if (ar.failed())
					return Future.failedFuture(ar.cause());

				if (ar.result() == null)
					return Future.failedFuture("Invalid authorization token: user not exists");

				return Future.succeededFuture(ar.result());
			});
		} else {
			getClient = clientProvider.getClient(userId, clientId).transform(ar -> {
				if (ar.failed())
					return Future.failedFuture(ar.cause());

				if (ar.result() == null)
					return Future.failedFuture("Invalid authorization token: client not exists");

				return Future.succeededFuture(ar.result());
			});
		}

		return getClient.map(client -> createUser(client, scope, cwt, authInfo.getToken()));
	}

	private User createUser(Object client, String scope, SignedCwt cwt, String accessToken) {
		final Id userId;
		final Id clientId;
		final Set<Authorization> authorizations = new HashSet<>();

		if (client instanceof ClientUser u) {
			userId = u.getId();
			clientId = null;
			authorizations.add(RoleBasedAuthorization.create(Role.CLIENT.toString()));
			if (u.isAdmin())
				authorizations.add(RoleBasedAuthorization.create(Role.ADMIN.toString()));
		} else if (client instanceof ClientDevice d) {
			userId = d.getUserId();
			clientId = d.getId();
			authorizations.add(RoleBasedAuthorization.create(Role.CLIENT.toString()));
		} else if (client instanceof SuperNodeInfo n) {
			userId = n.getId();
			clientId = null;
			authorizations.add(RoleBasedAuthorization.create(Role.FEDERATION.toString()));
		} else if (client instanceof ServiceInfo s) {
			userId = s.getNodeId();
			clientId = s.getPeerId();
			authorizations.add(RoleBasedAuthorization.create(Role.FEDERATION.toString()));
		} else {
			throw new IllegalStateException("Invalid client type: " + client.getClass().getName());
		}

		Map<String, Object> map = new LinkedHashMap<>();
		// "username" should be lowercase, see Vert.x User.subject()
		map.put("username", userId.toString());
		map.put("userId", userId);
		if (clientId != null)
			map.put("clientId", clientId);
		map.put("access_token", accessToken);
		if (scope != null)
			map.put("scope", scope);
		// sid identifies THIS session — needed when a user has multiple linked AuthIdentities sharing the same userId subject.
		if (cwt.containsClaim(Claim.SESSION_ID.getValue()))
			map.put("sessionId", cwt.getClaimAsId(Claim.SESSION_ID.getValue()));
		JsonObject principal = new JsonObject(Map.copyOf(map));

		map = new LinkedHashMap<>();
		map.put("client", client);
		map.put("accessToken", cwt);
		map.put("rootClaim", "accessToken");
		if (cwt.containsClaim(Claim.EXPIRATION.getValue()))
			map.put("exp", cwt.getClaim(Claim.EXPIRATION.getValue()));
		if (cwt.containsClaim(Claim.ISSUED_AT.getValue()))
			map.put("iat", cwt.getClaim(Claim.ISSUED_AT.getValue()));
		if (cwt.containsClaim(Claim.NOT_BEFORE.getValue()))
			map.put("nbf", cwt.getClaim(Claim.NOT_BEFORE.getValue()));
		map.put("sub", userId);
		JsonObject attributes = new JsonObject(Map.copyOf(map));

		User user = User.create(principal, attributes);
		user.authorizations().put("boson", authorizations);

		return user;
	}

	/**
	 * Generates a new token for a user and optional client.
	 *
	 * @param userId the user ID
	 * @param sessionId the session ID to embed as the {@code sid} claim (optional, can be null)
	 * @param clientId the client ID (optional, can be null)
	 * @param scope the scope associated with the token; can be null or optional
	 * @param ttl the time-to-live in seconds (0 for default server lifetime)
	 * @return the generated token string
	 * @throws IllegalArgumentException if expiration is invalid
	 */
	public String generateToken(Id userId, Id sessionId, Id clientId, String scope, long ttl) {
		Objects.requireNonNull(userId, "user cannot be null");
		if (ttl < 0)
			throw new IllegalArgumentException("ttl must be positive");

		SignedCwt.Builder cwtBuilder = SignedCwt.builder(identity)
				.subject(userId)
				.audience(identity.getId())
				.expiration(Duration.ofSeconds(ttl == 0 ? defaultTtl : ttl))
				.notBeforeNow()
				.issuedAtNow()
				.tokenId(Random.randomBytes(8));
		if (scope == null)
			scope = defaultScope;
		if (scope != null)
			cwtBuilder.scope(scope);
		if (clientId != null)
			cwtBuilder.clientId(clientId);
		if (sessionId != null)
			cwtBuilder.claim(Claim.SESSION_ID.getValue(), sessionId.bytesUnsafe());

		return cwtBuilder.buildToString();
	}

	/**
	 * Generates a new token for a user and optional client.
	 *
	 * @param userId the user ID
	 * @param clientId the client ID (optional, can be null)
	 * @param scope the scope associated with the token; can be null or optional
	 * @param ttl the time-to-live in seconds (0 for default server lifetime)
	 * @return the generated token string
	 * @throws IllegalArgumentException if expiration is invalid
	 */
	public String generateToken(Id userId, Id clientId, String scope, long ttl) {
		return generateToken(userId, null, clientId, scope, ttl);
	}

	/**
	 * Generates a new token for a user with standard lifetime.
	 *
	 * @param userId the user ID
	 * @param clientId the client ID (optional)
	 * @param scope the scope associated with the token; can be null or optional
	 * @return the generated token string
	 */
	public String generateToken(Id userId, Id clientId, String scope) {
		return generateToken(userId, clientId, scope, 0);
	}

	/**
	 * Generates a new token for a user with the specified scope.
	 *
	 * @param userId the user ID for whom the token will be generated; must not be null
	 * @param scope the scope associated with the token; can be null or optional
	 * @return the generated token string
	 */
	public String generateToken(Id userId, String scope) {
		return generateToken(userId, null, null, scope, 0);
	}
}