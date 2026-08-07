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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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
import io.bosonnetwork.utils.FileUtils;
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
public record NodeConfiguration(Vertx vertx, NodeListenOptions listen, Signature.KeyPair keyPair,
								Path dataDir, NodeDatabaseOptions database,
								KademliaOptions kademlia, Set<NodeInfo> bootstraps, SecurityOptions security) {
	/**
	 * The default port for the DHT node, chosen from the IANA unassigned range (38866-39062).
	 * See: <a href="https://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xhtml">
	 * IANA unassigned range (38866-39062)
	 * </a>
	 */
	public static final int DEFAULT_DHT_PORT = 39001;

	public static final int DEFAULT_ALPHA = 3;
	public static final int DEFAULT_K = 16;
	public static final int DEFAULT_REPLACEMENTS = 8;
	public static final int DEFAULT_CONCURRENT_TASKS = 32;

	public static final boolean DEFAULT_SPAM_THROTTLING = true;
	public static final boolean DEFAULT_SUSPICIOUS_NODE_DETECTOR = true;
	public static final boolean DEFAULT_DEVELOPER_MODE = false;

	private static final String DEFAULT_DATABASE_URI = "jdbc:sqlite:node.db";

	/**
	 * The endpoints the DHT node binds to.
	 * <p>
	 * A node speaks UDP on both address families at once, so unlike a service's listener - see
	 * {@code io.bosonnetwork.service.config.ListenOptions}, a different type despite the similar
	 * name - this carries an IPv4 and an IPv6 endpoint and has no TLS setting. Within each family
	 * the host and the network interface are alternatives: naming both is an error, because the
	 * node cannot tell which one the operator meant.
	 * <p>
	 * These settings sit at the top level of the configuration document rather than in a named
	 * block, and the document is read into a builder that also carries command line arguments, so
	 * the reading half lives in {@link Builder#fromMap(Map)} rather than here.
	 *
	 * @param host4             the IPv4 host to bind to, or {@code null} to disable IPv4
	 * @param networkInterface4 the interface whose IPv4 address to bind to, or {@code null}
	 * @param host6             the IPv6 host to bind to, or {@code null} to disable IPv6
	 * @param networkInterface6 the interface whose IPv6 address to bind to, or {@code null}
	 * @param port              the UDP port to listen on, in the range {@code [1, 65535]}; both
	 *                          families use the same port
	 */
	public record NodeListenOptions(@Nullable String host4, @Nullable String networkInterface4,
									@Nullable String host6, @Nullable String networkInterface6,
									int port) {
		/**
		 * Canonical constructor, and the only place these settings are validated.
		 *
		 * @param host4             the IPv4 host, or {@code null}
		 * @param networkInterface4 the IPv4 interface, or {@code null}
		 * @param host6             the IPv6 host, or {@code null}
		 * @param networkInterface6 the IPv6 interface, or {@code null}
		 * @param port              the UDP port, in the range {@code [1, 65535]}
		 * @throws IllegalArgumentException if both a host and an interface are named for the same
		 *                                  address family, if neither family is configured, or if
		 *                                  the port is out of range
		 */
		public NodeListenOptions {
			// An absent setting reaches this constructor as null from a programmatic caller and as
			// an empty string from a document that names the key without a value. Normalize here so
			// that the two spellings are one value from this point on: consumers test for null, and
			// an empty string reaching a bind call would silently mean the wildcard address.
			host4 = emptyToNull(host4);
			networkInterface4 = emptyToNull(networkInterface4);
			host6 = emptyToNull(host6);
			networkInterface6 = emptyToNull(networkInterface6);

			if (host4 != null && networkInterface4 != null)
				throw new IllegalArgumentException("Both IPv4 host and network interface are specified for the node; only one is allowed");
			if (host6 != null && networkInterface6 != null)
				throw new IllegalArgumentException("Both IPv6 host and network interface are specified for the node; only one is allowed");

			if (host4 == null && networkInterface4 == null && host6 == null && networkInterface6 == null)
				throw new IllegalArgumentException("either IPv4 or IPv6 host or network interface must be provided");

			if (port < 1 || port > 65535)
				throw new IllegalArgumentException("Invalid port: " + port);
		}

		Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			if (host4 != null)
				map.put("host4", host4);
			if (networkInterface4 != null)
				map.put("interface4", networkInterface4);
			if (host6 != null)
				map.put("host6", host6);
			if (networkInterface6 != null)
				map.put("interface6", networkInterface6);
			map.put("port", port);
			return map;
		}
	}

	/**
	 * How the DHT node reaches its persistence database.
	 * <p>
	 * This mirrors {@code io.bosonnetwork.service.config.DatabaseOptions} - same keys, same
	 * meanings - but is a separate type so that {@code io.bosonnetwork} does not depend on the
	 * service configuration package. The two are expected to stay in step: a change to the schema
	 * rules or the pool size semantics of one belongs in the other as well.
	 *
	 * @param uri      the connection URI
	 * @param poolSize the connection pool size, or {@code 0} to use the driver default
	 * @param schema   the schema name, or {@code null} for the default schema; ignored by drivers that
	 *                 have no notion of a schema
	 */
	public record NodeDatabaseOptions(String uri, int poolSize, @Nullable String schema) {
		/**
		 * Canonical constructor.
		 *
		 * @param uri      the connection URI
		 * @param poolSize the connection pool size, or {@code 0} for the driver default
		 * @param schema   the schema name, or {@code null} for the default schema
		 * @throws NullPointerException     if {@code uri} is null
		 * @throws IllegalArgumentException if {@code uri} is empty or unsupported, if {@code poolSize}
		 *                                  is negative, or if {@code schema} is not a safe identifier
		 */
		public NodeDatabaseOptions {
			Objects.requireNonNull(uri, "uri");
			if (uri.isEmpty())
				throw new IllegalArgumentException("uri is empty");
			// Checked here rather than only on the reading path, so that a programmatic caller
			// cannot build a configuration whose driver the node does not actually have.
			checkDatabaseUri(uri);
			if (poolSize < 0)
				throw new IllegalArgumentException("Invalid poolSize: " + poolSize);
			schema = SqlSafety.validateSchema(schema);
		}

		static NodeDatabaseOptions fromMap(@Nullable ConfigMap cm) {
			if (cm == null || cm.isEmpty())
				return new NodeDatabaseOptions(DEFAULT_DATABASE_URI, 0, null);

			return new NodeDatabaseOptions(
					Objects.requireNonNullElse(cm.getString("uri", DEFAULT_DATABASE_URI), DEFAULT_DATABASE_URI),
					cm.getNonNegativeInteger("poolSize", 0),
					cm.getString("schema", null));
		}

		Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("uri", uri);
			if (poolSize != 0)
				map.put("poolSize", poolSize);
			if (schema != null)
				map.put("schema", schema);
			return map;
		}
	}

	/**
	 * The Kademlia routing and lookup parameters.
	 *
	 * @param alpha             the Kademlia concurrency parameter: how many nodes a lookup queries in parallel
	 * @param k                 the Kademlia bucket size
	 * @param replacements      how many replacement entries each bucket keeps
	 * @param concurrentTasks the ceiling on concurrently running DHT tasks; further tasks are queued
	 */
	public record KademliaOptions(int alpha, int k, int replacements, int concurrentTasks) {
		/** Accepted range for {@link #alpha()}. */
		public static final int MIN_ALPHA = 1, MAX_ALPHA = 32;
		/** Accepted range for {@link #k()}. */
		public static final int MIN_K = 4, MAX_K = 128;
		/** Accepted range for {@link #replacements()}. */
		public static final int MIN_REPLACEMENTS = 4, MAX_REPLACEMENTS = 128;
		/** Accepted minimum for {@link #concurrentTasks()}; it has no upper bound. */
		public static final int MIN_CONCURRENT_TASKS = 16;

		/**
		 * Canonical constructor. Validates via the shared checks below, so a value rejected here is
		 * rejected identically wherever else it is supplied.
		 *
		 * @param alpha             the concurrency parameter, in [{@value #MIN_ALPHA}, {@value #MAX_ALPHA}]
		 * @param k                 the bucket size, in [{@value #MIN_K}, {@value #MAX_K}]
		 * @param replacements      the replacement count, in [{@value #MIN_REPLACEMENTS}, {@value #MAX_REPLACEMENTS}]
		 * @param concurrentTasks   the concurrent task ceiling, at least {@value #MIN_CONCURRENT_TASKS}
		 * @throws IllegalArgumentException if any parameter is outside its accepted range
		 */
		public KademliaOptions {
			checkAlpha(alpha);
			checkK(k);
			checkReplacements(replacements);
			checkConcurrentTasks(concurrentTasks);
		}

		// The accepted ranges live here and nowhere else. Builder validates through these same checks
		// so that it can fail at the call site - pointing at the offending setter - while remaining
		// incapable of disagreeing with the constructor. Duplicating the rule in the Builder is how
		// the two previously drifted: the record gained ranges while the Builder still tested only for
		// positivity, so builder.k(2) was accepted and then threw from build(), far from its cause.

		static void checkAlpha(int alpha) {
			if (alpha < MIN_ALPHA || alpha > MAX_ALPHA)
				throw new IllegalArgumentException("Invalid alpha: " + alpha +
						", expected [" + MIN_ALPHA + ", " + MAX_ALPHA + "]");
		}

		static void checkK(int k) {
			if (k < MIN_K || k > MAX_K)
				throw new IllegalArgumentException("Invalid k: " + k +
						", expected [" + MIN_K + ", " + MAX_K + "]");
		}

		static void checkReplacements(int replacements) {
			if (replacements < MIN_REPLACEMENTS || replacements > MAX_REPLACEMENTS)
				throw new IllegalArgumentException("Invalid replacements: " + replacements +
						", expected [" + MIN_REPLACEMENTS + ", " + MAX_REPLACEMENTS + "]");
		}

		static void checkConcurrentTasks(int concurrentTasks) {
			if (concurrentTasks < MIN_CONCURRENT_TASKS)
				throw new IllegalArgumentException("Invalid concurrentTasks: " + concurrentTasks +
						", expected at least " + MIN_CONCURRENT_TASKS);
		}

		static KademliaOptions fromMap(@Nullable ConfigMap cm) {
			if (cm == null || cm.isEmpty())
				return new KademliaOptions(DEFAULT_ALPHA, DEFAULT_K, DEFAULT_REPLACEMENTS, DEFAULT_CONCURRENT_TASKS);

			// Read straight through to the canonical constructor: a value the operator wrote down is
			// reported as an error rather than quietly replaced by the default, which would leave a
			// node running parameters nobody asked for.
			return new KademliaOptions(cm.getInteger("alpha", DEFAULT_ALPHA),
					cm.getInteger("k", DEFAULT_K),
					cm.getInteger("replacements", DEFAULT_REPLACEMENTS),
					cm.getInteger("concurrentTasks", DEFAULT_CONCURRENT_TASKS));
		}

		Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("alpha", alpha);
			map.put("k", k);
			map.put("replacements", replacements);
			map.put("concurrentTasks", concurrentTasks);
			return map;
		}
	}

	/**
	 * The node's protective behaviors.
	 *
	 * @param spamThrottling         whether high-frequency requests from a single peer are throttled
	 * @param suspiciousNodeDetector whether peers behaving abnormally are identified and isolated
	 * @param developerMode          whether the node may participate over local/private addresses
	 */
	public record SecurityOptions(boolean spamThrottling, boolean suspiciousNodeDetector, boolean developerMode) {
		static SecurityOptions fromMap(@Nullable ConfigMap cm) {
			if (cm == null || cm.isEmpty())
				return new SecurityOptions(DEFAULT_SPAM_THROTTLING, DEFAULT_SUSPICIOUS_NODE_DETECTOR, DEFAULT_DEVELOPER_MODE);

			return new SecurityOptions(
					cm.getBoolean("spamThrottling", DEFAULT_SPAM_THROTTLING),
					cm.getBoolean("suspiciousNodeDetector", DEFAULT_SUSPICIOUS_NODE_DETECTOR),
					cm.getBoolean("developerMode", DEFAULT_DEVELOPER_MODE)
			);
		}

		Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("spamThrottling", spamThrottling);
			map.put("suspiciousNodeDetector", suspiciousNodeDetector);
			map.put("developerMode", developerMode);
			return map;
		}
	}

	/**
	 * Canonical constructor.
	 *
	 * @param vertx      the Vert.x instance the node runs on
	 * @param listen     the endpoints to bind to
	 * @param keyPair    the node's identity key pair
	 * @param dataDir    the directory for persistent data
	 * @param database   how to reach the persistence database
	 * @param kademlia   the Kademlia routing and lookup parameters
	 * @param bootstraps the entry points into the DHT network
	 * @param security   the node's protective behaviors
	 * @throws NullPointerException if any argument is null
	 */
	public NodeConfiguration {
		Objects.requireNonNull(vertx, "vertx");
		Objects.requireNonNull(listen, "listen");
		Objects.requireNonNull(keyPair, "keyPair");
		Objects.requireNonNull(dataDir, "dataDir");
		Objects.requireNonNull(database, "database");
		Objects.requireNonNull(kademlia, "kademlia");
		Objects.requireNonNull(bootstraps, "bootstraps");
		Objects.requireNonNull(security, "security");
		bootstraps = Set.copyOf(bootstraps);
	}

	private static @Nullable String emptyToNull(@Nullable String s) {
		return s == null || s.isEmpty() ? null : s;
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
	 * This static factory method deserializes a configuration from a Map structure.
	 * <p>
	 * The map must be a complete configuration, since there is no builder to carry anything it
	 * leaves out; in particular it must name a {@code privateKey} and at least one address family.
	 * A Vert.x instance is taken from the calling context - use {@link #builder()} with
	 * {@link Builder#vertx(Vertx)} when there is none.
	 *
	 * @param map the map containing configuration data, the map must not be null or empty
	 * @return a new {@link NodeConfiguration} instance
	 * @throws NullPointerException     if the map is null
	 * @throws IllegalArgumentException if the map is empty, required fields are missing, or values are invalid
	 * @throws IllegalStateException    if the map does not form a valid configuration, or if there is
	 *                                  no Vert.x instance in the calling context
	 */
	public static NodeConfiguration fromMap(Map<String, Object> map) {
		Objects.requireNonNull(map, "Configuration map must not be null");
		if (map.isEmpty())
			throw new IllegalArgumentException("Configuration map is empty");

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
		// A ConfigMap rather than a plain map, because its put() drops null values instead of
		// storing them: the optional settings below are simply absent when unset, which is what a
		// reader - and a YAML document - expects.
		ConfigMap map = new ConfigMap();
		map.putAll(listen.toMap());
		map.put("privateKey", Base58.encode(keyPair.privateKey().bytes()));
		// Written as a string, not as a Path. The map is meant to survive a round trip through YAML,
		// and a serializer given a Path writes it as a file: URI which reads back as a relative
		// directory literally named "file:".
		map.put("dataDir", dataDir.toString());
		map.put("database", database.toMap());
		map.put("kademlia", kademlia.toMap());
		map.put("bootstraps", bootstrapsToList(bootstraps));
		map.put("security", security.toMap());
		return map;
	}

	private static @Nullable List<List<Object>> bootstrapsToList(Collection<NodeInfo> bootstraps) {
		if (!bootstraps.isEmpty()) {
			List<List<Object>> lst = new ArrayList<>();
			bootstraps.forEach(n -> {
				List<Object> ni = new ArrayList<>();
				ni.add(n.getId().toString());
				String host4 = n.getHost4();
				if (host4 != null) {
					ni.add(host4);
					ni.add(n.getPort4());
				}
				String host6 = n.getHost6();
				if (host6 != null) {
					ni.add(host6);
					ni.add(n.getPort6());
				}
				lst.add(ni);
			});
			return lst;
		}

		return null;
	}

	private static Set<NodeInfo> bootstrapsFromList(@Nullable List<List<Object>> lst) {
		if (lst == null || lst.isEmpty())
			return Collections.emptySet();

		Set<NodeInfo> set = new HashSet<>();
		lst.forEach(b -> {
			int size = b.size();
			if (size != 3 && size != 5)
				throw new IllegalArgumentException("Invalid bootstrap node entry size: " + size + ". Expected 3 or 5 fields.");

			try {
				Id id = Id.of((String) b.get(0));

				// Resolve each (host, port) pair and route it to its address family, so the
				// declared order is irrelevant and a single address may be IPv4 or IPv6.
				InetSocketAddress addr4 = null;
				InetSocketAddress addr6 = null;
				for (int i = 1; i + 1 < size; i += 2) {
					InetSocketAddress sa = new InetSocketAddress((String) b.get(i), (int) b.get(i + 1));
					if (sa.getAddress() instanceof java.net.Inet4Address) {
						if (addr4 != null)
							throw new IllegalArgumentException("Duplicate IPv4 address found in bootstrap node: " + sa.getAddress());
						addr4 = sa;
					} else {
						if (addr6 != null)
							throw new IllegalArgumentException("Duplicate IPv6 address found in bootstrap node: " + sa.getAddress());
						addr6 = sa;
					}
				}

				set.add(NodeInfo.of(id, addr4, addr6));
			} catch (Exception e) {
				throw new IllegalArgumentException("Invalid bootstrap node entry: " + b + ", " + e.getMessage(), e);
			}
		});

		return set;
	}

	// One rule, one message: the builder and the options record both refuse a URI here, so a caller
	// cannot be told two different things about the same unsupported driver.
	private static void checkDatabaseUri(String uri) {
		if (!uri.startsWith("postgresql://") && !uri.startsWith("jdbc:sqlite:"))
			throw new IllegalArgumentException("Unsupported database URI: " + uri +
					". Only PostgreSQL and SQLite are supported.");
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
		/**
		 * The IPv4 address for the DHT node.
		 * DHT support for IPv4 is disabled if both {@code host4} and
		 * {@code networkInterface4} are null or empty.
		 */
		private @Nullable String host4;
		/**
		 * The network interface used by the DHT node for IPv4 communications.
		 * DHT support for IPv4 is disabled if both {@code host4} and
		 * {@code networkInterface4} are null or empty.
		 */
		private @Nullable String networkInterface4;
		/**
		 * The IPv6 address for the DHT node.
		 * DHT support for IPv6 is disabled if both {@code host6} and
		 * {@code networkInterface6} are null or empty.
		 */
		private @Nullable String host6;
		/**
		 * The network interface used by the DHT node for IPv6 communications.
		 * DHT support for IPv6 is disabled if both {@code host6} and
		 * {@code networkInterface6} are null or empty.
		 */
		private @Nullable String networkInterface6;
		/**
		 * The port number for the DHT node.
		 */
		private int port = DEFAULT_DHT_PORT;
		/**
		 * The node's key pair.
		 */
		private Signature.@Nullable KeyPair keyPair;
		/**
		 * Path to the directory for persistent DHT data storage. Defaults to {@link #defaultDataDir()};
		 * a node always needs one, since the routing table caches and the database file live under it.
		 */
		private Path dataDir = defaultDataDir();
		/**
		 * Database storage URI for the node.
		 */
		private String databaseUri = DEFAULT_DATABASE_URI;
		/**
		 * Database connection pool size.
		 */
		private int databasePoolSize = 0;
		/**
		 * Database schema name. Available for PostgreSQL only
		 */
		private @Nullable String databaseSchemaName = null;

		private int alpha = DEFAULT_ALPHA;

		private int k = DEFAULT_K;

		private int replacements = DEFAULT_REPLACEMENTS;

		private int concurrentTasks = DEFAULT_CONCURRENT_TASKS;

		/**
		 * Set of bootstrap nodes for joining the DHT network.
		 */
		private final Set<NodeInfo> bootstraps = new HashSet<>();

		/**
		 * Whether spam throttling is enabled for this node.
		 */
		private boolean spamThrottling = true;

		/**
		 * Whether suspicious node detection is enabled for this node.
		 */
		private boolean suspiciousNodeDetector = true;

		/**
		 * Whether developer mode is enabled for this node.
		 */
		private boolean developerMode = false;

		/**
		 * Constructs a new Builder with default settings.
		 */
		protected Builder() {
			vertx = Vertx.currentContext() != null ? Vertx.currentContext().owner() : null;
		}

		/**
		 * The directory a node uses when neither the caller nor the configuration document names one.
		 *
		 * @return the per-user default data directory
		 */
		public static Path defaultDataDir() {
			return FileUtils.getUserDataDir().resolve("boson/node");
		}

		/**
		 * Set the Vert.x instance to be used by the node.
		 *
		 * @param vertx the Vert.x instance (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if vertx is null
		 */
		public Builder vertx(Vertx vertx) {
			Objects.requireNonNull(vertx, "Vert.x instance must not be null");
			this.vertx = vertx;
			return this;
		}

		/**
		 * Automatically detects and sets the first available unicast IPv4 address for the node.
		 *
		 * @return this Builder for chaining
		 * @throws IllegalStateException if no suitable IPv4 address is found
		 */
		public Builder autoHost4() {
			InetAddress addr = AddressUtils.getDefaultRouteAddress(Inet4Address.class);
			if (addr == null)
				throw new IllegalStateException("No available IPv4 address");

			return address4(addr);
		}

		/**
		 * Automatically detects and sets the first available unicast IPv6 address for the node.
		 *
		 * @return this Builder for chaining
		 * @throws IllegalStateException if no suitable IPv6 address is found
		 */
		public Builder autoHost6() {
			InetAddress addr = AddressUtils.getDefaultRouteAddress(Inet6Address.class);
			if (addr == null)
				throw new IllegalStateException("No available IPv6 address");

			return address6(addr);
		}

		/**
		 * Automatically detects and sets both IPv4 and IPv6 addresses for the node.
		 *
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
				address4(addr4);

			if (addr6 != null)
				address6(addr6);

			return this;
		}

		/**
		 * Set the IPv4 address for the DHT node.
		 *
		 * @param host the string host name or IPv4 address (must not be null)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if the host is not a valid IPv4 address
		 * @throws NullPointerException     if the host is null
		 */
		public Builder host4(String host) {
			Objects.requireNonNull(host, "IPv4 host must not be null");

			try {
				return address4(InetAddress.getByName(host));
			} catch (UnknownHostException e) {
				throw new IllegalArgumentException("Invalid IPv4 host name or address: " + host, e);
			}
		}

		/**
		 * Set the IPv4 address for the DHT node.
		 *
		 * @param addr the IPv4 InetAddress (must not be null)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if addr is not an unicast IPv4 address
		 * @throws NullPointerException     if addr is null
		 */
		public Builder address4(InetAddress addr) {
			Objects.requireNonNull(addr, "IPv4 address must not be null");
			if (!AddressUtils.isAnyUnicast(addr))
				throw new IllegalArgumentException("The IPv4 address is not a unicast address: " + addr);

			if (addr instanceof Inet4Address)
				this.host4 = addr.getHostAddress();
			else
				throw new IllegalArgumentException("The provided address is not an IPv4 address: " + addr);

			return this;
		}

		/**
		 * Sets the value of the network interface to be used for IPv4.
		 *
		 * @param networkInterface the name or identifier of the network interface. Must not be null.
		 * @return the builder instance for method chaining.
		 * @throws NullPointerException if the networkInterface parameter is null.
		 */
		public Builder networkInterface4(String networkInterface) {
			Objects.requireNonNull(networkInterface, "IPv4 network interface must not be null");
			this.networkInterface4 = networkInterface;
			return this;
		}

		/**
		 * Set the IPv6 address for the DHT node.
		 *
		 * @param host the string host name or IPv6 address (must not be null)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if the host is not a valid IPv6 address
		 * @throws NullPointerException     if the host is null
		 */
		public Builder host6(String host) {
			Objects.requireNonNull(host, "IPv6 host must not be null");

			try {
				return address6(InetAddress.getByName(host));
			} catch (IOException | IllegalArgumentException e) {
				throw new IllegalArgumentException("Invalid IPv6 host name or address: " + host, e);
			}
		}

		/**
		 * Set the IPv6 address for the DHT node.
		 *
		 * @param addr the IPv6 InetAddress (must not be null)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if addr is not an unicast IPv6 address
		 * @throws NullPointerException     if addr is null
		 */
		public Builder address6(InetAddress addr) {
			Objects.requireNonNull(addr, "IPv6 address must not be null");
			if (!AddressUtils.isAnyUnicast(addr))
				throw new IllegalArgumentException("The IPv6 address is not a unicast address: " + addr);

			if (addr instanceof Inet6Address)
				this.host6 = addr.getHostAddress();
			else
				throw new IllegalArgumentException("The provided address is not an IPv6 address: " + addr);

			return this;
		}

		/**
		 * Sets the value of the network interface to be used for IPv6.
		 *
		 * @param networkInterface the name of the IPv6 network interface to set; must not be null
		 * @return the Builder instance for method chaining
		 * @throws NullPointerException if the provided networkInterface is null
		 */
		public Builder networkInterface6(String networkInterface) {
			Objects.requireNonNull(networkInterface, "IPv6 network interface must not be null");
			this.networkInterface6 = networkInterface;
			return this;
		}

		/**
		 * Set the DHT listen port. IPv4 and IPv6 networks will use the same port.
		 *
		 * @param port the port to listen (must be 1-65535)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if port is not in the valid range
		 */
		public Builder port(int port) {
			if (port <= 0 || port > 65535)
				throw new IllegalArgumentException("Invalid DHT port: " + port + ". Port must be between 1 and 65535.");

			this.port = port;
			return this;
		}

		/**
		 * Generates a new random private key for the node.
		 *
		 * @return this Builder for chaining
		 */
		public Builder generateKeyPair() {
			this.keyPair = Signature.KeyPair.random();
			return this;
		}

		/**
		 * Set the node's private key from a raw byte array.
		 *
		 * @param privateKey the private key bytes (must be 64 bytes)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if the key is not 64 bytes
		 */
		public Builder privateKey(byte[] privateKey) {
			Objects.requireNonNull(privateKey, "Private key must not be null");
			this.keyPair = Signature.KeyPair.fromPrivateKey(privateKey);
			return this;
		}

		/**
		 * Set the node's private key from a Base58-encoded string.
		 *
		 * @param privateKey the Base58-encoded or hex-encoded private key string (must not be null)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if the key is not 64 bytes when decoded
		 * @throws NullPointerException     if privateKey is null
		 */
		public Builder privateKey(String privateKey) {
			Objects.requireNonNull(privateKey, "Private key must not be null");
			byte[] key = privateKey.startsWith("0x") ?
					Hex.decode(privateKey, 2, privateKey.length() - 2) :
					Base58.decode(privateKey);
			this.keyPair = Signature.KeyPair.fromPrivateKey(key);
			return this;
		}

		/**
		 * Checks if a private key is present.
		 *
		 * @return true if a private key exists, false otherwise.
		 */
		public boolean hasKeyPair() {
			return keyPair != null;
		}

		/**
		 * Set the storage path for DHT persistent data using a string path.
		 *
		 * @param dir the string path (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if dir is null
		 */
		public Builder dataDir(String dir) {
			Objects.requireNonNull(dir, "Data directory must not be null");
			return dataDir(Paths.get(dir));
		}

		/**
		 * Set the storage path for DHT persistent data using a File object.
		 *
		 * @param path the File pointing to the storage directory (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if path is null
		 */
		public Builder dataDir(File path) {
			Objects.requireNonNull(path, "Data directory must not be null");
			dataDir(path.toPath());
			return this;
		}

		/**
		 * Set the storage path for DHT persistent data using a Path.
		 *
		 * @param path the Path to the storage directory (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if path is null
		 */
		public Builder dataDir(Path path) {
			Objects.requireNonNull(path, "Data directory must not be null");
			this.dataDir = FileUtils.normalizePath(path);
			return this;
		}

		/**
		 * Returns the data directory this builder would use, so that a caller can report the effective
		 * location without building the configuration first.
		 *
		 * @return the data directory, never null
		 */
		public Path dataDir() {
			return dataDir;
		}

		/**
		 * Set the database URI for the node.
		 *
		 * @param uri      the database URI (must not be null)
		 * @param poolSize the database connection pool size
		 * @return this Builder for chaining
		 * @throws NullPointerException     if storageURI is null
		 * @throws IllegalArgumentException if the URI is not supported or the pool size is invalid
		 */
		public Builder database(String uri, int poolSize) {
			databaseUri(uri);
			databasePoolSize(poolSize);
			return this;
		}

		/**
		 * Set the database URI for the node.
		 *
		 * @param uri the database URI (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException     if storageURI is null
		 * @throws IllegalArgumentException if the URI is not supported or the pool size is invalid
		 */
		public Builder databaseUri(String uri) {
			Objects.requireNonNull(uri, "Database URI must not be null");
			checkDatabaseUri(uri);
			this.databaseUri = uri;
			return this;
		}

		/**
		 * Set the database connection pool size.
		 *
		 * @param poolSize the connection pool size (must be non-negative)
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if the pool size is negative
		 */
		public Builder databasePoolSize(int poolSize) {
			if (poolSize < 0)
				throw new IllegalArgumentException("Invalid database pool size: " + poolSize + ". Pool size must be non-negative.");
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
		 * It will be ignored for SQLite databases.
		 *
		 * @param schema the name of the database schema
		 * @return the builder instance for method chaining
		 * @throws IllegalArgumentException if the schema name does not match the
		 *                                  required pattern or exceeds the maximum length
		 */
		public Builder databaseSchemaName(@Nullable String schema) {
			this.databaseSchemaName = SqlSafety.validateSchema(schema);
			return this;
		}

		/**
		 * Sets the Kademlia concurrency parameter: how many nodes a lookup queries in parallel.
		 *
		 * @param alpha the concurrency parameter, in
		 *              [{@value KademliaOptions#MIN_ALPHA}, {@value KademliaOptions#MAX_ALPHA}]
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if alpha is outside its accepted range
		 */
		public Builder alpha(int alpha) {
			KademliaOptions.checkAlpha(alpha);
			this.alpha = alpha;
			return this;
		}

		/**
		 * Sets the Kademlia bucket size.
		 *
		 * @param k the bucket size, in [{@value KademliaOptions#MIN_K}, {@value KademliaOptions#MAX_K}]
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if k is outside its accepted range
		 */
		public Builder k(int k) {
			KademliaOptions.checkK(k);
			this.k = k;
			return this;
		}

		/**
		 * Sets how many replacement entries each routing table bucket keeps.
		 *
		 * @param replacements the replacement count, in
		 *                     [{@value KademliaOptions#MIN_REPLACEMENTS}, {@value KademliaOptions#MAX_REPLACEMENTS}]
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if replacements is outside its accepted range
		 */
		public Builder replacements(int replacements) {
			KademliaOptions.checkReplacements(replacements);
			this.replacements = replacements;
			return this;
		}

		/**
		 * Sets the ceiling on concurrently running DHT tasks; further tasks are queued.
		 *
		 * @param concurrentTasks the concurrent task ceiling, at least
		 *                        {@value KademliaOptions#MIN_CONCURRENT_TASKS}
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if concurrentTasks is below its accepted minimum
		 */
		public Builder concurrentTasks(int concurrentTasks) {
			KademliaOptions.checkConcurrentTasks(concurrentTasks);
			this.concurrentTasks = concurrentTasks;
			return this;
		}

		/**
		 * Add a new bootstrap node to the configuration.
		 *
		 * @param id   the Id of the bootstrap node
		 * @param addr the string address of the bootstrap node
		 * @param port the port of the bootstrap node
		 * @return this Builder for chaining
		 */
		public Builder addBootstrap(String id, String addr, int port) {
			NodeInfo node = NodeInfo.of(Id.of(id), addr, port);
			this.bootstraps.add(node);
			return this;
		}

		/**
		 * Adds a dual-stack bootstrap node with both an IPv4 and an IPv6 address.
		 *
		 * @param id    the unique identifier of the bootstrap node
		 * @param addr4 the IPv4 address of the bootstrap node
		 * @param port4 the IPv4 port number of the bootstrap node
		 * @param addr6 the IPv6 address of the bootstrap node
		 * @param port6 the IPv6 port number of the bootstrap node
		 * @return the builder instance for chaining
		 */
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

		/**
		 * Adds a dual-stack bootstrap node with both an IPv4 and an IPv6 address.
		 *
		 * @param id    the Id of the bootstrap node
		 * @param addr4 the IPv4 address of the bootstrap node
		 * @param port4 the IPv4 port of the bootstrap node
		 * @param addr6 the IPv6 address of the bootstrap node
		 * @param port6 the IPv6 port of the bootstrap node
		 * @return this Builder for chaining
		 */
		public Builder addBootstrap(Id id, String addr4, int port4, String addr6, int port6) {
			NodeInfo node = NodeInfo.of(id, addr4, port4, addr6, port6);
			this.bootstraps.add(node);
			return this;
		}

		/**
		 * Add a new bootstrap node to the configuration.
		 *
		 * @param id   the Id of the bootstrap node
		 * @param addr the InetAddress of the bootstrap node
		 * @param port the port of the bootstrap node
		 * @return this Builder for chaining
		 */
		public Builder addBootstrap(Id id, InetAddress addr, int port) {
			NodeInfo node = NodeInfo.of(id, addr, port);
			this.bootstraps.add(node);
			return this;
		}

		/**
		 * Adds a dual-stack bootstrap node with both an IPv4 and an IPv6 address.
		 *
		 * @param id    the Id of the bootstrap node
		 * @param addr4 the IPv4 InetAddress of the bootstrap node
		 * @param port4 the IPv4 port of the bootstrap node
		 * @param addr6 the IPv6 InetAddress of the bootstrap node
		 * @param port6 the IPv6 port of the bootstrap node
		 * @return this Builder for chaining
		 */
		public Builder addBootstrap(Id id, InetAddress addr4, int port4, InetAddress addr6, int port6) {
			NodeInfo node = NodeInfo.of(id, addr4, port4, addr6, port6);
			this.bootstraps.add(node);
			return this;
		}

		/**
		 * Add a new bootstrap node to the configuration.
		 *
		 * @param id   the Id of the bootstrap node
		 * @param addr the InetSocketAddress of the bootstrap node
		 * @return this Builder for chaining
		 */
		public Builder addBootstrap(Id id, InetSocketAddress addr) {
			NodeInfo node = NodeInfo.of(id, addr);
			this.bootstraps.add(node);
			return this;
		}

		/**
		 * Adds a dual-stack bootstrap node with both an IPv4 and an IPv6 socket address.
		 *
		 * @param id    the Id of the bootstrap node
		 * @param addr4 the IPv4 InetSocketAddress of the bootstrap node, can be null
		 * @param addr6 the IPv6 InetSocketAddress of the bootstrap node, can be null
		 * @return this Builder for chaining
		 */
		public Builder addBootstrap(Id id, @Nullable InetSocketAddress addr4, @Nullable InetSocketAddress addr6) {
			NodeInfo node = NodeInfo.of(id, addr4, addr6);
			this.bootstraps.add(node);
			return this;
		}

		/**
		 * Add a new bootstrap node to the configuration.
		 *
		 * @param node the NodeInfo of the bootstrap node (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if the node is null
		 */
		public Builder addBootstrap(NodeInfo node) {
			Objects.requireNonNull(node, "Bootstrap node info must not be null");
			this.bootstraps.add(node);
			return this;
		}

		/**
		 * Add multiple bootstrap nodes to the configuration.
		 *
		 * @param nodes the collection of NodeInfo bootstrap nodes (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if the nodes parameter is null
		 */
		public Builder addBootstrap(Collection<NodeInfo> nodes) {
			Objects.requireNonNull(nodes, "Bootstrap nodes collection must not be null");
			this.bootstraps.addAll(nodes);
			return this;
		}

		/**
		 * Replaces the bootstrap nodes, discarding any added so far. Use
		 * {@link #addBootstrap(Collection)} to add to them instead.
		 *
		 * @param bootstraps the collection of NodeInfo bootstrap nodes (must not be null)
		 * @return this Builder for chaining
		 * @throws NullPointerException if the bootstraps parameter is null
		 */
		public Builder bootstraps(Collection<NodeInfo> bootstraps) {
			Objects.requireNonNull(bootstraps, "Bootstrap nodes collection must not be null");
			this.bootstraps.clear();
			this.bootstraps.addAll(bootstraps);
			return this;
		}

		/**
		 * Sets whether spam throttling is enabled for the node.
		 *
		 * @param enable true to enable spam throttling, false to disable
		 * @return this Builder for chaining
		 */
		public Builder spamThrottling(boolean enable) {
			this.spamThrottling = enable;
			return this;
		}

		/**
		 * Sets whether suspicious node detection is enabled for the node.
		 *
		 * @param enable true to enable suspicious node detection, false to disable
		 * @return this Builder for chaining
		 */
		public Builder suspiciousNodeDetector(boolean enable) {
			this.suspiciousNodeDetector = enable;
			return this;
		}

		/**
		 * Sets whether developer mode is enabled for the node.
		 *
		 * @param enable true to enable developer mode, false to disable
		 * @return this Builder for chaining
		 */
		public Builder developerMode(boolean enable) {
			this.developerMode = enable;
			return this;
		}

		/**
		 * Applies the settings in the given map on top of this builder.
		 * <p>
		 * The map is an OVERLAY, not a replacement: a setting the document does not name is left as
		 * whatever the caller already put on this builder, and a setting it does name wins. Both the
		 * DHT launcher and the shell layer command line arguments and a configuration file onto one
		 * builder, so a document that is silent about the port must not reset a port given on the
		 * command line - and a document that omits {@code privateKey} must still let the caller
		 * generate one afterwards.
		 * <p>
		 * The granularity is the setting a reader would think of as one choice:
		 * <ul>
		 *   <li>Each address family is one unit. A document that names {@code host4} or
		 *       {@code interface4} replaces both of this builder's IPv4 settings, so that a file's
		 *       {@code interface4} and a command line address do not combine into the "both
		 *       specified" error. IPv6 likewise.</li>
		 *   <li>Each named block - {@code database}, {@code kademlia}, {@code security} - is read
		 *       whole. Naming the block replaces every setting in it, and the keys the block leaves
		 *       out fall back to their own defaults rather than to this builder's values. A block
		 *       the document does not name at all is left alone.</li>
		 *   <li>{@code bootstraps} replaces the whole set, for the same reason.</li>
		 * </ul>
		 * Values are applied through this builder's setters, so a document is validated exactly as a
		 * programmatic caller would be.
		 * <p>
		 * The flat top-level settings are read here rather than in {@link NodeListenOptions} because
		 * the overlay is presence-based and spans keys: which of the four address settings to clear
		 * depends on which keys the document WROTE, not on their values, so it cannot live behind a
		 * record's value-only view of the document.
		 *
		 * @param map the configuration data; if null or empty the builder is returned unchanged
		 * @return this Builder for chaining
		 * @throws IllegalArgumentException if any value in the map is not valid for its setting
		 */
		public Builder fromMap(@Nullable Map<String, Object> map) {
			if (map == null || map.isEmpty())
				return this;

			ConfigMap m = new ConfigMap(map);

			if (m.containsKey("host4") || m.containsKey("interface4")) {
				this.host4 = null;
				this.networkInterface4 = null;

				String host = m.getString("host4", null);
				if (host != null && !host.isEmpty())
					host4(host);

				String nif = m.getString("interface4", null);
				if (nif != null && !nif.isEmpty())
					networkInterface4(nif);
			}

			if (m.containsKey("host6") || m.containsKey("interface6")) {
				this.host6 = null;
				this.networkInterface6 = null;

				String host = m.getString("host6", null);
				if (host != null && !host.isEmpty())
					host6(host);

				String nif = m.getString("interface6", null);
				if (nif != null && !nif.isEmpty())
					networkInterface6(nif);
			}

			if (m.containsKey("port"))
				port(m.getPort("port"));

			String sk = m.getString("privateKey", null);
			if (sk != null && !sk.isEmpty()) {
				try {
					privateKey(sk);
				} catch (Exception e) {
					throw new IllegalArgumentException("Invalid private key", e);
				}
			}

			if (m.containsKey("dataDir"))
				dataDir(m.getPath("dataDir"));

			if (m.containsKey("database")) {
				NodeDatabaseOptions database = NodeDatabaseOptions.fromMap(m.getObject("database"));
				databaseUri(database.uri());
				databasePoolSize(database.poolSize());
				databaseSchemaName(database.schema());
			}

			if (m.containsKey("kademlia")) {
				KademliaOptions kademlia = KademliaOptions.fromMap(m.getObject("kademlia"));
				alpha(kademlia.alpha());
				k(kademlia.k());
				replacements(kademlia.replacements());
				concurrentTasks(kademlia.concurrentTasks());
			}

			if (m.containsKey("security")) {
				SecurityOptions security = SecurityOptions.fromMap(m.getObject("security"));
				spamThrottling(security.spamThrottling());
				suspiciousNodeDetector(security.suspiciousNodeDetector());
				developerMode(security.developerMode());
			}

			if (m.containsKey("bootstraps"))
				bootstraps(bootstrapsFromList(m.getList("bootstraps")));

			return this;
		}

		/**
		 * Creates the {@link NodeConfiguration} instance with the current settings in this builder.
		 *
		 * @return the {@link NodeConfiguration} instance
		 * @throws IllegalStateException if the current settings do not form a valid configuration
		 *                               (for example, no Vert.x instance, no IPv4/IPv6 address, or no private key)
		 */
		public NodeConfiguration build() {
			if (keyPair == null)
				throw new IllegalStateException("The node's key pair must be provided.");

			if (vertx == null)
				vertx = Vertx.currentContext() != null ? Vertx.currentContext().owner() : null;

			// Deliberately not falling back to Vertx.vertx(): that would hand back a configuration
			// owning an event loop group nobody asked for and nobody closes.
			if (vertx == null)
				throw new IllegalStateException("Vert.x instance must be provided.");

			try {
				return new NodeConfiguration(vertx,
						new NodeListenOptions(host4, networkInterface4, host6, networkInterface6, port),
						keyPair,
						dataDir,
						new NodeDatabaseOptions(databaseUri, databasePoolSize, databaseSchemaName),
						new KademliaOptions(alpha, k, replacements, concurrentTasks),
						bootstraps,
						new SecurityOptions(spamThrottling, suspiciousNodeDetector, developerMode));
			} catch (NullPointerException | IllegalArgumentException e) {
				throw new IllegalStateException("Invalid NodeConfiguration: " + e.getMessage(), e);
			}
		}
	}
}