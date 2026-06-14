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

import java.nio.file.Path;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.Node;

/**
 * The ServiceContext interface represents a context in which the services can access the
 * host Boson node and the service configurations.
 */
public interface ServiceContext {
	/**
	 * Unwraps the context to provide the underlying infrastructure instance.
	 *
	 * @param clazz the type of the infrastructure component (e.g. io.vertx.core.Vertx)
	 * @return the component instance, or null if not available or not supported
	 * @param <T> the type parameter
	 */
	<T> @Nullable T unwrap(Class<T> clazz);

	/**
	 * Gets the host Boson node object.
	 *
	 * @return the host Boson node.
	 */
	Node getNode();

	/**
	 * Shortcut API to get the host Boson node id.
	 *
	 * @return the host Boson node id.
	 */
	Id getNodeId();

	/**
	 * Gets the persistence path for the current service.
	 *
	 * @return the {@code Path} object.
	 */
	Path getDataDir();

	/**
	 * Retrieves the {@link ClientContext} instance, which provides access to client management functionalities such as
	 * querying user information, checking for user or device existence, and retrieving associated devices.
	 *
	 * @return the {@link ClientContext} instance used for accessing client-related operations within the service context.
	 */
	ClientContext getClientContext();

	/**
	 * Retrieves the instance of {@link FederationContext} associated with the service.
	 *
	 * @return the {@link FederationContext} providing read-only access to federation-related
	 *         functionalities, such as querying other nodes and their services.
	 */
	FederationContext getFederationContext();

	/**
	 * Gets the service configuration data.
	 *
	 * @return the configuration data in {@code Map} object.
	 */
	Map<String, Object> getConfiguration();

	/**
	 * Sets a service runtime property. Properties form an in-memory key/value side-channel that
	 * outlives a single request but does not persist across restarts. Implementations should be
	 * thread-safe.
	 *
	 * @param key the property name (non-null)
	 * @param value the new value, or {@code null} to remove the mapping
	 * @return the previous value associated with {@code key}, or {@code null} if there was none
	 */
	@Nullable Object setProperty(String key, @Nullable Object value);

	/**
	 * Returns the value associated with the specified property key, or {@code null} if no mapping
	 * exists. The caller is responsible for the cast; this is a convenience for typed read.
	 *
	 * @param key the property name (non-null)
	 * @param <T> the expected type of the property value
	 * @return the value of the specified property, or {@code null} if no mapping exists
	 */
	<T> @Nullable T getProperty(String key);
}