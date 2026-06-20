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
import java.util.Optional;

import io.bosonnetwork.Id;

/**
 * Interface containing information about a super node service instance.
 * <p>
 * This interface encapsulates details about where a service is running (host, port),
 * its identity (peer ID, node ID), and identifiers for the service itself.
 */
public non-sealed interface ServiceInfo extends Principal {
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
	 * Gets the extra data as a raw byte array.
	 * <p>
	 * Implementations MUST return a defensive copy - mutating the returned array MUST NOT affect
	 * the internal state of this {@code ServiceInfo}.
	 *
	 * @return an {@link Optional} with a defensive copy of the extra data, or empty if no extra data is present
	 */
	Optional<byte[]> getExtraData();

	/**
	 * Gets the extra data parsed as a map.
	 * <p>
	 * The returned map is immutable; modification attempts result in {@link UnsupportedOperationException}.
	 *
	 * @return an immutable map of extra data, or an empty map if no extra data is present
	 */
	Map<String, Object> getExtra();

	/**
	 * Gets the unique type identifier string for the specific service type.
	 *
	 * @return the service type
	 */
	String getServiceType();

	/**
	 * Gets the human-readable name of the service.
	 *
	 * @return the service name
	 */
	String getServiceName();

	/**
	 * Checks whether federation functionality is enabled for the service.
	 *
	 * @return {@code true} if federation is enabled, {@code false} otherwise
	 */
	boolean isFederationEnabled();
}