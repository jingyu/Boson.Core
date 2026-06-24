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

import java.util.List;
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
	private final List<String> addresses;
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
		Objects.requireNonNull(nodeId);
		if (Objects.requireNonNull(host).isEmpty())
			throw new IllegalArgumentException("Invalid host");
		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port");

		this.id = Objects.requireNonNull(nodeId);
		this.addresses = List.of(host + ":" + port);
		// noinspection HttpUrlsUsage
		this.apiEndpoint = "http://" + this.addresses.get(0);
		this.ts = System.currentTimeMillis();
	}

	/**
	 * Constructs a new PlainSuperNodeInfo instance with the specified node ID, a list of addresses, and an optional API endpoint.
	 *
	 * @param nodeId      the unique identifier for the super node, must not be null.
	 * @param addresses   the list of addresses associated with the super node, must not be empty.
	 * @param apiEndpoint the optional API endpoint for the super node; if null or empty, it defaults to using the first address in the list with "http://" as the prefix.
	 * @throws IllegalArgumentException if the addresses list is empty.
	 * @throws NullPointerException     if the nodeId is null.
	 */
	protected PlainSuperNodeInfo(Id nodeId, List<String> addresses, @Nullable String apiEndpoint) {
		Objects.requireNonNull(nodeId);
		if (Objects.requireNonNull(addresses).isEmpty())
			throw new IllegalArgumentException("Empty addresses");

		this.id = Objects.requireNonNull(nodeId);
		this.addresses = List.copyOf(addresses);
		// noinspection HttpUrlsUsage
		this.apiEndpoint = apiEndpoint == null || apiEndpoint.isEmpty() ? "http://" + addresses.get(0) : apiEndpoint;
		this.ts = System.currentTimeMillis();
	}

	@Override
	public Id getId() {
		return id;
	}

	@Override
	public List<String> getAddresses() {
		return addresses;
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