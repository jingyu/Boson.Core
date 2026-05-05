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

import java.util.Objects;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.PasswordHash;
import io.bosonnetwork.service.ClientUser;

/**
 * Represents a plain user implementation of the {@code ClientUser} interface.
 * This class provides basic functionality and hardcoded/default values.
 * It uses an immutable structure with fields set at instantiation and does
 * not support updates beyond the initial state.
 */
public class PlainUser implements ClientUser {
	private final Id id;
	private final String name;
	private final String passphrase;
	private final long ts;

	PlainUser(Id id) {
		this(id, null, null);
	}

	PlainUser(Id id, String name, String passphrase) {
		this.id = Objects.requireNonNull(id);
		this.name = name == null || name.isEmpty() ? id.toAbbrBase58String() : name;
		this.passphrase = (passphrase != null && !passphrase.isEmpty()) ? passwordHash(passphrase) : null;
		this.ts = System.currentTimeMillis();
	}

	@Override
	public Id getId() {
		return id;
	}

	/**
	 * Verifies the provided passphrase.
	 * If no passphrase was set for this user, this method returns {@code true}
	 * for any input, effectively allowing anonymous/permissive access.
	 *
	 * @param passphrase the passphrase to verify
	 * @return {@code true} if the passphrase is valid or if no passphrase is set
	 */
	@Override
	public boolean verifyPassphrase(String passphrase) {
		return this.passphrase == null || passwordVerify(this.passphrase, passphrase);
	}

	private static String passwordHash(String password) {
		return PasswordHash.hashInteractive(password);
	}

	private static boolean passwordVerify(String hash, String password) {
		return PasswordHash.verify(hash, password);
	}

	@Override
	public String getName() {
		return name;
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
	public long getCreatedAt() {
		return ts;
	}

	@Override
	public long getUpdatedAt() {
		return ts;
	}

	@Override
	public String getPlanName() {
		return "Free";
	}
}