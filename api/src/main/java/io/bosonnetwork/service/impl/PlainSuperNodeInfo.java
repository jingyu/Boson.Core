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

import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.bosonnetwork.Id;
import io.bosonnetwork.service.SuperNodeInfo;

/**
 * A basic implementation of the {@link SuperNodeInfo} interface, representing
 * a minimal federated node within a network.
 * <p>
 * This class provides a foundational implementation of the required
 * {@link SuperNodeInfo} methods. It uses a provided identifier and records
 * the creation timestamp at the time of instantiation.
 */
public class PlainSuperNodeInfo implements SuperNodeInfo {
	private final Id id;
	private final String host;
	private final int port;
	private final String apiEndpoint;
	private final long ts;

	/**
	 * Creates a super node info with an API endpoint derived from the host and port.
	 *
	 * @param nodeId the super node id
	 * @param host   the host name or address
	 * @param port   the port (must be in 1-65535)
	 */
	protected PlainSuperNodeInfo(Id nodeId, String host, int port) {
		this(nodeId, host, port, null);
	}

	/**
	 * Creates a super node info with the given attributes.
	 *
	 * @param nodeId      the super node id
	 * @param host        the host name or address
	 * @param port        the port (must be in 1-65535)
	 * @param apiEndpoint the API endpoint; a {@code http://host:port} URL is used when null or empty
	 * @throws IllegalArgumentException if the port is out of range
	 */
	protected PlainSuperNodeInfo(Id nodeId, String host, int port, @Nullable String apiEndpoint) {
		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port: " + port);

		this.id = Objects.requireNonNull(nodeId);
		this.host = Objects.requireNonNull(host);
		this.port = port;
		// noinspection HttpUrlsUsage
		this.apiEndpoint = apiEndpoint == null || apiEndpoint.isEmpty() ? "http://" + host + ":" + port : apiEndpoint;
		this.ts = System.currentTimeMillis();
	}

	@Override
	public Id getId() {
		return id;
	}

	@Override
	public String getHost() {
		return host;
	}

	@Override
	public int getPort() {
		return port;
	}

	@Override
	public String getApiEndpoint() {
		return apiEndpoint;
	}

	@Override
	public Optional<String> getSoftware() {
		return Optional.empty();
	}

	@Override
	public Optional<String> getVersion() {
		return Optional.empty();
	}

	@Override
	public Optional<String> getName() {
		return Optional.empty();
	}

	@Override
	public Optional<String> getLogo() {
		return Optional.empty();
	}

	@Override
	public Optional<String> getWebsite() {
		return Optional.empty();
	}

	@Override
	public Optional<String> getContact() {
		return Optional.empty();
	}

	@Override
	public Optional<String> getDescription() {
		return Optional.empty();
	}

	@Override
	public boolean isFederated() {
		return true;
	}

	@Override
	public int getReputation() {
		return 1000;
	}

	@Override
	public long getCreatedAt() {
		return ts;
	}

	@Override
	public long getUpdatedAt() {
		return ts;
	}
}