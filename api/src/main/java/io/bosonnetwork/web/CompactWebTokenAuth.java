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

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.authentication.AuthenticationProvider;
import io.vertx.ext.auth.authentication.CredentialValidationException;
import io.vertx.ext.auth.authentication.Credentials;
import io.vertx.ext.auth.authentication.TokenCredentials;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.service.ClientAuthenticator;
import io.bosonnetwork.service.ClientDevice;
import io.bosonnetwork.service.ClientUser;
import io.bosonnetwork.service.FederatedNode;
import io.bosonnetwork.service.ServiceInfo;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.utils.Pair;

/**
 * A Compact Web Token (CWT) authentication provider.
 * <p>
 * This class implements a custom authentication mechanism using a compact token format
 * designed for efficiency. The token structure is partially inspired by JWT but simplified
 * and using CBOR/base64url encoding.
 * </p>
 * <h2>Token Format</h2>
 * <pre>
 * token = payload.signature
 * payload = base64url(CBOR(claims))
 * signature = base64url(ED25519Signature(SHA256(payload)))
 * </pre>
 *
 * <h2>Claims Definition</h2>
 * <p>Server issued token claims:</p>
 * <ul>
 *   <li><b>jti</b>: Token ID (nonce)</li>
 *   <li><b>iss</b>: Issuer (null or server node ID)</li>
 *   <li><b>sub</b>: Subject (User ID / Federated node ID)</li>
 *   <li><b>asc</b>: Associated ID (Node ID / Federated service peer ID)</li>
 *   <li><b>exp</b>: Expiration timestamp</li>
 * </ul>
 *
 * <p>Client issued token claims:</p>
 * <ul>
 *   <li><b>jti</b>: Token ID (nonce)</li>
 *   <li><b>iss</b>: Issuer (Subject ID, or Associated ID if present)</li>
 *   <li><b>aud</b>: Audience (Server Node ID)</li>
 *   <li><b>sub</b>: Subject (User ID / Federated node ID)</li>
 *   <li><b>asc</b>: Associated ID (Node ID / Federated service peer ID)</li>
 *   <li><b>exp</b>: Expiration timestamp</li>
 * </ul>
 */
public class CompactWebTokenAuth implements AuthenticationProvider {
	/** Base64 URL encoder without padding. */
	protected static final Base64.Encoder B64encoder = Base64.getUrlEncoder().withoutPadding();
	/** Base64 URL decoder. */
	protected static final Base64.Decoder B64decoder = Base64.getUrlDecoder();

	private static final long MAX_SERVER_ISSUED_TOKEN_LIFETIME = 14 * 24 * 60 * 60;	// 14 days in seconds
	private static final long MAX_CLIENT_ISSUED_TOKEN_LIFETIME = 30 * 60; 			// 30 minutes in seconds
	private static final int DEFAULT_LEEWAY = 5 * 60; // 5 minutes in seconds

	private final Identity identity;
	private final UserRepository userRepository;
	private final long maxServerIssuedTokenLifetime; // seconds
	private final long maxClientIssuedTokenLifetime; // seconds
	private final int leeway; // seconds

	/**
	 * Interface for retrieving subject and associated entities.
	 */
	public interface UserRepository {
		/**
		 * Retrieves the subject (user or node) by ID.
		 * @param subject the subject ID
		 * @return a Future containing the subject object (ClientUser, FederatedNode, etc.) or null if not found
		 */
		Future<?> getSubject(Id subject);
		
		/**
		 * Retrieves the associated entity (device, service, etc.) by ID.
		 * @param subject the subject ID owning the associated entity
		 * @param associated the associated entity ID
		 * @return a Future containing the associated object or null if not found
		 */
		Future<?> getAssociated(Id subject, Id associated);

		/**
		 * Creates a {@code UserRepository} instance using the provided {@code ClientAuthenticator}.
		 * The returned repository facilitates access to user-related data and entities
		 * by leveraging the given authenticator for security purposes.
		 *
		 * @param authenticator the {@code ClientAuthenticator} instance used to validate client authentication
		 * @return a {@code UserRepository} implementation that utilizes the given {@code ClientAuthenticator}
		 */
		static UserRepository fromClientAuthenticator(ClientAuthenticator authenticator) {
			return new AuthenticatorUserRepo(authenticator);
		}
	}

	private static final class AuthenticatorUserRepo implements UserRepository {
		private final ClientAuthenticator authenticator;

		private AuthenticatorUserRepo(ClientAuthenticator authenticator) {
			this.authenticator = authenticator;
		}

		@Override
		public Future<ClientUser> getSubject(Id userId) {
			return Future.fromCompletionStage(authenticator.authenticateUser(userId))
					.map(valid -> valid ? new IdOnlyClientUser(userId) : null);
		}

		@Override
		public Future<ClientDevice> getAssociated(Id userId, Id deviceId) {
			return Future.fromCompletionStage(authenticator.authenticateDevice(userId, deviceId, null))
					.map(valid -> valid ? new IdOnlyClientDevice(deviceId, userId) : null);
		}

		private static final class IdOnlyClientUser implements ClientUser {
			private final Id id;

			private IdOnlyClientUser(Id id) {
				this.id = id;
			}

			@Override
			public Id getId() {
				return id;
			}

			@Override
			public boolean verifyPassphrase(String passphrase) {
				throw new UnsupportedOperationException();
			}

			@Override
			public String getName() {
				return null;
			}

			@Override
			public String getAvatar() {
				return null;
			}

			@Override
			public String getEmail() {
				return null;
			}

			@Override
			public String getBio() {
				return null;
			}

			@Override
			public long getCreated() {
				return 0;
			}

			@Override
			public long getUpdated() {
				return 0;
			}

			@Override
			public boolean isAnnounce() {
				return false;
			}

			@Override
			public long getLastAnnounced() {
				return 0;
			}

			@Override
			public String getPlanName() {
				return null;
			}
		}

		private static final class IdOnlyClientDevice implements ClientDevice {
			private final Id id;
			private final Id userId;

			private IdOnlyClientDevice(Id id, Id userId) {
				this.id = id;
				this.userId = userId;
			}
			@Override
			public Id getId() {
				return id;
			}
			@Override
			public Id getUserId() {
				return userId;
			}
			@Override
			public String getName() {
				return null;
			}

			@Override
			public String getApp() {
				return null;
			}

			@Override
			public long getCreated() {
				return 0;
			}

			@Override
			public long getUpdated() {
				return 0;
			}

			@Override
			public long getLastSeen() {
				return 0;
			}

			@Override
			public String getLastAddress() {
				return null;
			}
		}
	}

	private CompactWebTokenAuth(Identity identity, UserRepository userRepository,
								long maxServerIssuedTokenLifetime, long maxClientIssuedTokenLifetime, int leeway) {
		this.identity = identity;
		this.userRepository = userRepository;
		this.maxServerIssuedTokenLifetime = maxServerIssuedTokenLifetime;
		this.maxClientIssuedTokenLifetime = maxClientIssuedTokenLifetime;
		this.leeway = leeway;
	}

	/**
	 * Creates a new instance of CompactWebTokenAuth.
	 *
	 * @param identity the identity of the current server node (used for signing and verification)
	 * @param userRepository the repository to lookup token subjects and associated entities
	 * @param maxServerIssuedTokenLifetime maximum lifetime for tokens issued by this server (seconds)
	 * @param maxClientIssuedTokenLifetime maximum lifetime for tokens issued by clients (seconds)
	 * @param leeway allowed clock skew (seconds)
	 * @return the authenticator instance
	 */
	public static CompactWebTokenAuth create(Identity identity, UserRepository userRepository,
								 long maxServerIssuedTokenLifetime, long maxClientIssuedTokenLifetime, int leeway) {
		return new CompactWebTokenAuth(identity, userRepository,
				maxServerIssuedTokenLifetime, maxClientIssuedTokenLifetime, leeway);
	}

	/**
	 * Creates a new instance of CompactWebTokenAuth with the specified identity, user repository,
	 * and default configuration values for token lifetimes and leeway.
	 *
	 * @param identity the identity associated with the authentication process
	 * @param userRepository the repository used for managing user data
	 * @return a new instance of CompactWebTokenAuth
	 */
	public static CompactWebTokenAuth create(Identity identity, UserRepository userRepository) {
		return new CompactWebTokenAuth(identity, userRepository,
				MAX_SERVER_ISSUED_TOKEN_LIFETIME, MAX_CLIENT_ISSUED_TOKEN_LIFETIME, DEFAULT_LEEWAY);
	}

	/**
	 * Authenticates a user using the provided credentials.
	 * <p>
	 * Expected credentials type is {@link TokenCredentials}. The token is parsed, validated
	 * (signature, expiration, claims), and resolved against the {@link UserRepository}.
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

		final String token = authInfo.getToken();
		final int index = token.indexOf('.');
		if (index <= 0 || index >= token.length() - 1)
			return Future.failedFuture("Invalid authorization token: wrong format");

		final byte[] payload;
		final byte[] sig;
		final JsonObject claims;

		try {
			payload = B64decoder.decode(token.substring(0, index));
			sig = B64decoder.decode(token.substring(index + 1));
			claims = new JsonObject(Json.cborMapper().readValue(payload, Json.mapType()));
		} catch (IllegalArgumentException | IOException e) {
			return Future.failedFuture("Invalid authorization token: format error");
		}

		// check the timestamps and expiration first
		if (!claims.containsKey("exp") || claims.getLong("exp") == null)
			return Future.failedFuture("Invalid authorization token: missing expiration");
		long expiration = claims.getLong("exp", 0L);
		if (expiration <= 0)
			return Future.failedFuture("Invalid authorization token: invalid expiration");
		final long now = System.currentTimeMillis() / 1000;
		if (now - leeway >= expiration)
			return Future.failedFuture("Invalid authorization token: expired");

		if (claims.containsKey("iat")) {
			long iat = claims.getLong("iat", 0L);
			// issued at must be in the past
			if (iat > now + leeway)
				return Future.failedFuture("Invalid authorization token: invalid issue at");
		}

		if (claims.containsKey("nbf")) {
			Long nbf = claims.getLong("nbf", 0L);
			// not before must be after now
			if (nbf > now + leeway)
				return Future.failedFuture("Invalid authorization token: invalid not before");
		}

		final boolean isServerIssued;

		// determine the issuer, audience, subject and associated IDs
		final Id issuer;
		if (claims.containsKey("iss")) {
			byte[] value = claims.getBinary("iss");
			if (value == null || value.length != Id.BYTES)
				return Future.failedFuture("Invalid authorization token: invalid issuer");

			try {
				Id id = Id.of(value);
				if (id.equals(identity.getId())) {
					issuer = identity.getId();
					isServerIssued = true;
				} else {
					issuer = id;
					isServerIssued = false;
				}
			} catch (IllegalArgumentException e) {
				return Future.failedFuture("Invalid authorization token: invalid issuer");
			}
		} else {
			// default: super node issued token, if iss is omitted
			issuer = identity.getId();
			isServerIssued = true;
		}

		Id audience = null;
		if (claims.containsKey("aud")) {
			byte[] value = claims.getBinary("aud");
			if (value == null || value.length != Id.BYTES)
				return Future.failedFuture("Invalid authorization token: invalid audience");
			try {
				audience = Id.of(value);
			} catch (IllegalArgumentException e) {
				return Future.failedFuture("Invalid authorization token: invalid audience");
			}
		}

		Id subject;
		if (claims.containsKey("sub")) {
			byte[] value = claims.getBinary("sub");
			if (value == null || value.length != Id.BYTES)
				return Future.failedFuture("Invalid authorization token: invalid subject");
			try {
				subject = Id.of(value);
			} catch (IllegalArgumentException e) {
				return Future.failedFuture("Invalid authorization token: invalid subject");
			}
		} else {
			return Future.failedFuture("Invalid authorization token: missing subject");
		}

		final Id associated;
		if (claims.containsKey("asc")) {
			byte[] value = claims.getBinary("asc");
			if (value == null || value.length != Id.BYTES)
				return Future.failedFuture("Invalid authorization token: invalid associated");
			try {
				associated = Id.of(value);
			} catch (IllegalArgumentException e) {
				return Future.failedFuture("Invalid authorization token: invalid associated");
			}
		} else {
			associated = null;
		}

		// check issuer should be: super node or subject or associated
		// audience is mandatory if the token not issued by the super node
		if (!isServerIssued) {
			// TODO: remove
			/*/
			if (associated != null) {
				if (!issuer.equals(associated))
					return Future.failedFuture("Invalid authorization token: wrong issuer");
			} else {
				if (!issuer.equals(subject))
					return Future.failedFuture("Invalid authorization token: wrong issuer");
			}
			*/
			if (!issuer.equals(Objects.requireNonNullElse(associated, subject)))
				return Future.failedFuture("Invalid authorization token: wrong issuer");

			if (audience == null)
				return Future.failedFuture("Invalid authorization token: missing audience");
		}

		if (audience != null && !audience.equals(identity.getId()))
			return Future.failedFuture("Invalid authorization token: wrong audience");

		// check the expiration time is in the acceptable range
		if (isServerIssued) {
			if (expiration - now - leeway > maxServerIssuedTokenLifetime)
				return Future.failedFuture("Invalid authorization token: life time too long");
		} else {
			if (expiration - now - leeway > maxClientIssuedTokenLifetime)
				return Future.failedFuture("Invalid authorization token: life time too long");
		}

		// verify the signature
		if (!issuer.toSignatureKey().verify(payload, sig))
			return Future.failedFuture("Invalid authorization token: signature verification failed");

		final String scope = claims.containsKey("scp") ?  claims.getString("scp") : null;

		return userRepository.getSubject(subject).compose(s -> {
			if (s == null)
				return Future.failedFuture("Invalid authorization token: subject not exists");

			if (associated != null)
				return userRepository.getAssociated(subject, associated).compose(a -> {
					if (a == null)
						return Future.failedFuture("Invalid authorization token: associated not exists");

					return Future.succeededFuture(Pair.of(s, a));
				});
			else
				return Future.succeededFuture(Pair.of(s, null));
		}).map(client -> {
			JsonObject principal = new JsonObject();

			// Optimize: reduction of object instances
			if (client.a() instanceof ClientUser u) {
				principal.put("username", u.getId().toBase58String());
				principal.put("sub", u.getId());
				principal.put("user", u);
				principal.put("plan", u.getPlanName());
			} else if (client.a() instanceof FederatedNode n) {
				principal.put("username", n.getId().toBase58String());
				principal.put("sub", n.getId());
				principal.put("node", n);
			} else {
				principal.put("username", subject.toBase58String());
				principal.put("sub", subject);
				principal.put("subjectObject", client.a());
			}

			if (client.b() != null) {
				if (client.b() instanceof ClientDevice d) {
					principal.put("asc", d.getId());
					principal.put("device", d);
				} else if (client.b() instanceof ServiceInfo s) {
					principal.put("asc", s.getPeerId());
					principal.put("service", s);
				} else {
					principal.put("asc", associated);
					principal.put("associatedObject", client.b());
				}
			}

			if (scope != null)
				principal.put("scope", scope);

			// The origin unparsed token
			principal.put("access_token", token);

			JsonObject attributes = new JsonObject();

			attributes.put("jti", claims.getBinary("jti"));
			attributes.put("exp", expiration);
			if (claims.containsKey("iat"))
				attributes.put("iat", claims.getLong("iat"));
			if (claims.containsKey("nbf"))
				attributes.put("nbf", claims.getLong("nbf"));

			// the origin parse claims
			// attributes.put("accessToken", claims);

			return User.create(principal, attributes);
		});
	}

	/**
	 * Generates a new token with specific claims.
	 * 
	 * @param claims the map of claims to include in the token
	 * @return the generated token string
	 * @throws IllegalArgumentException if expiration is invalid
	 */
	public String generateToken(Map<String, Object> claims) {
		Map<String, Object> _claims;

		long now = System.currentTimeMillis() / 1000;
		if (claims.containsKey("exp") && claims.get("exp") != null) {
			long expiration = (Long) claims.get("exp");
			if (expiration <= 0 || expiration > now + maxServerIssuedTokenLifetime)
				throw new IllegalArgumentException("Invalid expiration");

			_claims = claims;
		} else {
			_claims = new LinkedHashMap<>(claims);
			_claims.put("exp", now + maxServerIssuedTokenLifetime);
		}

		final byte[] payload;
		try {
			payload = Json.cborMapper().writeValueAsBytes(_claims);
		} catch (IOException e) {
			throw new RuntimeException("INTERNAL ERROR: JSON serialization");
		}

		final byte[] sig = identity.sign(payload);

		return B64encoder.encodeToString(payload) + "." + B64encoder.encodeToString(sig);
	}

	/**
	 * Generates a new token for a subject and optional associated entity.
	 *
	 * @param subject the subject ID
	 * @param associated the associated entity ID (optional, can be null)
	 * @param scope the scope string (optional, can be null)
	 * @param expiration the expiration time in seconds (0 for default server lifetime)
	 * @return the generated token string
	 * @throws IllegalArgumentException if expiration is invalid
	 */
	public String generateToken(Id subject, Id associated, String scope, long expiration) {
		Objects.requireNonNull(subject);
		if (expiration < 0 || expiration > maxServerIssuedTokenLifetime)
			throw new IllegalArgumentException("Invalid expiration");

		if (expiration == 0)
			expiration = System.currentTimeMillis() / 1000 + maxServerIssuedTokenLifetime;

		Map<String, Object> claims = new LinkedHashMap<>(5);
		claims.put("jti", Random.randomBytes(24));
		claims.put("sub", subject.bytes());
		if (associated != null)
			claims.put("asc", associated.bytes());
		if (scope != null && !scope.isEmpty())
			claims.put("scp", scope);
		claims.put("exp", expiration);
		return generateToken(claims);
	}

	/**
	 * Generates a new token for a subject with standard lifetime.
	 *
	 * @param subject the subject ID
	 * @param associated the associated entity ID (optional)
	 * @param scope the scope string (optional)
	 * @return the generated token string
	 */
	public String generateToken(Id subject, Id associated, String scope) {
		return generateToken(subject, associated, scope, 0);
	}

	/**
	 * Generates a new token for a subject with standard lifetime and no scope.
	 *
	 * @param subject the subject ID
	 * @param associated the associated entity ID (optional)
	 * @return the generated token string
	 */
	public String generateToken(Id subject, Id associated) {
		return generateToken(subject, associated, null, 0);
	}

	/**
	 * Generates a new token for a subject with scope and standard lifetime.
	 *
	 * @param subject the subject ID
	 * @param scope the scope string
	 * @return the generated token string
	 */
	public String generateToken(Id subject, String scope) {
		return generateToken(subject, null, scope, 0);
	}

	/**
	 * Generates a new token for a subject with standard lifetime and no associated entity or scope.
	 *
	 * @param subject the subject ID
	 * @return the generated token string
	 */
	public String generateToken(Id subject) {
		return generateToken(subject, null, null, 0);
	}
}