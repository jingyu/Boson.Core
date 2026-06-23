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
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.vertx.core.Vertx;
import org.jspecify.annotations.Nullable;

import io.bosonnetwork.crypto.Signature;
import io.bosonnetwork.database.SqlSafety;
import io.bosonnetwork.utils.AddressUtils;
import io.bosonnetwork.utils.Base58;
import io.bosonnetwork.utils.ConfigMap;
import io.bosonnetwork.utils.Hex;

/**
 * Configuration for customizing the initialization and behavior of a Boson DHT node.
 * <p>
 * An instance carries the parameters that define how a DHT node is set up, along with operational
 * features such as metrics and spam throttling. Instances are immutable and are created either with
 * the fluent {@link Builder} (see {@link #builder()}) or from a map via {@link #fromMap(Map)}; the
 * configuration values affect the lifecycle and runtime behavior of the DHT node.
 * </p>
 */
public class NodeConfiguration {
	/**
	 * The default port for the DHT node, chosen from the IANA unassigned range (38866-39062).
	 * See: <a href="https://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xhtml">
	 *     IANA unassigned range (38866-39062)
	 *     </a>
	 */
	public static final int DEFAULT_DHT_PORT = 39001;

	/**
	 * Vert.x instance used for the node's asynchronous operations.
	 */
	private final Vertx vertx;

	/** IPv4 address string for the DHT node. If null or empty, disables DHT on IPv4. */
	private final @Nullable String host4;

	/** IPv6 address string for the DHT node. If null or empty, disables DHT on IPv6.*/
	private final @Nullable String host6;

	/** The port number for the DHT node. */
	private final int port;

	/** The node's private key, encoded in Base58. */
	private final Signature.PrivateKey privateKey;

	/** Path to the directory for persistent DHT data storage. disables persistence if null. */
	private final @Nullable Path dataDir;

	/** Database storage URI for the node. */
	private final String databaseUri;

	/** Database connection pool size. */
	private final int databasePoolSize;

	/** Database schema name. Available for PostgreSQL only*/
	private final @Nullable String databaseSchemaName;

	/** Set of bootstrap nodes for joining the DHT network. */
	private final Set<NodeInfo> bootstraps;

	/** Whether spam throttling is enabled for this node. */
	private final boolean enableSpamThrottling;

	/** Whether suspicious node detection is enabled for this node. */
	private final boolean enableSuspiciousNodeDetector;

	/** Whether developer mode is enabled for this node. */
	private final boolean enableDeveloperMode;

	/** Whether metrics is enabled for this node. */
	private final boolean enableMetrics;

	private NodeConfiguration(Builder builder) {
		Objects.requireNonNull(builder.vertx, "Vertx instance must be provided");
		if ((builder.host4 == null || builder.host4.isEmpty()) && (builder.host6 == null || builder.host6.isEmpty()))
			throw new IllegalArgumentException("Either IPv4 or IPv6 address must be provided");
		Objects.requireNonNull(builder.privateKey, "Private key must be provided");
		Objects.requireNonNull(builder.databaseUri, "Database URI must be provided");

		this.vertx = builder.vertx;
		this.host4 = builder.host4;
		this.host6 = builder.host6;
		this.port = builder.port;
		this.privateKey = builder.privateKey;
		this.dataDir = builder.dataDir;
		this.databaseUri = builder.databaseUri;
		this.databasePoolSize = builder.databasePoolSize;
		this.databaseSchemaName = builder.databaseSchemaName;
		this.bootstraps = Set.copyOf(builder.bootstraps);
		enableSpamThrottling = builder.enableSpamThrottling;
		enableSuspiciousNodeDetector = builder.enableSuspiciousNodeDetector;
		enableDeveloperMode = builder.enableDeveloperMode;
		enableMetrics = builder.enableMetrics;
	}

	/**
	 * Provides the Vert.x instance to be used by the DHT node.
	 *
	 * @return the {@link Vertx} instance to use.
	 */
	public Vertx vertx() {
		return vertx;
	}

	/**
	 * Specifies the IPv4 address to which the DHT node should bind.
	 * <p>
	 * Returning {@code null} disables IPv4 binding.
	 * </p>
	 *
	 * @return the IPv4 address as a string, or {@code null} if IPv4 is disabled.
	 */
	public @Nullable String host4() {
		return host4;
	}

	/**
	 * Specifies the IPv6 address to which the DHT node should bind.
	 * <p>
	 * Returning {@code null} disables IPv6 binding.
	 * </p>
	 *
	 * @return the IPv6 address as a string, or {@code null} if IPv6 is disabled.
	 */
	public @Nullable String host6() {
		return host6;
	}

	/**
	 * Returns the port number on which the DHT node will listen.
	 * <p>
	 * The default port is {@code 39001}. Valid port numbers should be within the unassigned range
	 * as per <a href="https://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xhtml">IANA</a>
	 * (38866-39062, unassigned).
	 * </p>
	 *
	 * @return the port number for the DHT node.
	 */
	public int port() {
		return port;
	}

	/**
	 * Provides the private key used by the DHT node for cryptographic operations.
	 *
	 * @return the private key
	 */
	public Signature.PrivateKey privateKey() {
		return privateKey;
	}

	/**
	 * Returns a {@link Path} to a writable directory for persisting node information and routing tables.
	 * <p>
	 * When specified, the node will periodically save its state and persist data during shutdown.
	 * Returning {@code null} disables data persistence.
	 * </p>
	 *
	 * @return the storage directory path, or {@code null} to disable persistence.
	 */
	public @Nullable Path dataDir() {
		return dataDir;
	}

	/**
	 * Provides the URL for database storage used by the DHT node.
	 *
	 * @return the database URL as a string; defaults to {@code "jdbc:sqlite:node.db"}.
	 */
	public String databaseUri() {
		return databaseUri;
	}

	/**
	 * Provides the configured size of the database connection pool.
	 * <p>
	 * This value determines the maximum number of database connections that can be
	 * simultaneously maintained by the application for performing database operations.
	 *
	 * @return the size of the database connection pool, or {@code 0} if no specific pool size is defined.
	 */
	public int databasePoolSize() {
		return databasePoolSize;
	}

	/**
	 * Returns the database schema name.
	 * This typically corresponds to a namespace, such as the PostgreSQL search path.
	 *
	 * @return the name of the database schema as a string, or {@code null} if no schema is specified.
	 */
	public @Nullable String databaseSchemaName() {
		return databaseSchemaName;
	}

	/**
	 * Returns a collection of bootstrap nodes that the DHT node will use to join the network.
	 * <p>
	 * These nodes are contacted during startup to discover other peers in the DHT.
	 * </p>
	 *
	 * @return a collection of {@link NodeInfo} instances representing bootstrap nodes,
	 *         or an empty collection if none are specified.
	 */
	public Set<NodeInfo> bootstrapNodes() {
		return bootstraps;
	}

	/**
	 * Indicates whether metrics collection is enabled for the DHT node.
	 * <p>
	 * Enabling metrics allows the node to gather and expose operational statistics such as
	 * request rates, error counts, latency, and resource usage, which can aid in monitoring
	 * and debugging.
	 * </p>
	 *
	 * @return {@code true} if metrics collection is enabled; {@code false} otherwise.
	 */
	public boolean enableMetrics() {
		return enableMetrics;
	}

	/**
	 * Indicates whether spam throttling is enabled to mitigate excessive or malicious traffic.
	 *
	 * @return {@code true} if spam throttling is enabled; {@code false} otherwise.
	 */
	public boolean enableSpamThrottling() {
		return enableSpamThrottling;
	}

	/**
	 * Indicates whether suspicious node detection is enabled.
	 * <p>
	 * This feature helps identify and potentially isolate nodes exhibiting abnormal or malicious behavior.
	 * </p>
	 *
	 * @return {@code true} if suspicious node detection is enabled; {@code false} otherwise.
	 */
	public boolean enableSuspiciousNodeDetector() {
		return enableSuspiciousNodeDetector;
	}

	/**
	 * Indicates whether developer mode is enabled.
	 * <p>
	 * Developer mode may enable additional logging, debugging features, or relaxed constraints
	 * useful during development and testing.
	 * </p>
	 *
	 * @return {@code true} if developer mode is enabled; {@code false} otherwise.
	 */
	public boolean enableDeveloperMode() {
		return enableDeveloperMode;
	}

	/**
	 * Creates a new builder for constructing a {@link NodeConfiguration} instance.
	 *
	 * @return a new {@link Builder} instance.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Creates a NodeConfiguration from a Map representation.
	 * <p>
	 * This static factory method deserializes a configuration from a Map structure.
	 * The map should contain the following keys:
	 * <ul>
	 *   <li>{@code host4} (String, optional) - IPv4 address</li>
	 *   <li>{@code host6} (String, optional) - IPv6 address (at least one of host4/host6 required)</li>
	 *   <li>{@code port} (Integer, optional) - DHT port (defaults to 39001)</li>
	 *   <li>{@code privateKey} (String, required) - Base58 or hex-encoded private key</li>
	 *   <li>{@code dataDir} (String, optional) - Path to persistent data directory</li>
	 *   <li>{@code databaseUri} (String, required) - Database URI (defaults to "jdbc:sqlite:node.db")</li>
	 *   <li>{@code databasePoolSize} (int, optional) - Database pool size (defaults to 0)</li>
	 *   <li>{@code databaseSchemaName} (String, optional) - Database schema name (defaults to null)</li>
	 *   <li>{@code bootstraps} (List&lt;List&lt;Object&gt;&gt; optional) - Bootstrap nodes as [id, host, port] triplets</li>
	 *   <li>{@code enableSpamThrottling} (Boolean, optional) - Enable spam throttling (default: true)</li>
	 *   <li>{@code enableSuspiciousNodeDetector} (Boolean, optional) - Enable suspicious node detection (default: true)</li>
	 *   <li>{@code enableDeveloperMode} (Boolean, optional) - Enable developer mode (default: false)</li>
	 *   <li>{@code enableMetrics} (Boolean, optional) - Enable metrics (default: false)</li>
	 * </ul>
	 *
	 * @param map the map containing configuration data, the map must not be null or empty
	 * @return a new {@link NodeConfiguration} instance
	 * @throws NullPointerException if the map is null
	 * @throws IllegalArgumentException if the map is empty, required fields are missing, or values are invalid
	 */
	public static NodeConfiguration fromMap(Map<String, Object> map) {
		Objects.requireNonNull(map, "map");
		if (map.isEmpty())
			throw new IllegalArgumentException("Configuration is empty");

		return builder().fromMap(map).build();
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
		Map<String, Object> map = new LinkedHashMap<>();

		if (host4 != null)
			map.put("host4", host4);

		if (host6 != null)
			map.put("host6", host6);

		map.put("port", port);
		map.put("privateKey", Base58.encode(privateKey.bytes()));

		if (dataDir != null)
			map.put("dataDir", dataDir);

		Map<String, Object> db = new LinkedHashMap<>();
		db.put("uri", databaseUri);
		if (databasePoolSize > 0)
			db.put("poolSize", databasePoolSize);
		if (databaseSchemaName != null)
			db.put("schema", databaseSchemaName);
		map.put("database", db);

		if (!bootstraps.isEmpty()) {
			List<List<Object>> lst = new ArrayList<>();
			bootstraps.forEach(n -> {
				List<Object> ni  = new ArrayList<>();
				ni.add(n.getId().toString());
				if (n.hasAddress4()) {
					ni.add(n.getHost4());
					ni.add(n.getPort4());
				}
				if (n.hasAddress6()) {
					ni.add(n.getHost6());
					ni.add(n.getPort6());
				}
				lst.add(ni);
			});
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
	 * The Builder provides a fluent API for configuring and constructing {@link NodeConfiguration}
	 * instances. It supports setting network addresses, ports, keys, persistent storage, bootstrap nodes,
	 * and feature toggles.
	 */
	public static class Builder {
		/**
		 * Vert.x instance used for the node's asynchronous operations.
		 * May be null if not set.
		 */
		private @Nullable Vertx vertx;

		/** IPv4 address string for the DHT node. If null or empty, disables DHT on IPv4. */
		private @Nullable String host4 = null;

		/** IPv6 address string for the DHT node. If null or empty, disables DHT on IPv6.*/
		private @Nullable String host6 = null;

		/** The port number for the DHT node. */
		private int port = DEFAULT_DHT_PORT;

		/** The node's private key, encoded in Base58. */
		private Signature.@Nullable PrivateKey privateKey;

		/** Path to the directory for persistent DHT data storage. disables persistence if null. */
		private @Nullable Path dataDir = null;

		/** Database storage URI for the node. */
		private String databaseUri;

		/** Database connection pool size. */
		private int databasePoolSize = 0;

		/** Database schema name. Available for PostgreSQL only*/
		private @Nullable String databaseSchemaName = null;

		/** Set of bootstrap nodes for joining the DHT network. */
		private final Set<NodeInfo> bootstraps;

		/** Whether spam throttling is enabled for this node. */
		private boolean enableSpamThrottling = true;

		/** Whether suspicious node detection is enabled for this node. */
		private boolean enableSuspiciousNodeDetector = true;

		/** Whether developer mode is enabled for this node. */
		private boolean enableDeveloperMode = false;

		/** Whether metrics is enabled for this node. */
		private boolean enableMetrics = false;

		/**
		 * Constructs a new Builder with default settings.
		 */
		protected Builder() {
			vertx = Vertx.currentContext() != null ? Vertx.currentContext().owner() : null;
			bootstraps = new HashSet<>();
			databaseUri = "jdbc:sqlite:node.db";
		}

		/**
		 * Set the Vert.x instance to be used by the node.
		 * @param vertx the Vert.x instance (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if vertx is null
		 */
		public Builder vertx(Vertx vertx) {
			Objects.requireNonNull(vertx, "vertx");
			this.vertx = vertx;
			return this;
		}

		/**
		 * Automatically detects and sets the first available unicast IPv4 address for the node.
		 * @return this Builder for chaining
		 * @throws IllegalStateException if no suitable IPv4 address is found
		 */
		public Builder autoHost4() {
			InetAddress addr = AddressUtils.getDefaultRouteAddress(Inet4Address.class);
			if (addr == null)
				throw new IllegalStateException("No available IPv4 address");

			this.host4 = addr.getHostAddress();
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

			this.host6 = addr.getHostAddress();
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
				this.host4 = addr4.getHostAddress();

			if (addr6 != null)
				this.host6 = addr6.getHostAddress();

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
		 * @throws IllegalArgumentException if addr is not an unicast IPv4 address
		 * @throws NullPointerException if addr is null
		 */
		public Builder address4(InetAddress addr) {
			Objects.requireNonNull(addr, "addr");
			if (!AddressUtils.isAnyUnicast(addr))
				throw new IllegalArgumentException("Not a unicast address: " + addr);

			if (addr instanceof Inet4Address)
				this.host4 = addr.getHostAddress();
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
				throw new IllegalArgumentException("Invalid host name or address: " + host, e);
			}
		}

		/**
		 * Set the IPv6 address for the DHT node.
		 * @param addr the IPv6 InetAddress (must not be null)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if addr is not an unicast IPv6 address
		 * @throws NullPointerException if addr is null
		 */
		public Builder address6(InetAddress addr) {
			Objects.requireNonNull(addr, "addr");
			if (!AddressUtils.isAnyUnicast(addr))
				throw new IllegalArgumentException("Not a unicast address: " + addr);

			if (addr instanceof Inet6Address)
				this.host6 = addr.getHostAddress();
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

			this.port = port;
			return this;
		}

		/**
		 * Generates a new random private key for the node.
		 * @return this Builder for chaining
		 */
		public Builder generatePrivateKey() {
			this.privateKey = Signature.KeyPair.random().privateKey();
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
			this.privateKey = Signature.PrivateKey.fromBytes(privateKey);
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
			this.privateKey = Signature.PrivateKey.fromBytes(key);
			return this;
		}

		/**
		 * Checks if a private key is present.
		 *
		 * @return true if a private key exists, false otherwise.
		 */
		public boolean hasPrivateKey() {
			return privateKey != null;
		}

		/**
		 * Set the storage path for DHT persistent data using a string path.
		 * @param dir the string path (maybe null to disable persistence)
		 * @return this Builder for chaining
		 */
		public Builder dataDir(@Nullable String dir) {
			return dataDir(dir != null ? Path.of(dir) : null);
		}

		/**
		 * Set the storage path for DHT persistent data using a File object.
		 * @param path the File pointing to the storage directory (maybe null to disable persistence)
		 * @return this Builder for chaining
		 */
		public Builder dataDir(@Nullable File path) {
			dataDir(path != null ? path.toPath() : null);
			return this;
		}

		/**
		 * Set the storage path for DHT persistent data using a Path.
		 * @param path the Path to the storage directory (maybe null to disable persistence)
		 * @return this Builder for chaining
		 */
		public Builder dataDir(@Nullable Path path) {
			this.dataDir = path;
			return this;
		}

		/**
		 * Set the database URI for the node.
		 * @param uri the database URI (must not be null)
		 * @param poolSize the database connection pool size
		 * @return this Builder for chaining
		 * @throws NullPointerException if storageURI is null
		 * @throws IllegalArgumentException if the URI is not supported or the pool size is invalid
		 */
		public Builder database(String uri, int poolSize) {
			databaseUri(uri);
			databasePoolSize(poolSize);
			return this;
		}

		/**
		 * Set the database URI for the node.
		 * @param uri the database URI (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if storageURI is null
		 * @throws IllegalArgumentException if the URI is not supported or the pool size is invalid
		 */
		public Builder databaseUri(String uri) {
			Objects.requireNonNull(uri, "uri");
			if (!uri.startsWith("postgresql://") && !uri.startsWith("jdbc:sqlite:"))
				throw new IllegalArgumentException("Unsupported storage URL: " + uri);
			this.databaseUri = uri;
			return this;
		}

		/**
		 * Set the database connection pool size.
		 * @param poolSize the connection pool size (must be non-negative)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if the pool size is negative
		 */
		public Builder databasePoolSize(int poolSize) {
			if (poolSize < 0)
				throw new IllegalArgumentException("Invalid pool size: " + poolSize);
			this.databasePoolSize = poolSize;
			return this;
		}

		/**
		 * Sets the database schema name to be used. The schema name must start with a
		 * lowercase letter and may contain lowercase letters, digits, and underscores
		 * with a maximum length of 32 characters. If the provided schema is null or
		 * empty, the schema name will be set to null.
		 * <p>
		 * NOTICE: the schema only available to PostgreSQL databases.
		 *         It will be ignored for SQLite databases.
		 *
		 * @param schema the name of the database schema
		 * @return the builder instance for method chaining
		 * @throws IllegalArgumentException if the schema name does not match the
		 *         required pattern or exceeds the maximum length
		 */
		public Builder databaseSchemaName(@Nullable String schema) {
			this.databaseSchemaName = SqlSafety.validateSchema(schema);
			return this;
		}

		/**
		 * Add a new bootstrap node to the configuration.
		 *
		 * @param id the Id of the bootstrap node
		 * @param addr the string address of the bootstrap node
		 * @param port the port of the bootstrap node
		 * @return this Builder for chaining
		 */
		public Builder addBootstrap(String id, String addr, int port) {
			NodeInfo node = NodeInfo.of(Id.of(id), addr, port);
			this.bootstraps.add(node);
			return this;
		}

		public Builder addBootstrap(String id, String addr4, int port4, String addr6, int port6) {
			NodeInfo node = NodeInfo.of(Id.of(id), addr4, port4, addr6, port6);
			this.bootstraps.add(node);
			return this;
		}

		/**
		 * Adds a bootstrap node to the configuration.
		 *
		 * @param id   the unique identifier of the bootstrap node
		 * @param addr the address of the bootstrap node
		 * @param port the port number of the bootstrap node
		 * @return the builder instance for chaining
		 */
		public Builder addBootstrap(Id id, String addr, int port) {
			NodeInfo node = NodeInfo.of(id, addr, port);
			this.bootstraps.add(node);
			return this;
		}

		public Builder addBootstrap(Id id, String addr4, int port4, String addr6, int port6) {
			NodeInfo node = NodeInfo.of(id, addr4, port4, addr6, port6);
			this.bootstraps.add(node);
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
			NodeInfo node = NodeInfo.of(id, addr, port);
			this.bootstraps.add(node);
			return this;
		}

		public Builder addBootstrap(Id id, InetAddress addr4, int port4, InetAddress addr6, int port6) {
			NodeInfo node = NodeInfo.of(id, addr4, port4, addr6, port6);
			this.bootstraps.add(node);
			return this;
		}

		/**
		 * Add a new bootstrap node to the configuration.
		 * @param id the Id of the bootstrap node
		 * @param addr the InetSocketAddress of the bootstrap node
		 * @return this Builder for chaining
		 */
		public Builder addBootstrap(Id id, InetSocketAddress addr) {
			NodeInfo node = NodeInfo.of(id, addr);
			this.bootstraps.add(node);
			return this;
		}

		public Builder addBootstrap(Id id, InetSocketAddress addr4, InetSocketAddress addr6) {
			NodeInfo node = NodeInfo.of(id, addr4, addr6);
			this.bootstraps.add(node);
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
			this.bootstraps.add(node);
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
			this.bootstraps.addAll(nodes);
			return this;
		}

		/**
		 * Sets whether spam throttling is enabled for the node.
		 * @param enable true to enable spam throttling, false to disable
		 * @return this Builder for chaining
		 */
		public Builder setSpamThrottling(boolean enable) {
			this.enableSpamThrottling = enable;
			return this;
		}

		/**
		 * Sets whether suspicious node detection is enabled for the node.
		 * @param enable true to enable suspicious node detection, false to disable
		 * @return this Builder for chaining
		 */
		public Builder setSuspiciousNodeDetector(boolean enable) {
			this.enableSuspiciousNodeDetector = enable;
			return this;
		}

		/**
		 * Sets whether developer mode is enabled for the node.
		 * @param enable true to enable developer mode, false to disable
		 * @return this Builder for chaining
		 */
		public Builder setDeveloperMode(boolean enable) {
			this.enableDeveloperMode = enable;
			return this;
		}

		/**
		 * Enables metrics for the node.
		 * @param enable true to enable metrics, false to disable
		 * @return this Builder for chaining
		 */
		public Builder setMetrics(boolean enable) {
			this.enableMetrics = enable;
			return this;
		}

		/**
		 * Populates the builder with values from the specified map. The map is expected to contain
		 * configurations relating to network, database, and other settings.
		 *
		 * @param map A nullable map containing configuration data. If the map is null or empty, the
		 *            method returns the current builder instance without making changes.
		 * @return The builder instance with the configurations applied from the map, enabling method chaining.
		 * @throws IllegalArgumentException If any bootstrap node configuration is invalid or missing required fields.
		 */
		public Builder fromMap(@Nullable Map<String, Object> map) {
			if (map == null || map.isEmpty())
				return this;

			ConfigMap m = new ConfigMap(map);

			String host4 = m.getString("host4", null);
			if (host4 != null && !host4.isEmpty())
				host4(host4);

			String host6 = m.getString("host6", null);
			if (host6 != null && !host6.isEmpty())
				host6(host6);

			port(m.getPort("port", DEFAULT_DHT_PORT));
			String sk = m.getString("privateKey", null);
			if (sk != null && !sk.isEmpty())
				privateKey(sk);

			Path dataDir = m.getPath("dataDir", null);
			if (dataDir != null)
				dataDir(dataDir);

			ConfigMap db = m.getObject("database");
			if (db != null && !db.isEmpty()) {
				String databaseUri = db.getString("uri", null);
				if (databaseUri != null && !databaseUri.isEmpty())
					databaseUri(databaseUri);

				databasePoolSize(db.getInteger("poolSize", 0));
				databaseSchemaName(db.getString("schema", null));
			}

			List<List<Object>> lst = m.getList("bootstraps");
			if (lst != null && !lst.isEmpty()) {
				lst.forEach(b -> {
					int size = b.size();
					if (size != 3 && size != 5)
						throw new IllegalArgumentException("Invalid bootstrap node: missing fields - " + b);

					try {
						Id id = Id.of((String) b.get(0));
						String host1 = (String) b.get(1);
						int port1 = (int) b.get(2);
						String host2 = size == 5 ? (String) b.get(3) : null;
						int port2 = size == 5 ? (int) b.get(4) : 0;
						addBootstrap(NodeInfo.of(id, host1, port1, host2, port2));
					} catch (Exception e) {
						throw new IllegalArgumentException("Invalid bootstrap node: " + b);
					}
				});
			}

			setSpamThrottling(m.getBoolean("enableSpamThrottling", enableSpamThrottling));
			setSuspiciousNodeDetector(m.getBoolean("enableSuspiciousNodeDetector", enableSuspiciousNodeDetector));
			setDeveloperMode(m.getBoolean("enableDeveloperMode", enableDeveloperMode));
			setMetrics(m.getBoolean("enableMetrics", enableMetrics));
			return this;
		}

		/**
		 * Creates the {@link NodeConfiguration} instance with the current settings in this builder.
		 *
		 * @return the {@link NodeConfiguration} instance
		 * @throws IllegalStateException if the current settings do not form a valid configuration
		 *         (for example, no Vert.x instance, no IPv4/IPv6 address, or no private key)
		 */
		public NodeConfiguration build() {
			try {
				return new NodeConfiguration(this);
			} catch (NullPointerException | IllegalArgumentException e) {
				throw new IllegalStateException("Invalid NodeConfiguration: " + e.getMessage(), e);
			}
		}
	}
}