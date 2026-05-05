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

import io.bosonnetwork.Id;
import io.bosonnetwork.service.FederatedNode;

/**
 * A basic implementation of the {@link FederatedNode} interface, representing
 * a minimal federated node within a network.
 *
 * This class provides a foundational implementation of the required
 * {@link FederatedNode} methods. It uses a provided identifier and records
 * the creation timestamp at the time of instantiation.
 */
public class PlainFederatedNode implements FederatedNode {
	private final Id id;
	private final String host;
	private final int port;
	private final String apiEndpoint;
	private final long ts;

	PlainFederatedNode(Id nodeId, String host, int port) {
		this(nodeId, host, port, null);
	}

	PlainFederatedNode(Id nodeId, String host, int port, String apiEndpoint) {
		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port");

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
	public String getSoftware() {
		return null;
	}

	@Override
	public String getVersion() {
		return null;
	}

	@Override
	public String getName() {
		return null;
	}

	@Override
	public String getLogo() {
		return null;
	}

	@Override
	public String getWebsite() {
		return null;
	}

	@Override
	public String getContact() {
		return null;
	}

	@Override
	public String getDescription() {
		return null;
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