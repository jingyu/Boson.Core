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

package io.bosonnetwork.service;

import io.bosonnetwork.Id;

/**
 * Interface representing a registered user on the Boson super node.
 * <p>
 * This interface provides access to user profile information such as identification,
 * display name, contact details, and account status.
 */
public interface ClientUser {
	/**
	 * Gets the unique identifier of the user.
	 *
	 * @return the user {@link Id}
	 */
	Id getId();

	/**
	 * Verifies if the provided passphrase matches the user's passphrase.
	 *
	 * @param passphrase the passphrase to verify
	 * @return {@code true} if the passphrase is valid, {@code false} otherwise
	 */
	boolean verifyPassphrase(String passphrase);

	/**
	 * Gets the display name of the user.
	 *
	 * @return the user's name
	 */
	String getName();

	/**
	 * Gets the avatar identifier or URL of the user.
	 *
	 * @return the avatar string
	 */
	String getAvatar();

	/**
	 * Gets the email address associated with the user.
	 *
	 * @return the user's email address
	 */
	String getEmail();

	/**
	 * Gets the biography or description of the user.
	 *
	 * @return the user's bio
	 */
	String getBio();

	/**
	 * Gets the timestamp when the user account was created.
	 *
	 * @return the creation timestamp in milliseconds
	 */
	long getCreated();

	/**
	 * Gets the timestamp when the user profile was last updated.
	 *
	 * @return the last update timestamp in milliseconds
	 */
	long getUpdated();

	/**
	 * Checks if the user presence is currently announced to the network.
	 *
	 * @return {@code true} if the user is announced, {@code false} otherwise
	 */
	boolean isAnnounce();

	/**
	 * Gets the timestamp of the last announcement of the user.
	 *
	 * @return the last announcement timestamp in milliseconds
	 */
	long getLastAnnounced();

	/**
	 * Gets the name of the subscription plan associated with the user.
	 *
	 * @return the plan name
	 */
	String getPlanName();
}