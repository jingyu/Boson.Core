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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import io.bosonnetwork.Id;

/**
 * Client manager interface (Read-only) for the Boson Super Node services.
 * <p>
 * This interface provides methods to query information about client users and their associated devices
 * registered within the network. It allows retrieving user details, checking for user existence,
 * and listing or verifying devices associated with users.
 */
public interface Clients {
	/**
	 * Retrieves the user information for a given user ID.
	 *
	 * @param userId the unique identifier of the user to retrieve
	 * @return a {@link CompletableFuture} that completes with the {@link ClientUser} object if found,
	 *         or completes with {@code null} if the user does not exist
	 */
	CompletableFuture<? extends ClientUser> getUser(Id userId);

	/**
	 * Checks if a user with the specified ID exists.
	 *
	 * @param userId the unique identifier of the user to check
	 * @return a {@link CompletableFuture} that completes with {@code true} if the user exists,
	 *         or {@code false} otherwise
	 */
	CompletableFuture<Boolean> existsUser(Id userId);

	/**
	 * Retrieves a list of all devices associated with a specific user.
	 *
	 * @param userId the unique identifier of the user whose devices are to be retrieved
	 * @return a {@link CompletableFuture} that completes with a list of {@link ClientDevice} objects belonging to the user
	 */
	CompletableFuture<List<? extends ClientDevice>> getDevices(Id userId);

	/**
	 * Retrieves the device information for a given device ID.
	 *
	 * @param deviceId the unique identifier of the device to retrieve
	 * @return a {@link CompletableFuture} that completes with the {@link ClientDevice} object if found,
	 *         or completes with {@code null} if the device does not exist
	 */
	CompletableFuture<? extends ClientDevice> getDevice(Id deviceId);

	/**
	 * Checks if a device with the specified ID exists.
	 *
	 * @param deviceId the unique identifier of the device to check
	 * @return a {@link CompletableFuture} that completes with {@code true} if the device exists,
	 *         or {@code false} otherwise
	 */
	CompletableFuture<Boolean> existsDevice(Id deviceId);

	/**
	 * Checks if a specific device exists and is associated with the specified user.
	 *
	 * @param userId   the unique identifier of the user
	 * @param deviceId the unique identifier of the device
	 * @return a {@link CompletableFuture} that completes with {@code true} if the device exists and belongs to the user,
	 *         or {@code false} otherwise
	 */
	CompletableFuture<Boolean> existsDevice(Id userId, Id deviceId);
}