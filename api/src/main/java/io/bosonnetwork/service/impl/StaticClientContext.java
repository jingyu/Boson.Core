package io.bosonnetwork.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import io.vertx.core.Future;

import io.bosonnetwork.Id;
import io.bosonnetwork.Identity;
import io.bosonnetwork.service.ClientAuthenticator;
import io.bosonnetwork.service.ClientAuthorizer;
import io.bosonnetwork.service.ClientContext;
import io.bosonnetwork.service.ClientDevice;
import io.bosonnetwork.service.ClientUser;
import io.bosonnetwork.utils.Pair;
import io.bosonnetwork.vertx.VertxFuture;
import io.bosonnetwork.web.CompactWebTokenAuth;

/**
 * A static implementation of the {@link ClientContext} interface.
 * This class provides a mechanism to manage users and their associated devices in memory using
 * a predefined map, providing functionality to query, add, and remove users and devices statically.
 * <p>
 * This implementation does not persist data and is suited for scenarios where the state does not
 * need long-term storage or dynamic updates from external sources.
 */
public class StaticClientContext implements ClientContext {
	private final Identity nodeIdentity;
	private final Map<Id, Pair<ClientUser, List<ClientDevice>>> userDevicesRegistry;

	public StaticClientContext(Identity nodeIdentity, Map<Id, List<Id>> userDevicesMap) {
		this.nodeIdentity = nodeIdentity;
		this.userDevicesRegistry = new ConcurrentHashMap<>();

		if (userDevicesMap != null && !userDevicesMap.isEmpty()) {
			userDevicesMap.forEach((userId, deviceIds) -> {
				ClientUser user = new PlainUser(userId);
				List<ClientDevice> devices = (deviceIds == null || deviceIds.isEmpty()) ? List.of() :
						deviceIds.stream().map(id -> (ClientDevice)(new PlainDevice(id, userId))).toList();
				this.userDevicesRegistry.put(userId, Pair.of(user, devices));
			});
		}
	}

	public boolean addUser(Id userId) {
		userDevicesRegistry.computeIfAbsent(userId, k -> Pair.of(new PlainUser(userId), List.of()));
		return true;
	}

	private ClientUser _getUser(Id userId) {
		Pair<ClientUser, List<ClientDevice>> pair = userDevicesRegistry.get(userId);
		return pair == null ? null : pair.a();
	}

	private boolean _existsUser(Id userId) {
		return userDevicesRegistry.containsKey(userId);
	}

	private List<ClientDevice> _getDevices(Id userId) {
		Pair<ClientUser, List<ClientDevice>> pair = userDevicesRegistry.get(userId);
		return pair == null ? List.of() : pair.b();
	}

	public void removeUser(Id userId) {
		userDevicesRegistry.remove(userId);
	}

	public boolean addDevice(Id userId, Id deviceId) {
		ClientDevice existing = _getDevice(deviceId);
		if (existing != null)
			return existing.getUserId().equals(userId);

		userDevicesRegistry.compute(userId, (k, v) -> {
			if (v == null)
				return Pair.of(new PlainUser(userId), List.of(new PlainDevice(deviceId, userId)));

			List<ClientDevice> devices = new ArrayList<>(v.b());
			devices.add(new PlainDevice(deviceId, userId));
			return Pair.of(v.a(), List.copyOf(devices));
		});

		return true;
	}

	private ClientDevice _getDevice(Id deviceId) {
		return userDevicesRegistry.values().stream()
				.map(Pair::b)
				.flatMap(List::stream)
				.filter(d -> d.getId().equals(deviceId))
				.findFirst()
				.orElse(null);
	}

	private boolean _existsDevice(Id deviceId) {
		return _getDevice(deviceId) != null;
	}

	private ClientDevice _getDevice(Id userId, Id deviceId) {
		Pair<ClientUser, List<ClientDevice>> pair = userDevicesRegistry.get(userId);
		return pair == null ? null : pair.b().stream()
				.filter(d -> d.getId().equals(deviceId))
				.findFirst()
				.orElse(null);
	}

	private boolean _existsDevice(Id userId, Id deviceId) {
		return _getDevice(userId, deviceId) != null;
	}

	public void removeDevice(Id deviceId) {
		ClientDevice device = _getDevice(deviceId);
		if (device == null)
			return;

		userDevicesRegistry.computeIfPresent(device.getUserId(), (k, v) -> {
			if (v.b().isEmpty())
				return v;

			List<ClientDevice> devices = new ArrayList<>(v.b());
			devices.removeIf(d -> d.getId().equals(deviceId));
			return Pair.of(v.a(), List.copyOf(devices));
		});
	}

	@Override
	public CompletableFuture<ClientUser> getUser(Id userId) {
		return VertxFuture.succeededFuture(_getUser(userId));
	}

	@Override
	public CompletableFuture<Boolean> existsUser(Id userId) {
		return VertxFuture.succeededFuture(_existsUser(userId));
	}

	@Override
	public CompletableFuture<List<ClientDevice>> getDevices(Id userId) {
		return VertxFuture.succeededFuture(_getDevices(userId));
	}

	@Override
	public CompletableFuture<ClientDevice> getDevice(Id deviceId) {
		return VertxFuture.succeededFuture(_getDevice(deviceId));
	}

	@Override
	public CompletableFuture<Boolean> existsDevice(Id deviceId) {
		return VertxFuture.completedFuture(_existsDevice(deviceId));
	}

	@Override
	public CompletableFuture<Boolean> existsDevice(Id userId, Id deviceId) {
		return VertxFuture.completedFuture(_existsDevice(userId, deviceId));
	}

	@Override
	public ClientAuthenticator getAuthenticator() {
		return new ClientAuthenticator() {
			@Override
			public CompletableFuture<Boolean> authenticateUser(Id userId, byte[] nonce, byte[] signature) {
				if (!_existsUser(userId))
					return CompletableFuture.completedFuture(false);

				boolean isValid = nonce == null || signature == null || userId.toSignatureKey().verify(nonce, signature);
				return CompletableFuture.completedFuture(isValid);
			}

			@Override
			public CompletableFuture<Boolean> authenticateDevice(Id userId, Id deviceId, byte[] nonce, byte[] signature, String address) {
				if (!_existsDevice(userId, deviceId))
					return CompletableFuture.completedFuture(false);

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
				return Future.succeededFuture(_getUser(subject));
			}

			@Override
			public Future<ClientDevice> getAssociated(Id subject, Id associated) {
				return Future.succeededFuture(_getDevice(subject, associated));
			}
		});
	}
}