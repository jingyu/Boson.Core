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

package io.bosonnetwork;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.Vertx;

import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.utils.Base58;
import io.bosonnetwork.utils.Json;

/**
 * Default configuration implementation for the {@link NodeConfiguration} interface.
 */
@JsonPropertyOrder({"host4", "host6", "port", "privateKey", "dataPath", "storageURL", "bootstraps",
		"spamThrottling", "suspiciousNodeDetector", "developerMode", "metrics"})
public class DefaultNodeConfiguration implements NodeConfiguration {
	/**
	 * The default port for the DHT node, chosen from the IANA unassigned range (38866-39062).
	 * See: https://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xhtml
	 */
	private static final int DEFAULT_DHT_PORT = 39001;

	/**
	 * Vert.x instance used for the node's asynchronous operations.
	 * May be null if not set.
	 */
	private Vertx vertx;

	/**
	 * IPv4 address string for the DHT node. If null or empty, disables DHT on IPv4.
	 */
	@JsonProperty("host4")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	String host4;

	/**
	 * IPv6 address string for the DHT node. If null or empty, disables DHT on IPv6.
	 */
	@JsonProperty("host6")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	String host6;

	/**
	 * The port number for the DHT node.
	 */
	@JsonProperty("port")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	int port;

	/**
	 * The node's private key, encoded in Base58.
	 */
	@JsonProperty("privateKey")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	String privateKey;

	/**
	 * Path to the directory for persistent DHT data storage. If null, disables persistence.
	 */
	private Path dataPath;

	/**
	 * Optional external storage URL for the node.
	 */
	@JsonProperty("storageURL")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private String storageURL;

	/**
	 * Set of bootstrap nodes for joining the DHT network.
	 */
	@JsonProperty("bootstraps")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private final Set<NodeInfo> bootstraps;

	/**
	 * Whether spam throttling is enabled for this node.
	 */
	@JsonProperty("spamThrottling")
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	private boolean enableSpamThrottling;

	/**
	 * Whether suspicious node detection is enabled for this node.
	 */
	@JsonProperty("suspiciousNodeDetector")
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	private boolean enableSuspiciousNodeDetector;

	/**
	 * Whether developer mode is enabled for this node.
	 */
	@JsonProperty("developerMode")
	@JsonInclude(JsonInclude.Include.NON_DEFAULT)
	private boolean enableDeveloperMode;

	/**
	 * Whether metrics collection is enabled for this node.
	 */
	@JsonProperty("metrics")
	private boolean enableMetrics;

	/**
	 * Constructs a new DefaultNodeConfiguration with default settings.
	 * The default port is {@link #DEFAULT_DHT_PORT}, spam throttling and suspicious node detector are enabled,
	 * developer mode and metrics are disabled, and no bootstraps are set.
	 */
	private DefaultNodeConfiguration() {
		this.bootstraps = new HashSet<>();
		this.enableSpamThrottling = true;
		this.enableSuspiciousNodeDetector = true;
		this.enableDeveloperMode = false;
		this.enableMetrics = false;
		this.port = DEFAULT_DHT_PORT;
	}

	/**
	 * {@inheritDoc}
	 * @return the Vert.x instance associated with this configuration, or null if not set.
	 */
	@Override
	public Vertx vertx() {
		return vertx;
	}

	/**
	 * {@inheritDoc}
	 * @return the IPv4 address string for the DHT node, or null if disabled.
	 */
	@Override
	public String host4() {
		return host4;
	}

	/**
	 * {@inheritDoc}
	 * @return the IPv6 address string for the DHT node, or null if disabled.
	 */
	@Override
	public String host6() {
		return host6;
	}

	/**
	 * {@inheritDoc}
	 * @return the port number for the DHT node.
	 */
	@Override
	public int port() {
		return port;
	}

	/**
	 * {@inheritDoc}
	 * @return the Base58-encoded private key string.
	 */
	@Override
	public String privateKey() {
		return privateKey;
	}

	/**
	 * {@inheritDoc}
	 * @return the path to the persistent data directory, or null if persistence is disabled.
	 */
	@Override
	public Path dataPath() {
		return dataPath;
	}

	/**
	 * For Jackson serialization: gets the string representation of the data path.
	 * @return the string path, or null if not set.
	 */
	@JsonProperty("dataPath")
	@JsonInclude(JsonInclude.Include.NON_EMPTY)
	private String getDataPath() {
		return dataPath != null ? dataPath.toString() : null;
	}

	/**
	 * For Jackson deserialization: sets the data path from a string.
	 * @param dataPath the string path to set (may be null).
	 */
	@JsonProperty("dataPath")
	private void setDataPath(String dataPath) {
		this.dataPath = normalizePath(dataPath != null ? Path.of(dataPath) : null);
	}

	/**
	 * {@inheritDoc}
	 * @return the external storage URL, or null if not set.
	 */
	@Override
	public String storageURL() {
		return storageURL;
	}

	/**
	 * {@inheritDoc}
	 * @return the collection of bootstrap nodes for this configuration.
	 */
	@Override
	public Collection<NodeInfo> bootstrapNodes() {
		return bootstraps;
	}

	/**
	 * {@inheritDoc}
	 * @return true if spam throttling is enabled.
	 */
	@Override
	public boolean enableSpamThrottling() {
		return enableSpamThrottling;
	}

	/**
	 * {@inheritDoc}
	 * @return true if suspicious node detection is enabled.
	 */
	@Override
	public boolean enableSuspiciousNodeDetector() {
		return enableSuspiciousNodeDetector;
	}

	/**
	 * {@inheritDoc}
	 * @return true if developer mode is enabled.
	 */
	@Override
	public boolean enableDeveloperMode() {
		return enableDeveloperMode;
	}

	/**
	 * {@inheritDoc}
	 * @return true if metrics collection is enabled.
	 */
	@Override
	public boolean enableMetrics() {
		return enableMetrics;
	}

	/**
	 * Builder helper class to create a {@link NodeConfiguration} object.
	 * <p>
	 * The Builder provides a fluent API for configuring and constructing {@link DefaultNodeConfiguration}
	 * instances. It supports setting network addresses, ports, keys, persistent storage, bootstrap nodes,
	 * feature toggles, and loading/saving configurations from/to files.
	 */
	public static class Builder {
		private DefaultNodeConfiguration config;

		/**
		 * Constructs a new Builder with default settings.
		 */
		protected Builder() {
			reset();
		}

		/**
		 * Set the Vert.x instance to be used by the node.
		 * @param vertx the Vert.x instance (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if vertx is null
		 */
		public Builder vertx(Vertx vertx) {
			Objects.requireNonNull(vertx, "vertx");
			config.vertx = vertx;
			return this;
		}

		/**
		 * Automatically detects and sets the first available unicast IPv4 address for the node.
		 * @return this Builder for chaining
		 * @throws IllegalStateException if no suitable IPv4 address is found
		 */
		public Builder autoHost4() {
			InetAddress addr = AddressUtils.getAllAddresses()
					.filter(Inet4Address.class::isInstance)
					.filter(AddressUtils::isAnyUnicast)
					.distinct()
					.findFirst()
					.orElse(null);
			if (addr == null)
				throw new IllegalStateException("No available IPv4 address");

			config.host4 = addr.getHostAddress();
			return this;
		}

		/**
		 * Automatically detects and sets the first available unicast IPv6 address for the node.
		 * @return this Builder for chaining
		 * @throws IllegalStateException if no suitable IPv6 address is found
		 */
		public Builder autoHost6() {
			InetAddress addr = AddressUtils.getAllAddresses()
					.filter(Inet6Address.class::isInstance)
					.filter(AddressUtils::isAnyUnicast)
					.distinct()
					.findFirst()
					.orElse(null);
			if (addr == null)
				throw new IllegalStateException("No available IPv6 address");

			config.host6 = addr.getHostAddress();
			return this;
		}

		/**
		 * Automatically detects and sets both IPv4 and IPv6 addresses for the node.
		 * @return this Builder for chaining
		 * @throws IllegalStateException if neither IPv4 nor IPv6 addresses are found
		 */
		public Builder autoHosts() {
			InetAddress addr4 = AddressUtils.getAllAddresses()
					.filter(Inet4Address.class::isInstance)
					.filter(AddressUtils::isAnyUnicast)
					.distinct()
					.findFirst()
					.orElse(null);

			InetAddress addr6 = AddressUtils.getAllAddresses()
					.filter(Inet6Address.class::isInstance)
					.filter(AddressUtils::isAnyUnicast)
					.distinct()
					.findFirst()
					.orElse(null);

			if (addr4 == null && addr6 == null)
				throw new IllegalStateException("No available IPv4/6 address");

			if (addr4 != null)
				config.host4 = addr4.getHostAddress();

			if (addr6 != null)
				config.host6 = addr6.getHostAddress();

			return this;
		}

		/**
		 * Set the IPv4 address for the DHT node.
		 * @param host the string host name or IPv4 address (must not be null)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if the host is not a valid IPv4 address
		 * @throws NullPointerException if host is null
		 */
		public Builder host4(String host) {
			Objects.requireNonNull(host, "host");

			try {
				return address4(InetAddress.getByName(host));
			} catch (UnknownHostException e) {
				throw new IllegalArgumentException("Invalid host name or address: " + host, e);
			}
		}

		/**
		 * Set the IPv4 address for the DHT node.
		 * @param addr the IPv4 InetAddress (must not be null)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if addr is not a unicast IPv4 address
		 * @throws NullPointerException if addr is null
		 */
		public Builder address4(InetAddress addr) {
			Objects.requireNonNull(addr, "addr");
			if (!AddressUtils.isAnyUnicast(addr))
				throw new IllegalArgumentException("Not any unicast address");

			if (addr instanceof Inet4Address)
				config.host4 = addr.getHostAddress();
			else
				throw new IllegalArgumentException("Invalid IPv4 address: " + addr);

			return this;
		}

		/**
		 * Set the IPv6 address for the DHT node.
		 * @param host the string host name or IPv6 address (must not be null)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if the host is not a valid IPv6 address
		 * @throws NullPointerException if host is null
		 */
		public Builder host6(String host) {
			Objects.requireNonNull(host, "host");

			try {
				return address6(InetAddress.getByName(host));
			} catch (IOException | IllegalArgumentException e) {
				throw new IllegalArgumentException("Invalid host name or address:: " + host, e);
			}
		}

		/**
		 * Set the IPv6 address for the DHT node.
		 * @param addr the IPv6 InetAddress (must not be null)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if addr is not a unicast IPv6 address
		 * @throws NullPointerException if addr is null
		 */
		public Builder address6(InetAddress addr) {
			Objects.requireNonNull(addr, "addr");
			if (!AddressUtils.isAnyUnicast(addr))
				throw new IllegalArgumentException("Not any unicast address");

			if (addr instanceof Inet6Address)
				config.host6 = addr.getHostAddress();
			else
				throw new IllegalArgumentException("Invalid IPv6 address: " + addr);

			return this;
		}

		/**
		 * Set the DHT listen port. IPv4 and IPv6 networks will use the same port.
		 * @param port the port to listen (must be 1-65535)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if port is not in the valid range
		 */
		public Builder port(int port) {
			if (port <= 0 || port > 65535)
				throw new IllegalArgumentException("Invalid port: " + port);

			config.port = port;
			return this;
		}

		/**
		 * Generates a new random private key for the node.
		 * @return this Builder for chaining
		 */
		public Builder generatePrivateKey() {
			config.privateKey = Base58.encode(Signature.KeyPair.random().privateKey().bytes());
			return this;
		}

		/**
		 * Set the node's private key from a raw byte array.
		 * @param privateKey the private key bytes (must be 64 bytes)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if the key is not 64 bytes
		 */
		public Builder privateKey(byte[] privateKey) {
			if (privateKey == null || privateKey.length != 64)
				throw new IllegalArgumentException("Invalid private key");

			config.privateKey = Base58.encode(privateKey);
			return this;
		}

		/**
		 * Set the node's private key from a Base58-encoded string.
		 * @param privateKey the Base58-encoded private key string (must not be null)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if the key is not 64 bytes when decoded
		 * @throws NullPointerException if privateKey is null
		 */
		public Builder privateKey(String privateKey) {
			Objects.requireNonNull(privateKey, "privateKey");

			byte[] key = Base58.decode(privateKey);
			if (key.length != 64)
				throw new IllegalArgumentException("Invalid private key");

			config.privateKey = privateKey;
			return this;
		}

		/**
		 * Checks if a private key has been set in this builder.
		 * @return true if a private key is set, false otherwise
		 */
		public boolean hasPrivateKey() {
			return config.privateKey != null;
		}

		/**
		 * Set the storage path for DHT persistent data using a string path.
		 * @param path the string path (may be null to disable persistence)
		 * @return this Builder for chaining
		 */
		public Builder dataPath(String path) {
			return dataPath(path != null ? Path.of(path) : null);
		}

		/**
		 * Set the storage path for DHT persistent data using a File object.
		 * @param path the File pointing to the storage directory (may be null to disable persistence)
		 * @return this Builder for chaining
		 */
		public Builder dataPath(File path) {
			dataPath(path != null ? path.toPath() : null);
			return this;
		}

		/**
		 * Set the storage path for DHT persistent data using a Path.
		 * @param path the Path to the storage directory (may be null to disable persistence)
		 * @return this Builder for chaining
		 */
		public Builder dataPath(Path path) {
			config.dataPath = normalizePath(path);
			return this;
		}

		/**
		 * Checks if a data path has been set.
		 * @return true if a data path is set, false otherwise
		 */
		public boolean hasDataPath() {
			return config.dataPath != null;
		}

		/**
		 * Gets the current data path set in the builder.
		 * @return the Path to the storage directory, or null if not set
		 */
		public Path dataPath() {
			return config.dataPath;
		}

		/**
		 * Set the external storage URL for the node.
		 * @param storageURL the storage URL (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if storageURL is null
		 */
		public Builder storageURL(String storageURL) {
			Objects.requireNonNull(storageURL, "storageURL");
			config.storageURL = storageURL;
			return this;
		}

		/**
		 * Add a new bootstrap node to the configuration.
		 * @param id the Id of the bootstrap node
		 * @param addr the string address of the bootstrap node
		 * @param port the port of the bootstrap node
		 * @return this Builder for chaining
		 */
		public Builder addBootstrap(String id, String addr, int port) {
			NodeInfo node = new NodeInfo(Id.of(id), addr, port);
			config.bootstraps.add(node);
			return this;
		}

		/**
		 * Add a new bootstrap node to the configuration.
		 * @param id the Id of the bootstrap node
		 * @param addr the InetAddress of the bootstrap node
		 * @param port the port of the bootstrap node
		 * @return this Builder for chaining
		 */
		public Builder addBootstrap(Id id, InetAddress addr, int port) {
			NodeInfo node = new NodeInfo(id, addr, port);
			config.bootstraps.add(node);
			return this;
		}

		/**
		 * Add a new bootstrap node to the configuration.
		 * @param id the Id of the bootstrap node
		 * @param addr the InetSocketAddress of the bootstrap node
		 * @return this Builder for chaining
		 */
		public Builder addBootstrap(Id id, InetSocketAddress addr) {
			NodeInfo node = new NodeInfo(id, addr);
			config.bootstraps.add(node);
			return this;
		}

		/**
		 * Add a new bootstrap node to the configuration.
		 * @param node the NodeInfo of the bootstrap node (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if node is null
		 */
		public Builder addBootstrap(NodeInfo node) {
			Objects.requireNonNull(node, "node");
			config.bootstraps.add(node);
			return this;
		}

		/**
		 * Add multiple bootstrap nodes to the configuration.
		 * @param nodes the collection of NodeInfo bootstrap nodes (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if nodes is null
		 */
		public Builder addBootstrap(Collection<NodeInfo> nodes) {
			Objects.requireNonNull(nodes, "nodes");
			config.bootstraps.addAll(nodes);
			return this;
		}

		/**
		 * Enables spam throttling for the node.
		 * @return this Builder for chaining
		 */
		public Builder enableSpamThrottling() {
			config.enableSpamThrottling = true;
			return this;
		}

		/**
		 * Disables spam throttling for the node.
		 * @return this Builder for chaining
		 */
		public Builder disableSpamThrottling() {
			config.enableSpamThrottling = false;
			return this;
		}

		/**
		 * Enables suspicious node detection for the node.
		 * @return this Builder for chaining
		 */
		public Builder enableSuspiciousNodeDetector() {
			config.enableSuspiciousNodeDetector = true;
			return this;
		}

		/**
		 * Disables suspicious node detection for the node.
		 * @return this Builder for chaining
		 */
		public Builder disableSuspiciousNodeDetector() {
			config.enableSuspiciousNodeDetector = false;
			return this;
		}

		/**
		 * Enables developer mode for the node.
		 * @return this Builder for chaining
		 */
		public Builder enableDeveloperMode() {
			config.enableDeveloperMode = true;
			return this;
		}

		/**
		 * Disables developer mode for the node.
		 * @return this Builder for chaining
		 */
		public Builder disableDeveloperMode() {
			config.enableDeveloperMode = false;
			return this;
		}

		/**
		 * Enables metrics collection for the node.
		 * @return this Builder for chaining
		 */
		public Builder enableMetrics() {
			config.enableMetrics = true;
			return this;
		}

		/**
		 * Disables metrics collection for the node.
		 * @return this Builder for chaining
		 */
		public Builder disableMetrics() {
			config.enableMetrics = false;
			return this;
		}

		/**
		 * Loads the configuration data from a JSON or YAML file.
		 * The format is determined by the file extension (.json for JSON, otherwise YAML).
		 * @param file the string file path to load (must not be null)
		 * @return this Builder for chaining
		 * @throws IOException if I/O error occurs during loading
		 * @throws IllegalArgumentException if the file does not exist or is a directory
		 */
		public Builder load(String file) throws IOException {
			Objects.requireNonNull(file, "file");
			Path configFile = Path.of(file);
			return load(configFile);
		}

		/**
		 * Loads the configuration data from a JSON or YAML file.
		 * The format is determined by the file extension (.json for JSON, otherwise YAML).
		 * @param file the File to load (must not be null)
		 * @return this Builder for chaining
		 * @throws IOException if I/O error occurs during loading
		 * @throws IllegalArgumentException if the file does not exist or is a directory
		 */
		public Builder load(File file) throws IOException {
			Objects.requireNonNull(file, "file");
			return load(file.toPath());
		}

		/**
		 * Loads the configuration data from a JSON or YAML file.
		 * The format is determined by the file extension (.json for JSON, otherwise YAML).
		 * @param file the Path to the file to load (must not be null)
		 * @return this Builder for chaining
		 * @throws IOException if I/O error occurs during loading
		 * @throws IllegalArgumentException if the file does not exist or is a directory
		 */
		public Builder load(Path file) throws IOException {
			Objects.requireNonNull(file, "file");
			file = normalizePath(file);
			if (Files.notExists(file) || Files.isDirectory(file))
				throw new IllegalArgumentException("Invalid config file: " + file);

			try (InputStream in = Files.newInputStream(file)) {
				ObjectMapper mapper = file.getFileName().toString().endsWith(".json") ?
						Json.objectMapper() : Json.yamlMapper();
				config = mapper.readValue(in, DefaultNodeConfiguration.class);
			}

			return this;
		}

		/**
		 * Saves the current configuration to a file in JSON or YAML format.
		 * The format is determined by the file extension (.json for JSON, otherwise YAML).
		 * @param file the string file path to save to (must not be null)
		 * @return this Builder for chaining
		 * @throws IOException if I/O error occurs during saving
		 * @throws IllegalArgumentException if the file path is a directory
		 */
		public Builder save(String file) throws IOException {
			Objects.requireNonNull(file, "file");
			return save(Path.of(file));
		}

		/**
		 * Saves the current configuration to a file in JSON or YAML format.
		 * The format is determined by the file extension (.json for JSON, otherwise YAML).
		 * @param file the File to save to (must not be null)
		 * @return this Builder for chaining
		 * @throws IOException if I/O error occurs during saving
		 * @throws IllegalArgumentException if the file path is a directory
		 */
		public Builder save(File file) throws IOException {
			Objects.requireNonNull(file, "file");
			return save(file.toPath());
		}

		/**
		 * Saves the current configuration to a file in JSON or YAML format.
		 * The format is determined by the file extension (.json for JSON, otherwise YAML).
		 * @param file the Path to save to (must not be null)
		 * @return this Builder for chaining
		 * @throws IOException if I/O error occurs during saving
		 * @throws IllegalArgumentException if the file path is a directory
		 */
		public Builder save(Path file) throws IOException {
			Objects.requireNonNull(file, "file");
			file = normalizePath(file);
			if (Files.exists(file) && Files.isDirectory(file))
				throw new IllegalArgumentException("Invalid config file: " + file);

			Files.createDirectories(file.getParent());
			try (OutputStream out = Files.newOutputStream(file)) {
				ObjectMapper mapper = file.getFileName().toString().endsWith(".json") ?
						Json.objectMapper() : Json.yamlMapper();
				mapper.writeValue(out, config);
			}

			return this;
		}

		/**
		 * Resets the configuration builder object to the initial state,
		 * clearing all existing settings.
		 */
		private void reset() {
			config = new DefaultNodeConfiguration();
		}

		/**
		 * Creates the {@link NodeConfiguration} instance with the current settings in this builder.
		 * After creating the new {@link NodeConfiguration} instance, the builder will be reset to the
		 * initial state.
		 * @return the {@link NodeConfiguration} instance
		 */
		public NodeConfiguration build() {
			if (config.privateKey == null)
				config.privateKey = Base58.encode(Signature.KeyPair.random().privateKey().bytes());

			DefaultNodeConfiguration c = config;
			reset();
			return c;
		}
	}

	/**
	 * Normalizes a filesystem path, expanding '~' to the user's home directory if present,
	 * and converting to an absolute path.
	 * @param path the path to normalize (may be null)
	 * @return the normalized absolute path, or null if input is null
	 */
	private static Path normalizePath(Path path) {
		if (path == null)
			return null;

		path = path.normalize();
		if (path.startsWith("~"))
			path = Path.of(System.getProperty("user.home")).resolve(path.subpath(1, path.getNameCount()));
		else
			path = path.toAbsolutePath();

		return path;
	}
}