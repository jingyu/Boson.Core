/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

package io.bosonnetwork.kademlia.security;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import io.bosonnetwork.Id;

/**
 * An operator's list of hosts and node ids this node refuses to talk to.
 *
 * <p>Membership is stated, never inferred: entries arrive from configuration or from an explicit
 * {@link #ban(Id)} call, and nothing here observes traffic or expires an entry. That is the whole
 * difference between this and {@link SuspiciousNodeDetector}, which decides for itself, on evidence, and
 * always for a bounded time. A host named here stays named until somebody removes it.</p>
 *
 * <p>The unit is also different, and deliberately so: a literal host string as configured, not the
 * {@link SourceKey} the detector and the throttle count in. An operator banning an address means that
 * address, and widening it to a /64 would ban neighbours they did not name.</p>
 */
public interface Blacklist {
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

	/**
	 * Removes a host from the blacklist.
	 *
	 * @param host The IP host or hostname to unban.
	 */
	void unban(String host);

	/**
	 * Removes an ID from the blacklist.
	 *
	 * @param id The ID to unban.
	 */
	void unban(Id id);

	/**
	 * Creates and returns an empty {@code Blacklist} with no banned hosts or IDs.
	 *
	 * @return an empty blacklist.
	 */
	static Blacklist empty() {
		return new EmptyBlacklist();
	}

	/**
	 * Creates an empty, mutable blacklist that can be populated through {@link #ban(Id)} and saved to disk.
	 *
	 * @return a new empty blacklist.
	 */
	static Blacklist create() {
		return new FileBlacklist(null, null);
	}

	/**
	 * Loads a blacklist from a file, parsed as JSON if the name ends in {@code .json} and as YAML otherwise.
	 *
	 * @param file the path to load from.
	 * @return the loaded blacklist.
	 * @throws NullPointerException if {@code file} is {@code null}.
	 * @throws IllegalArgumentException if {@code file} does not exist or is not a regular file.
	 * @throws IOException if an I/O error occurs while reading or parsing.
	 */
	static Blacklist load(String file) throws IOException {
		Objects.requireNonNull(file, "path");
		return FileBlacklist.load(Path.of(file));
	}

	/**
	 * Loads a blacklist from a file.
	 *
	 * @param file the file to load from.
	 * @return the loaded blacklist.
	 * @throws NullPointerException if {@code file} is {@code null}.
	 * @throws IllegalArgumentException if {@code file} does not exist or is not a regular file.
	 * @throws IOException if an I/O error occurs while reading or parsing.
	 * @see #load(String)
	 */
	static Blacklist load(File file) throws IOException {
		Objects.requireNonNull(file, "path");
		return FileBlacklist.load(file.toPath());
	}

	/**
	 * Loads a blacklist from a file.
	 *
	 * @param path the path to load from.
	 * @return the loaded blacklist.
	 * @throws NullPointerException if {@code path} is {@code null}.
	 * @throws IllegalArgumentException if {@code path} does not exist or is not a regular file.
	 * @throws IOException if an I/O error occurs while reading or parsing.
	 * @see #load(String)
	 */
	static Blacklist load(Path path) throws IOException {
		Objects.requireNonNull(path, "path");
		return FileBlacklist.load(path);
	}
}