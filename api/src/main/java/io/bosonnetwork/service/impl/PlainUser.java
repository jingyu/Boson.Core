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
import java.util.Optional;

import org.jspecify.annotations.Nullable;

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
	private static final String DEFAULT_PASSPHRASE = "secret";
	private final Id id;
	private String passphrase;
	private final String name;
	private final @Nullable String avatar;
	private final @Nullable String email;
	private final @Nullable String bio;
	private final boolean isAdmin;
	private final long ts;

	/**
	 * Creates a non-admin user with no profile attributes.
	 *
	 * @param id the user id
	 */
	protected PlainUser(Id id) {
		this(id, null, null, null, null, false);
	}

	/**
	 * Creates a non-admin user with the given name.
	 *
	 * @param id   the user id
	 * @param name the user name; an abbreviated id is used when null or empty
	 */
	protected PlainUser(Id id, @Nullable String name) {
		this(id, name, null, null, null, false);
	}

	/**
	 * Creates a user with no profile attributes and the given admin flag.
	 *
	 * @param id      the user id
	 * @param isAdmin whether the user is an administrator
	 */
	protected PlainUser(Id id, boolean isAdmin) {
		this(id, null, null, null, null, isAdmin);
	}

	/**
	 * Creates a non-admin user with the given profile attributes.
	 *
	 * @param id     the user id
	 * @param name   the user name; an abbreviated id is used when null or empty
	 * @param avatar the avatar identifier or URL, or null if unset
	 * @param email  the email address, or null if unset
	 * @param bio    the biography, or null if unset
	 */
	protected PlainUser(Id id, @Nullable String name, @Nullable String avatar, @Nullable String email, @Nullable String bio) {
		this(id, name, avatar, email, bio, false);
	}

	/**
	 * Creates a user with the given profile attributes and admin flag.
	 *
	 * @param id      the user id
	 * @param name    the user name; an abbreviated id is used when null or empty
	 * @param avatar  the avatar identifier or URL, or null if unset
	 * @param email   the email address, or null if unset
	 * @param bio     the biography, or null if unset
	 * @param isAdmin whether the user is an administrator
	 */
	protected PlainUser(Id id, @Nullable String name, @Nullable String avatar, @Nullable String email, @Nullable String bio, boolean isAdmin) {
		this.id = Objects.requireNonNull(id);
		this.passphrase = passwordHash(DEFAULT_PASSPHRASE);
		this.name = name == null || name.isEmpty() ? id.toAbbrBase58String() : name;
		this.avatar = avatar;
		this.email = email;
		this.bio = bio;
		this.isAdmin = isAdmin;
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
		return passwordVerify(this.passphrase, passphrase);
	}

	private static String passwordHash(String password) {
		return PasswordHash.hashInteractive(password);
	}

	private static boolean passwordVerify(String hash, String password) {
		return PasswordHash.verify(hash, password);
	}

	@Override
	public Optional<String> getName() {
		return Optional.of(name);
	}

	@Override
	public Optional<String> getAvatar() {
		return Optional.ofNullable(avatar);
	}

	@Override
	public Optional<String> getEmail() {
		return Optional.ofNullable(email);
	}

	@Override
	public Optional<String> getBio() {
		return Optional.ofNullable(bio);
	}

	@Override
	public boolean isAdmin() {
		return isAdmin;
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