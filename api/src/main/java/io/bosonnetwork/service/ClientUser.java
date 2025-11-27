package io.bosonnetwork.service;

import io.bosonnetwork.Id;

public interface ClientUser {
	Id getId();

	boolean verifyPassphrase(String passphrase);

	String getName();

	String getAvatar();

	String getEmail();

	String getBio();

	long getCreated();

	long getUpdated();

	boolean isAnnounce();

	long getLastAnnounced();

	String getPlanName();
}