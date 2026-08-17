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

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.bosonnetwork.Id;
import io.bosonnetwork.json.Json;

/**
 * A thread-safe file-based blacklist for managing banned hosts and IDs using a copy-on-write strategy.
 * Optimized for frequent reads with synchronized writes to ensure thread safety.
 *
 * <p>Reads go to a volatile immutable set and take no lock at all; a write builds a whole new set under a
 * monitor and publishes it. That trade is right here because bans are configured rarely and consulted per
 * packet, and it is what lets the read path stay a plain field access.</p>
 *
 * @see Blacklist
 */
public class FileBlacklist implements Blacklist {
	private volatile Set<String> hosts;
	private volatile Set<Id> ids;

	/**
	 * Constructs a FileBlacklist with the specified hosts and IDs.
	 *
	 * @param hosts List of IP hosts or hostnames to blacklist. Can be null or empty.
	 * @param ids List of IDs to blacklist. Can be null or empty.
	 */
	@JsonCreator
	protected FileBlacklist(@JsonProperty("hosts") List<String> hosts, @JsonProperty("ids") List<Id> ids) {
		this.hosts = hosts == null || hosts.isEmpty() ? Set.of() : Set.copyOf(hosts);
		this.ids = ids == null || ids.isEmpty() ? Set.of() : Set.copyOf(ids);
	}

	/**
	 * Checks if the specified host is banned.
	 *
	 * @param host The IP host or hostname to check.
	 * @return true if the host is banned, false otherwise.
	 */
	@Override
	public boolean isBanned(String host) {
		return hosts.contains(host);
	}

	/**
	 * Checks if the specified ID is banned.
	 *
	 * @param id The ID to check.
	 * @return true if the ID is banned, false otherwise.
	 */
	@Override
	public boolean isBanned(Id id) {
		return ids.contains(id);
	}

	/**
	 * Adds a host to the blacklist.
	 *
	 * @param host The IP host or hostname to ban.
	 */
	@Override
	public void ban(String host) {
		Objects.requireNonNull(host, "host");

		if (hosts.contains(host))
			return;

		synchronized (this) {
			Set<String> newHosts = new HashSet<>(hosts);
			newHosts.add(host);
			this.hosts = Collections.unmodifiableSet(newHosts);
		}
	}

	/**
	 * Adds an ID to the blacklist.
	 *
	 * @param id The ID to ban.
	 */
	@Override
	public void ban(Id id) {
		Objects.requireNonNull(id, "id");

		if (ids.contains(id))
			return;

		synchronized (this) {
			Set<Id> newIds = new HashSet<>(ids);
			newIds.add(id);
			this.ids = Collections.unmodifiableSet(newIds);
		}
	}

	/**
	 * Removes a host from the blacklist.
	 *
	 * @param host The IP host or hostname to unban.
	 */
	@Override
	public void unban(String host) {
		Objects.requireNonNull(host, "host");

		if (!hosts.contains(host))
			return;

		synchronized (this) {
			Set<String> newHosts = new HashSet<>(hosts);
			newHosts.remove(host);
			this.hosts = Collections.unmodifiableSet(newHosts);
		}
	}

	/**
	 * Removes an ID from the blacklist.
	 *
	 * @param id The ID to unban.
	 */
	@Override
	public void unban(Id id) {
		Objects.requireNonNull(id, "id");

		if (!ids.contains(id))
			return;

		synchronized (this) {
			Set<Id> newIds = new HashSet<>(ids);
			newIds.remove(id);
			this.ids = Collections.unmodifiableSet(newIds);
		}
	}

	/**
	 * Returns a hash code value for the object.
	 *
	 * @return a hash code value for this object
	 */
	@Override
	public int hashCode() {
		return Objects.hash(hosts, ids);
	}

	/**
	 * Compares the specified object with this object for equality.
	 *
	 * @param obj the object to compare with this object
	 * @return true if the specified object is equal to this object
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;

		if (obj instanceof FileBlacklist that)
			return Objects.equals(hosts, that.hosts) && Objects.equals(ids, that.ids);


		return false;
	}

	// Serialization only. The constructor takes lists because that is the shape the file holds; these hand
	// back the live sets, which is why nothing may mutate one in place.
	@JsonProperty("hosts")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private Set<String> getHosts() {
		return hosts;
	}

	@JsonProperty("ids")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private Set<Id> getIds() {
		return ids;
	}

	/**
	 * Persists this blacklist instance to the given path.
	 * <p>
	 * The chosen format follows the file extension: {@code .json} for JSON,
	 * anything else for YAML.
	 *
	 * @param file the destination path. It need not exist; if it does, it must be a regular file.
	 * @throws NullPointerException if {@code file} is {@code null}.
	 * @throws IllegalArgumentException if {@code file} exists and is not a regular file.
	 * @throws IOException              if an I/O error occurs while writing.
	 */
	public void save(Path file) throws IOException {
		Objects.requireNonNull(file, "file");

		if (Files.exists(file) && !Files.isRegularFile(file))
			throw new IllegalArgumentException("File `" + file + "` already exists and is not a regular file");

		ObjectMapper mapper = file.getFileName().endsWith(".json") ? Json.objectMapper() : Json.yamlMapper();
		try (OutputStream out = new FileOutputStream(file.toFile())) {
			// no need using NIO, simple file I/O is enough
			mapper.writeValue(out, this);
		}
	}

	/**
	 * Reads a {@code Blacklist} definition from disk.
	 * <p>
	 * The file format is chosen automatically based on the extension:
	 * files ending in {@code .json} are parsed as JSON, all others as YAML.
	 *
	 * @param file the path to the JSON/YAML file to load.
	 * @return the new loaded blacklist.
	 * @throws NullPointerException if {@code file} is {@code null}.
	 * @throws IllegalArgumentException if {@code file} does not exist or is not a regular file.
	 * @throws IOException              if an I/O error occurs while reading or parsing.
	 */
	public static FileBlacklist load(Path file) throws IOException {
		Objects.requireNonNull(file, "file");

		if (Files.notExists(file) || !Files.isRegularFile(file))
			throw new IllegalArgumentException("File `" + file + "` does not exist or is not a regular file");

		ObjectMapper mapper = file.getFileName().endsWith(".json") ? Json.objectMapper() : Json.yamlMapper();
		try (InputStream in = new FileInputStream(file.toFile())) {
			// no need using NIO, simple file I/O is enough
			return mapper.readValue(in, FileBlacklist.class);
		}
	}
}