package io.bosonnetwork.service.impl;

import io.bosonnetwork.Id;
import io.bosonnetwork.service.ClientUser;

/**
 * Represents a plain user implementation of the {@code ClientUser} interface.
 * This class provides basic functionality and hardcoded/default values for fields
 * such as name, avatar, email, bio, announcement status, and subscription plan.
 * It utilizes an immutable structure with fields set at instantiation and does
 * not support updates beyond the initial state.
 */
public class PlainUser implements ClientUser {
	private final Id id;
	private final long ts;

	PlainUser(Id id) {
		this.id = id;
		this.ts = System.currentTimeMillis();
	}

	@Override
	public Id getId() {
		return id;
	}

	@Override
	public boolean verifyPassphrase(String passphrase) {
		return true;
	}

	@Override
	public String getName() {
		return "PlainUser";
	}

	@Override
	public String getAvatar() {
		return null;
	}

	@Override
	public String getEmail() {
		return null;
	}

	@Override
	public String getBio() {
		return null;
	}

	@Override
	public long getCreated() {
		return ts;
	}

	@Override
	public long getUpdated() {
		return ts;
	}

	@Override
	public boolean isAnnounce() {
		return false;
	}

	@Override
	public long getLastAnnounced() {
		return 0;
	}

	@Override
	public String getPlanName() {
		return "Free";
	}
}