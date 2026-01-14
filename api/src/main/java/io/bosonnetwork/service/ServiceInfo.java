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

import java.util.Map;

import io.bosonnetwork.Id;

/**
 * Interface containing information about a super node service instance.
 * <p>
 * This interface encapsulates details about where a service is running (host, port),
 * its identity (peer ID, node ID), and identifiers for the service itself.
 */
public interface ServiceInfo {
	/**
	 * Gets the unique peer identifier associated with the service.
	 *
	 * @return the peer {@link Id}
	 */
	Id getPeerId();

	/**
	 * Retrieves a unique fingerprint representing the service instance.
	 *
	 * @return a long value uniquely identifying the service instance
	 */
	long getFingerprint();

	/**
	 * Gets the unique identifier of the node hosting the service.
	 *
	 * @return the node {@link Id}
	 */
	Id getNodeId();

	/**
	 * Gets the endpoint URL or URI for accessing the service.
	 *
	 * @return the endpoint string, never be {@code null}
	 */
	String getEndpoint();

	/**
	 * Checks if the extra data is present.
	 *
	 * @return {@code true} if the extra data is present, {@code false} otherwise.
	 */
	boolean hasExtra();

	/**
	 * Gets the extra data.
	 *
	 * @return the extra data
	 */
	byte[] getExtraData();

	/**
	 * Gets the extra data as a map.
	 *
	 * @return the extra data map
	 */
	Map<String, Object> getExtra();

	/**
	 * Gets the unique identifier string for the specific service type.
	 *
	 * @return the service ID
	 */
	String getServiceId();

	/**
	 * Gets the human-readable name of the service.
	 *
	 * @return the service name
	 */
	String getServiceName();
}