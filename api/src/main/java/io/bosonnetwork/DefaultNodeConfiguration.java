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
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.vertx.core.Vertx;

import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.utils.Base58;
import io.bosonnetwork.utils.ConfigMap;
import io.bosonnetwork.utils.Hex;

/**
 * Default configuration implementation for the {@link NodeConfiguration} interface.
 * <p>
 * Use the {@link Builder} class to construct instances with a fluent API. The configuration
 * can also be serialized to/from Map for persistence or network transmission.
 * </p>
 *
 * @see NodeConfiguration
 * @see Builder
 */
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
	private String host4;

	/**
	 * IPv6 address string for the DHT node. If null or empty, disables DHT on IPv6.
	 */
	private String host6;

	/**
	 * The port number for the DHT node.
	 */
	private int port;

	/**
	 * The node's private key, encoded in Base58.
	 */
	private Signature.PrivateKey privateKey;

	/**
	 * Path to the directory for persistent DHT data storage. disables persistence if null.
	 */
	private Path dataDir;

	/**
	 * Optional external storage URI for the node.
	 */
	private String storageURI;

	/**
	 * Set of bootstrap nodes for joining the DHT network.
	 */
	private final Set<NodeInfo> bootstraps;

	/**
	 * Whether spam throttling is enabled for this node.
	 */
	private boolean enableSpamThrottling;

	/**
	 * Whether suspicious node detection is enabled for this node.
	 */
	private boolean enableSuspiciousNodeDetector;

	/**
	 * Whether developer mode is enabled for this node.
	 */
	private boolean enableDeveloperMode;

	/**
	 * Whether metrics is enabled for this node.
	 */
	private boolean enableMetrics;

	/**
	 * Constructs a new DefaultNodeConfiguration with default settings.
	 * The default port is {@link #DEFAULT_DHT_PORT}, spam throttling and suspicious node detector are enabled,
	 * developer mode and metrics are disabled, and no bootstraps are set.
	 */
	private DefaultNodeConfiguration() {
		this.port = DEFAULT_DHT_PORT;
		this.storageURI = "jdbc:sqlite:node.db";
		this.enableSpamThrottling = true;
		this.enableSuspiciousNodeDetector = true;
		this.enableDeveloperMode = false;
		this.enableMetrics = false;

		this.bootstraps = new HashSet<>();
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
	 * Sets the Vert.x instance for this configuration.
	 * <p>
	 * This method is typically used internally to inject the Vert.x instance after
	 * configuration construction.
	 * </p>
	 *
	 * @param vertx the Vert.x instance to set
	 */
	public void setVertx(Vertx vertx) {
		this.vertx = vertx;
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
	public Signature.PrivateKey privateKey() {
		return privateKey;
	}

	/**
	 * {@inheritDoc}
	 * @return the path to the persistent data directory, or null if persistence is disabled.
	 */
	@Override
	public Path dataDir() {
		return dataDir;
	}

	/**
	 * {@inheritDoc}
	 * @return the external storage URL, or null if not set.
	 */
	@Override
	public String storageURI() {
		return storageURI;
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
	 * @return true if metrics is enabled.
	 */
	@Override
	public boolean enableMetrics() {
		return enableMetrics;
	}

	/**
	 * Creates a DefaultNodeConfiguration from a Map representation.
	 * <p>
	 * This static factory method deserializes a configuration from a Map structure.
	 * The map should contain the following keys:
	 * <ul>
	 *   <li>{@code host4} (String, optional) - IPv4 address</li>
	 *   <li>{@code host6} (String, optional) - IPv6 address (at least one of host4/host6 required)</li>
	 *   <li>{@code port} (Integer, optional) - DHT port (defaults to 39001)</li>
	 *   <li>{@code privateKey} (String, required) - Base58 or hex-encoded private key</li>
	 *   <li>{@code dataDir} (String, optional) - Path to persistent data directory</li>
	 *   <li>{@code storageURI} (String, required) - Storage URI (defaults to "jdbc:sqlite:node.db")</li>
	 *   <li>{@code bootstraps} (List&lt;List&lt;Object&gt;&gt;, optional) - Bootstrap nodes as [id, host, port] triplets</li>
	 *   <li>{@code enableSpamThrottling} (Boolean, optional) - Enable spam throttling (default: true)</li>
	 *   <li>{@code enableSuspiciousNodeDetector} (Boolean, optional) - Enable suspicious node detection (default: true)</li>
	 *   <li>{@code enableDeveloperMode} (Boolean, optional) - Enable developer mode (default: false)</li>
	 *   <li>{@code enableMetrics} (Boolean, optional) - Enable metrics (default: false)</li>
	 * </ul>
	 *
	 * @param map the map containing configuration data, must not be null or empty
	 * @return a new DefaultNodeConfiguration instance
	 * @throws NullPointerException if map is null
	 * @throws IllegalArgumentException if map is empty, required fields are missing, or values are invalid
	 */
	public static DefaultNodeConfiguration fromMap(Map<String, Object> map) {
		Objects.requireNonNull(map, "map");
		if (map.isEmpty())
			throw new IllegalArgumentException("Configuration is empty");

		DefaultNodeConfiguration config = new DefaultNodeConfiguration();

		ConfigMap m = new ConfigMap(map);

		config.host4 = m.getString("host4", config.host4);
		config.host6 = m.getString("host6", config.host6);
		if ((config.host4 == null || config.host4.isEmpty()) && (config.host6 == null || config.host6.isEmpty()))
			throw new IllegalArgumentException("Missing host4 or host6");

		config.port = m.getPort("port", config.port);
		String sk = m.getString("privateKey", null);
		if (sk == null || sk.isEmpty())
			throw new IllegalArgumentException("Missing privateKey");
		try {
			byte[] keyBytes = sk.startsWith("0x") ? Hex.decode(sk.substring(2)) : Base58.decode(sk);
			config.privateKey = Signature.PrivateKey.fromBytes(keyBytes);
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid privateKey: " + config.privateKey);
		}

		String dir = m.getString("dataDir", null);
		if (dir != null && !dir.isEmpty())
			config.dataDir = Path.of(dir);

		config.storageURI = m.getString("storageURI", config.storageURI);
		if (config.storageURI == null || config.storageURI.isEmpty())
			throw new IllegalArgumentException("Missing storageURI");

		List<List<Object>> lst = m.getList("bootstraps");
		if (lst != null && !lst.isEmpty()) {
			lst.forEach(b -> {
				if (b.size() != 3)
					throw new IllegalArgumentException("Invalid bootstrap node: missing fields - " + b);

				try {
					Id id = Id.of((String) b.get(0));
					String host = (String) b.get(1);
					int port = (int) b.get(2);

					config.bootstraps.add(new NodeInfo(id, host, port));
				} catch (Exception e) {
					throw new IllegalArgumentException("Invalid bootstrap node: " + b);
				}
			});
		}

		config.enableSpamThrottling = m.getBoolean("enableSpamThrottling", config.enableSpamThrottling);
		config.enableSuspiciousNodeDetector = m.getBoolean("enableSuspiciousNodeDetector", config.enableSuspiciousNodeDetector);
		config.enableDeveloperMode = m.getBoolean("enableDeveloperMode", config.enableDeveloperMode);
		config.enableMetrics = m.getBoolean("enableMetrics", config.enableMetrics);

		return config;
	}

	/**
	 * Serializes this configuration to a Map representation.
	 * <p>
	 * The returned map contains all configured values and can be used for persistence,
	 * network transmission, or creating a new configuration via {@link #fromMap(Map)}.
	 * Null or empty values are excluded from the map.
	 * </p>
	 *
	 * @return a Map containing the configuration data
	 */
	public Map<String, Object> toMap() {
		HashMap<String, Object> map = new HashMap<>();

		if (host4 != null)
			map.put("host4", host4);

		if (host6 != null)
			map.put("host6", host6);

		map.put("port", port);
		map.put("privateKey", Base58.encode(privateKey.bytes()));

		if (dataDir != null)
			map.put("dataDir", dataDir);

		map.put("storageURI", storageURI);

		if (!bootstraps.isEmpty()) {
			List<List<Object>> lst = new ArrayList<>();
			bootstraps.forEach(n -> lst.add(Arrays.asList(n.getId().toString(), n.getHost(), n.getPort())));
			map.put("bootstraps", lst);
		}

		map.put("enableSpamThrottling", enableSpamThrottling);
		map.put("enableSuspiciousNodeDetector", enableSuspiciousNodeDetector);
		map.put("enableDeveloperMode", enableDeveloperMode);
		map.put("enableMetrics", enableMetrics);

		return map;
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
			config = new DefaultNodeConfiguration();
		}

		/**
		 * Gets or lazily initializes the configuration instance.
		 * <p>
		 * This private helper ensures the config field is initialized on first access.
		 * </p>
		 *
		 * @return the configuration instance
		 */
		private DefaultNodeConfiguration config() {
			return config == null ? config = new DefaultNodeConfiguration() : config;
		}

		/**
		 * Initializes this builder from a template Map.
		 * <p>
		 * This method loads a complete configuration from a Map, replacing any previously
		 * set values. The template map should follow the same structure as expected by
		 * {@link DefaultNodeConfiguration#fromMap(Map)}.
		 * </p>
		 *
		 * @param template the template map containing configuration data, must not be null
		 * @return this Builder for chaining
		 * @throws NullPointerException if template is null
		 * @throws IllegalArgumentException if the template is invalid
		 * @see DefaultNodeConfiguration#fromMap(Map)
		 */
		public Builder template(Map<String, Object> template) {
			Objects.requireNonNull(template, "template");
			this.config = DefaultNodeConfiguration.fromMap(template);
			return this;
		}

		/**
		 * Set the Vert.x instance to be used by the node.
		 * @param vertx the Vert.x instance (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if vertx is null
		 */
		public Builder vertx(Vertx vertx) {
			Objects.requireNonNull(vertx, "vertx");
			config().vertx = vertx;
			return this;
		}

		/**
		 * Automatically detects and sets the first available unicast IPv4 address for the node.
		 * @return this Builder for chaining
		 * @throws IllegalStateException if no suitable IPv4 address is found
		 */
		public Builder autoHost4() {
			InetAddress addr = AddressUtils.getDefaultRouteAddress(Inet6Address.class);
			if (addr == null)
				throw new IllegalStateException("No available IPv4 address");

			config().host4 = addr.getHostAddress();
			return this;
		}

		/**
		 * Automatically detects and sets the first available unicast IPv6 address for the node.
		 * @return this Builder for chaining
		 * @throws IllegalStateException if no suitable IPv6 address is found
		 */
		public Builder autoHost6() {
			InetAddress addr = AddressUtils.getDefaultRouteAddress(Inet6Address.class);
			if (addr == null)
				throw new IllegalStateException("No available IPv6 address");

			config().host6 = addr.getHostAddress();
			return this;
		}

		/**
		 * Automatically detects and sets both IPv4 and IPv6 addresses for the node.
		 * @return this Builder for chaining
		 * @throws IllegalStateException if neither IPv4 nor IPv6 addresses are found
		 */
		public Builder autoHosts() {
			InetAddress addr4;
			try {
				addr4 = AddressUtils.getDefaultRouteAddress(Inet4Address.class);
			} catch (Exception e) {
				addr4 = null;
			}

			InetAddress addr6;
			try {
				addr6 = AddressUtils.getDefaultRouteAddress(Inet6Address.class);
			} catch (Exception e) {
				addr6 = null;
			}

			if (addr4 == null && addr6 == null)
				throw new IllegalStateException("No available IPv4/6 address");

			if (addr4 != null)
				config().host4 = addr4.getHostAddress();

			if (addr6 != null)
				config().host6 = addr6.getHostAddress();

			return this;
		}

		/**
		 * Set the IPv4 address for the DHT node.
		 * @param host the string host name or IPv4 address (must not be null)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if the host is not a valid IPv4 address
		 * @throws NullPointerException if the host is null
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
				config().host4 = addr.getHostAddress();
			else
				throw new IllegalArgumentException("Invalid IPv4 address: " + addr);

			return this;
		}

		/**
		 * Set the IPv6 address for the DHT node.
		 * @param host the string host name or IPv6 address (must not be null)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if the host is not a valid IPv6 address
		 * @throws NullPointerException if the host is null
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
				config().host6 = addr.getHostAddress();
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

			config().port = port;
			return this;
		}

		/**
		 * Generates a new random private key for the node.
		 * @return this Builder for chaining
		 */
		public Builder generatePrivateKey() {
			config().privateKey = Signature.KeyPair.random().privateKey();
			return this;
		}

		/**
		 * Set the node's private key from a raw byte array.
		 * @param privateKey the private key bytes (must be 64 bytes)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if the key is not 64 bytes
		 */
		public Builder privateKey(byte[] privateKey) {
			Objects.requireNonNull(privateKey, "privateKey");
			config().privateKey = Signature.PrivateKey.fromBytes(privateKey);
			return this;
		}

		/**
		 * Set the node's private key from a Base58-encoded string.
		 * @param privateKey the Base58-encoded or hex-encoded private key string (must not be null)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if the key is not 64 bytes when decoded
		 * @throws NullPointerException if privateKey is null
		 */
		public Builder privateKey(String privateKey) {
			Objects.requireNonNull(privateKey, "privateKey");
			byte[] key = privateKey.startsWith("0x") ?
					Hex.decode(privateKey, 2, privateKey.length() - 2) :
					Base58.decode(privateKey);
			config().privateKey = Signature.PrivateKey.fromBytes(key);
			return this;
		}

		/**
		 * Checks if a private key has been set in this builder.
		 * @return true if a private key is set, false otherwise
		 */
		public boolean hasPrivateKey() {
			return config().privateKey != null;
		}

		/**
		 * Set the storage path for DHT persistent data using a string path.
		 * @param dir the string path (maybe null to disable persistence)
		 * @return this Builder for chaining
		 */
		public Builder dataDir(String dir) {
			return dataDir(dir != null ? Path.of(dir) : null);
		}

		/**
		 * Set the storage path for DHT persistent data using a File object.
		 * @param path the File pointing to the storage directory (maybe null to disable persistence)
		 * @return this Builder for chaining
		 */
		public Builder dataDir(File path) {
			dataDir(path != null ? path.toPath() : null);
			return this;
		}

		/**
		 * Set the storage path for DHT persistent data using a Path.
		 * @param path the Path to the storage directory (maybe null to disable persistence)
		 * @return this Builder for chaining
		 */
		public Builder dataDir(Path path) {
			config().dataDir = path;
			return this;
		}

		/**
		 * Checks if a data path has been set.
		 * @return true if a data path is set, false otherwise
		 */
		public boolean hasDataDir() {
			return config().dataDir != null;
		}

		/**
		 * Gets the current data path set in the builder.
		 * @return the Path to the storage directory, or null if not set
		 */
		public Path dataDir() {
			return config().dataDir;
		}

		/**
		 * Set the external storage URL for the node.
		 * @param storageURI the storage URL (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if storageURI is null
		 */
		public Builder storageURI(String storageURI) {
			Objects.requireNonNull(storageURI, "storageURI");
			if (!storageURI.startsWith("postgresql://") && !storageURI.startsWith("jdbc:sqlite:"))
				throw new IllegalArgumentException("Unsupported storage URL: " + storageURI);
			config().storageURI = storageURI;
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
			config().bootstraps.add(node);
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
			config().bootstraps.add(node);
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
			config().bootstraps.add(node);
			return this;
		}

		/**
		 * Add a new bootstrap node to the configuration.
		 * @param node the NodeInfo of the bootstrap node (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if the node is null
		 */
		public Builder addBootstrap(NodeInfo node) {
			Objects.requireNonNull(node, "node");
			config().bootstraps.add(node);
			return this;
		}

		/**
		 * Add multiple bootstrap nodes to the configuration.
		 * @param nodes the collection of NodeInfo bootstrap nodes (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if the nodes parameter is null
		 */
		public Builder addBootstrap(Collection<NodeInfo> nodes) {
			Objects.requireNonNull(nodes, "nodes");
			config().bootstraps.addAll(nodes);
			return this;
		}

		/**
		 * Enables spam throttling for the node.
		 * @return this Builder for chaining
		 */
		public Builder enableSpamThrottling() {
			config().enableSpamThrottling = true;
			return this;
		}

		/**
		 * Disables spam throttling for the node.
		 * @return this Builder for chaining
		 */
		public Builder disableSpamThrottling() {
			config().enableSpamThrottling = false;
			return this;
		}

		/**
		 * Enables suspicious node detection for the node.
		 * @return this Builder for chaining
		 */
		public Builder enableSuspiciousNodeDetector() {
			config().enableSuspiciousNodeDetector = true;
			return this;
		}

		/**
		 * Disables suspicious node detection for the node.
		 * @return this Builder for chaining
		 */
		public Builder disableSuspiciousNodeDetector() {
			config().enableSuspiciousNodeDetector = false;
			return this;
		}

		/**
		 * Enables developer mode for the node.
		 * @return this Builder for chaining
		 */
		public Builder enableDeveloperMode() {
			config().enableDeveloperMode = true;
			return this;
		}

		/**
		 * Disables developer mode for the node.
		 * @return this Builder for chaining
		 */
		public Builder disableDeveloperMode() {
			config().enableDeveloperMode = false;
			return this;
		}

		/**
		 * Enables metrics for the node.
		 * @param enable true to enable metrics, false to disable
		 * @return this Builder for chaining
		 */
		public Builder enableMetrics(boolean enable) {
			config().enableMetrics = enable;
			return this;
		}

		/**
		 * Creates the {@link NodeConfiguration} instance with the current settings in this builder.
		 * After creating the new {@link NodeConfiguration} instance, the builder will be reset to the
		 * initial state.
		 * @return the {@link NodeConfiguration} instance
		 */
		public NodeConfiguration build() {
			if (config().host4() == null && config().host6() == null)
				throw new IllegalArgumentException("Missing host4 or host6");

			if (config().privateKey == null)
				generatePrivateKey();

			DefaultNodeConfiguration c = config();
			config = null;
			return c;
		}
	}
}