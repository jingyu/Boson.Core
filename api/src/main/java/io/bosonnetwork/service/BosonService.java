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
import io.bosonnetwork.PeerInfo;

/**
 * Interface BosonService is the basic abstraction for extensible services on top of
 * the Boson super node. This interface defines the core service attributes and
 * lifecycle management methods. All services intended to run on a Boson super node
 * MUST implement this interface.
 */
public interface BosonService {
	/**
	 * Retrieves the peer identifier associated with the service.
	 *
	 * @return an {@link Id} object representing the peer identifier.
	 */
	Id getId();

	/**
	 * The unique identifier for the service type.
	 *
	 * @return the unique type identifier string.
	 */
	String getType();

	/**
	 * The user-friendly service name.
	 *
	 * @return the service name.
	 */
	String getName();

	/**
	 * Returns the <em>local</em> bind host of the service (the address the service listens on
	 * locally - e.g. {@code 0.0.0.0}, {@code 127.0.0.1}, or a configured interface). This is
	 * deployment configuration and is generally different from the publicly-reachable host
	 * advertised in {@link #getPeerInfo()}.
	 *
	 * @return the local bind host.
	 */
	String getHost();

	/**
	 * Returns the <em>local</em> bind port of the service. Deployment configuration; usually
	 * different from any port advertised through {@link #getPeerInfo()}.
	 *
	 * @return the local bind port.
	 */
	int getPort();

	/**
	 * Returns the <em>public</em> endpoint URL that remote clients should use to reach this
	 * service. The endpoint encoded in {@link #getPeerInfo()} is normally the same value and is
	 * what gets gossiped/published over the network.
	 *
	 * @return the public service endpoint URL.
	 */
	String getEndpoint();

	/**
	 * Returns the published peer information for this service - the public-facing identity, the
	 * advertised endpoint, and any associated metadata. This is what federation peers see when
	 * looking up the service.
	 *
	 * @return the {@link PeerInfo} for this service.
	 */
	PeerInfo getPeerInfo();

	/**
	 * Checks whether the federation feature is enabled for the service.
	 *
	 * @return true if federation is enabled, false otherwise.
	 */
	default boolean isFederationEnabled() {
		return false;
	}

	/**
	 * Returns whether the service is currently running.
	 * <p>
	 * The contract is: {@code true} only between a successful completion of {@link #start()} and
	 * the moment {@link #stop()} begins executing. While a {@code start()} or {@code stop()}
	 * future is still pending, this returns {@code false}.
	 *
	 * @return true if the service is running, false otherwise.
	 */
	boolean isRunning();

	/**
	 * Initialize the service instance with the {@link ServiceContext} object.
	 * <p>
	 * <strong>Must not block.</strong> {@code init} is called on the Vert.x event loop (following
	 * the standard Vert.x verticle init pattern); any I/O (config reads, schema setup, network
	 * calls) must be deferred to {@link #start()} where it can be performed asynchronously.
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