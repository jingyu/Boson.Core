package io.bosonnetwork.service;

import io.bosonnetwork.Id;

public interface FederatedNode {
	Id getId();

	String getHost();

	int getPort();

	String getApiEndpoint();

	String getSoftware();

	String getVersion();

	String getName();

	String getLogo();

	String getWebsite();

	String getContact();

	String getDescription();

	boolean isTrusted();

	int getReputation();

	long getCreated();

	long getUpdated();
}