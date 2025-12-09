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

package io.bosonnetwork;

/**
 * Provides an interface for managing a blacklist of nodes, allowing hosts and IDs
 * to be banned and checked for their banned status.
 */
public interface NodeBlacklist {
	/**
	 * Checks if the specified host is banned.
	 *
	 * @param host The IP host or hostname to check.
	 * @return true if the host is banned, false otherwise.
	 */
	boolean isBanned(String host);

	/**
	 * Checks if the specified ID is banned.
	 *
	 * @param id The ID to check.
	 * @return true if the ID is banned, false otherwise.
	 */
	boolean isBanned(Id id);

	/**
	 * Checks if the specified host or ID is banned.
	 *
	 * @param id   The ID to check.
	 * @param host The IP host or hostname to check.
	 * @return true if the host or ID is banned, false otherwise.
	 */
	default boolean isBanned(Id id, String host) {
		return isBanned(id) || isBanned(host);
	}

	/**
	 * Adds a host to the blacklist.
	 *
	 * @param host The IP host or hostname to ban.
	 */
	void ban(String host);

	/**
	 * Adds an ID to the blacklist.
	 *
	 * @param id The ID to ban.
	 */
	void ban(Id id);
}