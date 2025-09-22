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
import io.bosonnetwork.access.AccessManager;

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
	Path getDataPath();

	/**
	 * Gets the {@link io.bosonnetwork.access.AccessManager} instance that provided by
	 * the host Boson node.
	 *
	 * @return the AccessManager interface.
	 */
	AccessManager getAccessManager();

	/**
	 * Gets the service configuration data.
	 *
	 * @return the configuration data in {@code Map} object.
	 */
	Map<String, Object> getConfiguration();

	/**
	 * Set the service runtime property
	 *
	 * @param name the property name.
	 * @param value the new value to be associated with the property name.
	 * @return the previous value associated with {@code name}.
	 */
	Object setProperty(String name, Object value);

	/**
	 * Returns the value to which the specified name, or {@code null} if the service
	 * contains no property value for the name.
	 *
	 * @param name the property name.
	 * @return the value of the specified property, or
     *         {@code null} if the service contains no mapping for the property.
	 */
	Object getProperty(String name);
}