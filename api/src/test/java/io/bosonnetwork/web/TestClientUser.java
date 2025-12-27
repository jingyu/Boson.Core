package io.bosonnetwork.web;

import io.bosonnetwork.Id;
import io.bosonnetwork.service.ClientUser;

public class TestClientUser implements ClientUser {
	private final Id id;
	private final String name;
	private final String avatar;
	private final String email;
	private final String bio;
	private final long created;
	private final long updated;

	public TestClientUser(Id id, String name, String avatar, String email, String bio) {
		this.id = id;
		this.name = name;
		this.avatar = avatar;
		this.email = email;
		this.bio = bio;
		this.created = System.currentTimeMillis();
		this.updated = created;
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
		return name;
	}

	@Override
	public String getAvatar() {
		return avatar;
	}

	@Override
	public String getEmail() {
		return email;
	}

	@Override
	public String getBio() {
		return bio;
	}

	@Override
	public long getCreated() {
		return created;
	}

	@Override
	public long getUpdated() {
		return updated;
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