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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.bosonnetwork.Id;
import io.bosonnetwork.utils.Json;

/**
 * A thread-safe blacklist for managing banned hosts and IDs using a copy-on-write strategy.
 * Optimized for frequent reads with synchronized writes to ensure thread safety.
 */
public class Blacklist {
	private volatile Map<String, Boolean> hosts;
	private volatile Map<Id, Boolean> ids;

	/**
	 * Constructs a Blacklist with the specified hosts and IDs.
	 *
	 * @param hosts List of IP hosts or hostnames to blacklist. Can be null or empty.
	 * @param ids List of IDs to blacklist. Can be null or empty.
	 */
	@JsonCreator
	protected Blacklist(@JsonProperty("hosts") List<String> hosts, @JsonProperty("ids") List<Id> ids) {
		if (hosts == null || hosts.isEmpty())
			this.hosts = Collections.emptyMap();
		else
			this.hosts = Collections.unmodifiableMap(hosts.stream().collect(Collectors.toMap(host -> host, host -> Boolean.TRUE)));

		if (ids == null || ids.isEmpty())
			this.ids = Collections.emptyMap();
		else
			this.ids = Collections.unmodifiableMap(ids.stream().collect(Collectors.toMap(id -> id, id -> Boolean.TRUE)));
	}

	/**
	 * Checks if the specified host is banned.
	 *
	 * @param host The IP host or hostname to check.
	 * @return true if the host is banned, false otherwise.
	 */
	public boolean isBanned(String host) {
		return hosts.containsKey(host);
	}

	/**
	 * Checks if the specified ID is banned.
	 *
	 * @param id The ID to check.
	 * @return true if the ID is banned, false otherwise.
	 */
	public boolean isBanned(Id id) {
		return ids.containsKey(id);
	}

	/**
	 * Adds an host to the blacklist.
	 *
	 * @param host The IP host or hostname to ban.
	 */
	public void ban(String host) {
		Objects.requireNonNull(host, "host");

		if (hosts.containsKey(host))
			return;

		synchronized (this) {
			Map<String, Boolean> newHosts = new HashMap<>(hosts);
			newHosts.put(host, Boolean.TRUE);
			this.hosts = Collections.unmodifiableMap(newHosts);
		}
	}

	/**
	 * Adds an ID to the blacklist.
	 *
	 * @param id The ID to ban.
	 */
	public void ban(Id id) {
		Objects.requireNonNull(id, "id");

		if (ids.containsKey(id))
			return;

		synchronized (this) {
			Map<Id, Boolean> newIds = new HashMap<>(ids);
			newIds.put(id, Boolean.TRUE);
			this.ids = Collections.unmodifiableMap(newIds);
		}
	}

	/**
	 * Removes an host from the blacklist.
	 *
	 * @param host The IP host or hostname to unban.
	 */
	public void unban(String host) {
		Objects.requireNonNull(host, "host");

		if (!hosts.containsKey(host))
			return;

		synchronized (this) {
			Map<String, Boolean> newHosts = new HashMap<>(hosts);
			newHosts.remove(host);
			this.hosts = Collections.unmodifiableMap(newHosts);
		}
	}

	/**
	 * Removes an ID from the blacklist.
	 *
	 * @param id The ID to unban.
	 */
	public void unban(Id id) {
		Objects.requireNonNull(id, "id");

		if (!ids.containsKey(id))
			return;

		synchronized (this) {
			Map<Id, Boolean> newIds = new HashMap<>(ids);
			newIds.remove(id);
			this.ids = Collections.unmodifiableMap(newIds);
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

		if (obj instanceof Blacklist that)
			return Objects.equals(hosts, that.hosts) && Objects.equals(ids, that.ids);


		return false;
	}

	@JsonProperty("hosts")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private Set<String> getHosts() {
		return hosts.keySet();
	}

	@JsonProperty("ids")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private Set<Id> getIds() {
		return ids.keySet();
	};

	/**
	 * Persists this blacklist instance to the given path.
	 * <p/>
	 * The chosen format follows the file extension: {@code .json} for JSON,
	 * anything else for YAML.
	 *
	 * @param file the destination path. If the file exists it must be a regular file.
	 * @throws NullPointerException if {@code file} is {@code null}.
	 * @throws IllegalArgumentException if {@code file} does not exist or is not a regular file.
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
	 * Creates and returns an empty {@code Blacklist} with no banned hosts or IDs.
	 *
	 * @return an empty blacklist.
	 */
	public static Blacklist empty() {
		return new Blacklist(null, null);
	}

	/**
	 * Reads a {@code Blacklist} definition from disk.
	 * <p/>
	 * The file format is chosen automatically based on the extension:
	 * files ending in {@code .json} are parsed as JSON, all others as YAML.
	 *
	 * @param file the path to the JSON/YAML file to load.
	 * @return the new loaded blacklist.
	 * @throws NullPointerException if {@code file} is {@code null}.
	 * @throws IllegalArgumentException if {@code file} does not exist or is not a regular file.
	 * @throws IOException              if an I/O error occurs while reading or parsing.
	 */
	public static Blacklist load(Path file) throws IOException {
		Objects.requireNonNull(file, "file");

		if (Files.notExists(file) || !Files.isRegularFile(file))
			throw new IllegalArgumentException("File `" + file + "` does not exist or is not a regular file");

		ObjectMapper mapper = file.getFileName().endsWith(".json") ? Json.objectMapper() : Json.yamlMapper();
		try (InputStream in = new FileInputStream(file.toFile())) {
			// no need using NIO, simple file I/O is enough
			return mapper.readValue(in, Blacklist.class);
		}
	}
}