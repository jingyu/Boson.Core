package io.bosonnetwork.service.impl;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import io.vertx.core.Future;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.service.ClientAuthenticator;
import io.bosonnetwork.service.ClientAuthorizer;
import io.bosonnetwork.service.ClientContext;
import io.bosonnetwork.service.ClientDevice;
import io.bosonnetwork.service.ClientUser;
import io.bosonnetwork.vertx.VertxFuture;
import io.bosonnetwork.web.CompactWebTokenAuth;

/**
 * Implementation of the {@link ClientContext} interface that allows all operations without restrictions.
 * This context is permissive, always returning successful results for authentication, authorization,
 * and existence checks. It is intended for use cases where no stringent access control or validation
 * is required.
 * <p>
 * This implementation supports the following behaviors:
 * - All user and device existence checks return {@code true}.
 * - All authentication requests are permitted as valid.
 * - Authorization requests provide empty grants.
 * - Retrieval of users or devices always succeeds with placeholder objects or empty lists, as appropriate.
 * - Provides a permissive {@link CompactWebTokenAuth} instance for token handling.
 * <p>
 * Note: This class should be used cautiously, as it bypasses all security and validation checks.
 */
public class AllowAllClientContext implements ClientContext {
	private final Identity nodeIdentity;

	public AllowAllClientContext(Identity nodeIdentity) {
		this.nodeIdentity = nodeIdentity;
	}

	@Override
	public CompletableFuture<ClientUser> getUser(Id userId) {
		return VertxFuture.succeededFuture(new PlainUser(userId));
	}

	@Override
	public CompletableFuture<Boolean> existsUser(Id userId) {
		return VertxFuture.succeededFuture(true);
	}

	@Override
	public CompletableFuture<List<ClientDevice>> getDevices(Id userId) {
		return VertxFuture.succeededFuture(List.of());
	}

	@Override
	public CompletableFuture<ClientDevice> getDevice(Id deviceId) {
		return VertxFuture.succeededFuture();
	}

	@Override
	public CompletableFuture<Boolean> existsDevice(Id deviceId) {
		return VertxFuture.succeededFuture(true);
	}

	@Override
	public CompletableFuture<Boolean> existsDevice(Id userId, Id deviceId) {
		return VertxFuture.succeededFuture(true);
	}

	@Override
	public ClientAuthenticator getAuthenticator() {
		return new ClientAuthenticator() {
			@Override
			public CompletableFuture<Boolean> authenticateUser(Id userId, byte[] nonce, byte[] signature) {
				boolean isValid = nonce == null || signature == null || userId.toSignatureKey().verify(nonce, signature);
				return CompletableFuture.completedFuture(isValid);
			}

			@Override
			public CompletableFuture<Boolean> authenticateDevice(Id userId, Id deviceId, byte[] nonce, byte[] signature, String address) {
				boolean isValid = nonce == null || signature == null || deviceId.toSignatureKey().verify(nonce, signature);
				return CompletableFuture.completedFuture(isValid);
			}
		};
	}

	@Override
	public ClientAuthorizer getAuthorizer() {
		return (userId, deviceId, serviceId) -> VertxFuture.succeededFuture(Map.of());
	}

	@Override
	public CompactWebTokenAuth getWebTokenAuthenticator() {
		if (nodeIdentity == null)
			throw new IllegalStateException("Node identity is not set");

		return CompactWebTokenAuth.create(nodeIdentity, new CompactWebTokenAuth.UserRepository() {
			@Override
			public Future<ClientUser> getSubject(Id subject) {
				return Future.succeededFuture(new PlainUser(subject));
			}

			@Override
			public Future<?> getAssociated(Id subject, Id associated) {
				return Future.succeededFuture(new PlainDevice(associated, subject));
			}
		});
	}
}