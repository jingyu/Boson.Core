package io.bosonnetwork.service.impl;

import java.util.Map;

import io.bosonnetwork.Id;
import io.bosonnetwork.service.ServiceInfo;

/**
 * A plain implementation of the {@link ServiceInfo} interface that provides
 * basic information about a service instance, including peer ID, node ID,
 * and a timestamp of its creation.
 */
public class PlainServiceInfo implements ServiceInfo {
	private final Id peerId;
	private final Id nodeId;
	private final long ts;

	PlainServiceInfo(Id peerId, Id nodeId) {
		this.peerId = peerId;
		this.nodeId = nodeId;
		this.ts = System.currentTimeMillis();
	}

	@Override
	public Id getPeerId() {
		return peerId;
	}

	@Override
	public long getFingerprint() {
		return 0;
	}

	@Override
	public Id getNodeId() {
		return nodeId;
	}

	@Override
	public String getEndpoint() {
		return null;
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
	public String getServiceId() {
		return null;
	}

	@Override
	public String getServiceName() {
		return null;
	}
}