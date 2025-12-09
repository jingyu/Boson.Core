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

import io.vertx.core.Vertx;

import io.bosonnetwork.Id;
import io.bosonnetwork.Node;

/**
 * The ServiceContext interface represents a context in which the services can access the
 * host Boson node and the service configurations.
 */
public interface ServiceContext {
	/**
	 * Get the Vert.x instance to be used by the current DHT node.
	 *
	 * @return the {@link Vertx} instance.
	 */
	Vertx getVertx();
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
	 * Gets the client authenticator instance.
	 *
	 * @return the {@link ClientAuthenticator} used for authenticating clients.
	 */
	ClientAuthenticator getClientAuthenticator();

	/**
	 * Gets the client authorizer instance.
	 *
	 * @return the {@link ClientAuthorizer} used for authorizing client requests.
	 */
	ClientAuthorizer getClientAuthorizer();

	/**
	 * Gets the federation authenticator instance.
	 *
	 * @return the {@link FederationAuthenticator} used for authenticating federation requests.
	 */
	FederationAuthenticator getFederationAuthenticator();

	/**
	 * Gets the federation instance.
	 *
	 * @return the {@link Federation} object.
	 */
	Federation getFederation();

	/**
	 * Gets the service configuration data.
	 *
	 * @return the configuration data in {@code Map} object.
	 */
	Map<String, Object> getConfiguration();

	/**
	 * Set the service runtime property
	 *
	 * @param key the property key.
	 * @param value the new value to be associated with the property name.
	 * @return the previous value associated with {@code key}.
	 */
	Object setProperty(Object key, Object value);

	/**
	 * Returns the value to which the specified key is mapped, or {@code null} if the service
	 * contains no property value for the key.
	 *
	 * @param key the property key.
	 * @param <T> the type of the property value.
	 * @return the value of the specified property, or
     *         {@code null} if the service contains no mapping for the property.
	 */
	<T> T getProperty(Object key);
}