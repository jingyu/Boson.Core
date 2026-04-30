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

import java.util.Map;
import java.util.Objects;

import io.bosonnetwork.Id;
import io.bosonnetwork.service.ServiceInfo;

/**
 * A plain implementation of the {@link ServiceInfo} interface that provides
 * basic information about a service instance, including peer ID, node ID,
 * and a timestamp of its creation.
 */
public class PlainServiceInfo implements ServiceInfo {
	private final Id peerId;
	private final long fingerprint;
	private final Id nodeId;
	private final String endpoint;
	private final String serviceId;
	private final String serviceName;

	PlainServiceInfo(Id peerId, long fingerprint, Id nodeId, String endpoint) {
		this(peerId, fingerprint, nodeId, endpoint, null, null);
	}

	PlainServiceInfo(Id peerId, long fingerprint, Id nodeId, String endpoint, String serviceId, String serviceName) {
		this.peerId = Objects.requireNonNull(peerId);
		this.fingerprint = fingerprint;
		this.nodeId = Objects.requireNonNull(nodeId);
		this.endpoint = Objects.requireNonNull(endpoint);
		this.serviceId = serviceId == null || serviceId.isEmpty() ? peerId.toString() : serviceId;
		this.serviceName = serviceName == null || serviceName.isEmpty() ? peerId.toAbbrBase58String() : serviceName;
	}

	@Override
	public Id getPeerId() {
		return peerId;
	}

	@Override
	public long getFingerprint() {
		return fingerprint;
	}

	@Override
	public Id getNodeId() {
		return nodeId;
	}

	@Override
	public String getEndpoint() {
		return endpoint;
	}

	@Override
	public boolean hasExtra() {
		return false;
	}

	@Override
	public byte[] getExtraData() {
		return null;
	}

	@Override
	public Map<String, Object> getExtra() {
		return Map.of();
	}

	@Override
	public String getServiceType() {
		return serviceId;
	}

	@Override
	public String getServiceName() {
		return serviceName;
	}
}