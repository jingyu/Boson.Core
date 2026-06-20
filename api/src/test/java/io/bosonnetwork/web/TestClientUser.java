package io.bosonnetwork.web;

import io.bosonnetwork.Id;
import io.bosonnetwork.service.impl.PlainUser;

public class TestClientUser extends PlainUser {
	public TestClientUser(Id id, String name, String avatar, String email, String bio, boolean admin) {
		super(id, name, avatar, email, bio, admin);
	}

	public TestClientUser(Id id, String name, String avatar, String email, String bio) {
		super(id, name, avatar, email, bio);
	}
}