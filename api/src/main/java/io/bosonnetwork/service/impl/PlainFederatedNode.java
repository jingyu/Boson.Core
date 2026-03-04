package io.bosonnetwork.service.impl;

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
	private final long ts;

	PlainFederatedNode(Id nodeId) {
		this.id = nodeId;
		this.ts = System.currentTimeMillis();
	}

	@Override
	public Id getId() {
		return id;
	}

	@Override
	public String getHost() {
		return null;
	}

	@Override
	public int getPort() {
		return 0;
	}

	@Override
	public String getApiEndpoint() {
		return null;
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
	public boolean isTrusted() {
		return true;
	}

	@Override
	public int getReputation() {
		return 1000;
	}

	@Override
	public long getCreated() {
		return ts;
	}

	@Override
	public long getUpdated() {
		return ts;
	}
}