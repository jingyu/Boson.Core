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

package io.bosonnetwork.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import io.bosonnetwork.utils.Variable;
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

	/**
	 * Constructs a new instance of {@code StaticClientContext}.
	 *
	 * @param nodeIdentity The {@link Identity} associated with this client context.
	 *                     This identity is used for cryptographic operations and
	 *                     represents the unique identifier of the node.
	 */
	public StaticClientContext(Identity nodeIdentity) {
		this.nodeIdentity = nodeIdentity;
		this.userDevicesRegistry = new ConcurrentHashMap<>();
	}

	/**
	 * Adds a new user to the user registry if they do not already exist.
	 *
	 * @param userId    The unique identifier of the user to be added. Must not be null.
	 * @param name      The name of the user to be added.
	 * @param passphrase The password or passphrase associated with the user.
	 * @return true if the user was successfully added to the registry, false if the user already exists.
	 * @throws NullPointerException if the provided userId is null.
	 */
	public boolean addUser(Id userId, String name, String passphrase) {
		Objects.requireNonNull(userId);
		if (existsUserSync(userId))
			return false;

		userDevicesRegistry.computeIfAbsent(userId, k -> Pair.of(new PlainUser(userId, name, passphrase), List.of()));
		return true;
	}

	/**
	 * Retrieves the {@code ClientUser} instance associated with the given unique user identifier.
	 * If the user does not exist in the registry, this method will return {@code null}.
	 *
	 * @param userId The unique identifier of the user to retrieve. Must not be {@code null}.
	 * @return The {@code ClientUser} instance associated with the given user identifier,
	 *         or {@code null} if the user is not found.
	 * @throws NullPointerException if {@code userId} is {@code null}.
	 */
	public ClientUser getUserSync(Id userId) {
		Objects.requireNonNull(userId);
		Pair<ClientUser, List<ClientDevice>> pair = userDevicesRegistry.get(userId);
		return pair == null ? null : pair.a();
	}

	/**
	 * Checks if a user with the specified unique identifier exists in the user registry.
	 *
	 * @param userId The unique identifier of the user to check. Must not be null.
	 * @return true if the user exists in the registry, false otherwise.
	 * @throws NullPointerException if the provided {@code userId} is null.
	 */
	public boolean existsUserSync(Id userId) {
		Objects.requireNonNull(userId);
		return userDevicesRegistry.containsKey(userId);
	}

	/**
	 * Removes a user from the user registry.
	 * If the specified user does not exist, the operation will have no effect.
	 *
	 * @param userId The unique identifier of the user to be removed. Must not be null.
	 * @return true if the user was successfully removed, false if the user did not exist.
	 * @throws NullPointerException if the provided userId is null.
	 */
	public boolean removeUser(Id userId) {
		Objects.requireNonNull(userId);
		return userDevicesRegistry.remove(userId) != null;
	}

	/**
	 * Adds a new device to the user registry for a specified user. If the device already exists
	 * globally, the addition will fail and return false. The user must already exist in the registry.
	 *
	 * @param userId The unique identifier of the user to which the device will be added. Must not be null.
	 * @param deviceId The global unique identifier of the device to be added. Must not be null.
	 * @param deviceName The name of the device to be added.
	 * @param app The application associated with the device.
	 * @return true if the device was successfully added, false if the device already exists.
	 * @throws NullPointerException if userId or deviceId is null.
	 * @throws IllegalArgumentException if the user does not exist in the registry.
	 * @throws IllegalStateException if the user exists with no valid registry state.
	 */
	public boolean addDevice(Id userId, Id deviceId, String deviceName, String app) {
		Objects.requireNonNull(userId);
		Objects.requireNonNull(deviceId);
		if (!existsUserSync(userId))
			throw new IllegalArgumentException("User does not exist");

		// device id should be global unique
		if (existsDeviceSync(deviceId))
			return false;

		userDevicesRegistry.compute(userId, (k, v) -> {
			if (v == null)
				throw new IllegalStateException("User does not exist");

			ClientDevice newDevice = new PlainDevice(deviceId, userId, deviceName, app);
			if (v.b().isEmpty()) {
				return Pair.of(v.a(), List.of(newDevice));
			} else {
				List<ClientDevice> devices = new ArrayList<>(v.b());
				devices.add(newDevice);
				return Pair.of(v.a(), List.copyOf(devices));
			}
		});

		return true;
	}

	/**
	 * Retrieves a {@code ClientDevice} instance with the specified unique device identifier.
	 * The device is searched across all registered user devices.
	 * If no matching device is found, this method returns {@code null}.
	 *
	 * @param deviceId The unique identifier of the device to retrieve. Must not be null.
	 * @return The {@code ClientDevice} instance matching the given device identifier,
	 *         or {@code null} if no such device is found.
	 * @throws NullPointerException if {@code deviceId} is null.
	 */
	private ClientDevice getDeviceSync(Id deviceId) {
		return userDevicesRegistry.values().stream()
				.map(Pair::b)
				.flatMap(List::stream)
				.filter(d -> d.getId().equals(deviceId))
				.findFirst()
				.orElse(null);
	}

	/**
	 * Checks whether a device with the specified unique identifier exists in the device registry.
	 *
	 * @param deviceId The unique identifier of the device to check. Must not be null.
	 * @return true if a device with the given identifier exists; false otherwise.
	 * @throws NullPointerException if the provided {@code deviceId} is null.
	 */
	private boolean existsDeviceSync(Id deviceId) {
		return getDeviceSync(deviceId) != null;
	}

	/**
	 * Retrieves a list of devices associated with the specified user identifier. If the user does not have
	 * any registered devices or if the user identifier is not found, an empty list is returned.
	 *
	 * @param userId The unique identifier of the user whose devices are to be retrieved. Must not be null.
	 * @return A list of {@code ClientDevice} instances associated with the specified user, or an empty list
	 *         if the user has no devices or the user does not exist.
	 * @throws NullPointerException if the provided {@code userId} is null.
	 */
	public List<ClientDevice> getDevicesSync(Id userId) {
		Objects.requireNonNull(userId);
		Pair<ClientUser, List<ClientDevice>> pair = userDevicesRegistry.get(userId);
		return pair == null ? List.of() : pair.b();
	}

	/**
	 * Retrieves the {@code ClientDevice} associated with the specified user and device identifiers.
	 * If the user or device does not exist in the registry, this method returns {@code null}.
	 *
	 * @param userId The unique identifier of the user whose device is to be retrieved. Must not be {@code null}.
	 * @param deviceId The unique identifier of the device to be retrieved. Must not be {@code null}.
	 * @return The {@code ClientDevice} instance matching the specified user and device identifiers,
	 *         or {@code null} if no matching device is found.
	 * @throws NullPointerException if {@code userId} or {@code deviceId} is {@code null}.
	 */
	public ClientDevice getDeviceSync(Id userId, Id deviceId) {
		Objects.requireNonNull(userId);
		Objects.requireNonNull(deviceId);
		Pair<ClientUser, List<ClientDevice>> pair = userDevicesRegistry.get(userId);
		return pair == null ? null : pair.b().stream()
				.filter(d -> d.getId().equals(deviceId))
				.findFirst()
				.orElse(null);
	}

	/**
	 * Checks whether a device with the specified unique device identifier exists for a given user.
	 * The method ensures that both the user ID and device ID are not null. It returns {@code true}
	 * if the device associated with the specified user exists, and {@code false} otherwise.
	 *
	 * @param userId   The unique identifier of the user whose device is to be checked. Must not be null.
	 * @param deviceId The unique identifier of the device to be checked. Must not be null.
	 * @return {@code true} if the device exists for the specified user; {@code false} otherwise.
	 * @throws NullPointerException If {@code userId} or {@code deviceId} is null.
	 */
	public boolean existsDeviceSync(Id userId, Id deviceId) {
		Objects.requireNonNull(userId);
		Objects.requireNonNull(deviceId);
		return getDeviceSync(userId, deviceId) != null;
	}

	/**
	 * Removes a device associated with a specific user from the device registry.
	 * If the specified device does not exist for the user, the operation will have no effect.
	 *
	 * @param userId   The unique identifier of the user whose device is to be removed. Must not be null.
	 * @param deviceId The unique identifier of the device to be removed. Must not be null.
	 * @return true if the device was successfully removed, false if the device did not exist.
	 * @throws NullPointerException if either userId or deviceId is null.
	 */
	public boolean removeDevice(Id userId, Id deviceId) {
		Objects.requireNonNull(userId);
		Objects.requireNonNull(deviceId);

		Variable<Boolean> removed = Variable.of(false);
		userDevicesRegistry.computeIfPresent(userId, (k, v) -> {
			if (v.b().isEmpty())
				return v;

			List<ClientDevice> devices = new ArrayList<>(v.b());
			boolean rm = devices.removeIf(d -> d.getId().equals(deviceId));
			if (rm) {
				removed.set(true);
				return Pair.of(v.a(), List.copyOf(devices));
			} else {
				// no change
				return v;
			}
		});

		return removed.get();
	}

	/**
	 * Clears all entries from the user device registry.
	 * This method removes all registered devices associated with users,
	 * effectively resetting the registry to an empty state.
	 */
	public void clear() {
		userDevicesRegistry.clear();
	}

	@Override
	public CompletableFuture<ClientUser> getUser(Id userId) {
		return VertxFuture.succeededFuture(getUserSync(userId));
	}

	@Override
	public CompletableFuture<Boolean> existsUser(Id userId) {
		return VertxFuture.succeededFuture(existsUserSync(userId));
	}

	@Override
	public CompletableFuture<List<ClientDevice>> getDevices(Id userId) {
		throw new UnsupportedOperationException("getDevices is not supported");
		// return VertxFuture.succeededFuture(_getDevices(userId));
	}

	@Override
	public CompletableFuture<ClientDevice> getDevice(Id deviceId) {
		throw new UnsupportedOperationException("getDevice is not supported");
		// return VertxFuture.succeededFuture(_getDevice(deviceId));
	}

	@Override
	public CompletableFuture<Boolean> existsDevice(Id deviceId) {
		throw new UnsupportedOperationException("existsDevice is not supported");
		//return VertxFuture.completedFuture(_existsDevice(deviceId));
	}

	@Override
	public CompletableFuture<Boolean> existsDevice(Id userId, Id deviceId) {
		return VertxFuture.completedFuture(existsDeviceSync(userId, deviceId));
	}

	@Override
	public ClientAuthenticator getAuthenticator() {
		return new ClientAuthenticator() {
			@Override
			public CompletableFuture<Boolean> authenticateUser(Id userId, byte[] nonce, byte[] signature) {
				if (!existsUserSync(userId))
					return CompletableFuture.completedFuture(false);

				boolean isValid = nonce == null || signature == null || userId.toSignatureKey().verify(nonce, signature);
				return CompletableFuture.completedFuture(isValid);
			}

			@Override
			public CompletableFuture<Boolean> authenticateDevice(Id userId, Id deviceId, byte[] nonce, byte[] signature, String address) {
				if (!existsDeviceSync(userId, deviceId))
					return CompletableFuture.completedFuture(false);

				boolean isValid = nonce == null || signature == null || deviceId.toSignatureKey().verify(nonce, signature);
				return CompletableFuture.completedFuture(isValid);
			}
		};
	}

	@Override
	public ClientAuthorizer getAuthorizer() {
		return (userId, deviceId, serviceType) -> VertxFuture.succeededFuture(Map.of());
	}

	@Override
	public CompactWebTokenAuth getWebTokenAuthenticator() {
		if (nodeIdentity == null)
			throw new IllegalStateException("Node identity is not set");

		return CompactWebTokenAuth.create(nodeIdentity, new CompactWebTokenAuth.UserRepository() {
			@Override
			public Future<ClientUser> getSubject(Id subject) {
				return Future.succeededFuture(getUserSync(subject));
			}

			@Override
			public Future<ClientDevice> getAssociated(Id subject, Id associated) {
				return Future.succeededFuture(getDeviceSync(subject, associated));
			}
		});
	}
}