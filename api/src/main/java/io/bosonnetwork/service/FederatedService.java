package io.bosonnetwork.service;

import io.bosonnetwork.Id;

public interface FederatedService {
	Id getPeerId();

	Id getNodeId();

	Id getOriginId();

	String getHost();

	int getPort();

	String getAlternativeEndpoint();

	String getServiceId();

	String getServiceName();

	String getEndpoint();

	long getCreated();

	long getUpdated();
}