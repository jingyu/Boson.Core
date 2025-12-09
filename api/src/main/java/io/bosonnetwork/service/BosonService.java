/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

import java.util.concurrent.CompletableFuture;

import io.bosonnetwork.Id;

/**
 * Interface BosonService is the basic abstraction for the extensible service on top of
 * the Boson super node. This interface describes the basic information about the service
 * itself and the life-cycle management methods. All super node services should implement
 * this interface.
 */
public interface BosonService {
	/**
	 * The unique identifier for the service.
	 *
	 * @return the unique identifier string.
	 */
	String getId();

	/**
	 * The user-friendly service name.
	 *
	 * @return the service name.
	 */
	String getName();

	/**
	 * Retrieves the peer identifier associated with the service.
	 *
	 * @return an {@link Id} object representing the peer identifier.
	 */
	Id getPeerId();

	/**
	 * Retrieves the host associated with the service.
	 *
	 * @return a string representing the host's address.
	 */
	String getHost();

	/**
	 * Retrieves the port number associated with the service.
	 *
	 * @return an integer representing the port number.
	 */
	int getPort();

	/**
	 * Retrieves an alternative endpoint for the service.
	 *
	 * @return a string representing the alternative endpoint.
	 */
	default String getAlternativeEndpoint() {
		return null;
	}

	/**
	 * Checks whether the federation feature is enabled for the service.
	 *
	 * @return true if federation is enabled, false otherwise.
	 */
	default boolean isFederationEnabled() {
		return false;
	}

	/**
	 * Get the running status
	 *
	 * @return true if the service is running, false otherwise.
	 */
	boolean isRunning();

	/**
	 * Initialize the service instance with the {@link ServiceContext} object.
	 *
	 * @param context the {@link ServiceContext} object to initialize the service.
	 * @throws BosonServiceException if the error occurred during the initialization.
	 */
	void init(ServiceContext context) throws BosonServiceException;

	/**
	 * Start the service in asynchronized way.
	 *
	 * @return the {@code CompletableFuture} of the starting action.
	 */
	CompletableFuture<Void> start();

	/**
	 * Stop the service in asynchronized way.
	 *
	 * @return the {@code CompletableFuture} of the stopping action.
	 */
	CompletableFuture<Void> stop();
}