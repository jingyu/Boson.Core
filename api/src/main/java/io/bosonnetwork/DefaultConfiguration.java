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
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.bosonnetwork.utils.AddressUtils;

/**
 * Default configuration implementation for the {@link Configuration} interface.
 */
public class DefaultConfiguration implements Configuration {
	private static final int DEFAULT_DHT_PORT = 39001;

	Inet4Address addr4;
	Inet6Address addr6;
	int port;

	byte[] privateKey;

	private Path accessControlsPath;
	private Path dataPath;
	private Set<NodeInfo> bootstraps;
	private Map<String, Map<String, Object>> services;

	private DefaultConfiguration() {
		this.bootstraps = new HashSet<>();
		this.services = new LinkedHashMap<>();
	}

	/**
	 * IPv4 address for the DHT node. Null IPv4 address will disable the DHT on IPv4.
	 *
	 * @return the InetAddress object of the IPv4 address.
	 */
	@Override
	public Inet4Address address4() {
		return addr4;
	}

	/**
	 * IPv6 address for the DHT node. Null IPv6 address will disable the DHT on IPv6.
	 *
	 * @return the InetAddress object of the IPv6 address.
	 */
	@Override
	public Inet6Address address6() {
		return addr6;
	}

	/**
	 * The port for the DHT node.
	 *
	 * @return the port number.
	 */
	@Override
	public int port() {
		return port;
	}

	@Override
	public byte[] privateKey() {
		return privateKey;
	}

	/**
	 * The access control lists directory.
	 *
	 * Null path will use default access control: allow all
	 *
	 * @return  a Path object point to the access control lists path.
	 */
	@Override
	public Path accessControlsPath() {
		return accessControlsPath;
	}

	/**
	 * If a Path that points to a writable directory is returned then the node info and
	 * the routing table will be persisted to that directory periodically and during shutdown.
	 *
	 * Null path will disable the DHT persist data.
	 *
	 * @return a Path object point to the storage path.
	 */
	@Override
	public Path dataPath() {
		return dataPath;
	}

	/**
	 * The bootstrap nodes for the new DHT node.
	 *
	 * @return a Collection for the bootstrap nodes.
	 */
	@Override
	public Collection<NodeInfo> bootstrapNodes() {
		return bootstraps;
	}

	/**
	 * The Boson services to be loaded within the DHT node.
	 *
	 * @return a Map object of service class(FQN) and service configuration.
	 */
	@Override
	public Map<String, Map<String, Object>> services() {
		return services;
	}

	/**
	 * The builder helper class to create a {@link Configuration} object.
	 */
	public static class Builder {
		private static final boolean AUTO_IPV4 = true;
		private static final boolean AUTO_IPV6 = false;

		private boolean autoAddr4 = AUTO_IPV4;
		private boolean autoAddr6 = AUTO_IPV6;

		private DefaultConfiguration conf;

		private DefaultConfiguration getConfiguration() {
			if (conf == null)
				conf = new DefaultConfiguration();

			return conf;
		}

		/**
		 * Set auto detect the IPv4 address. It will disable DHT on IPv4 if there isn't
		 * an available IPv4 address.
		 *
		 * @param auto true to auto detect the IPv4 address, false to disable.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setAutoAddress4(boolean auto) {
			autoAddr4 = auto;
			return this;
		}

		/**
		 * Set auto detect the IPv6 address. It will disable DHT on IPv6 if there isn't
		 * an available IPv6 address.
		 *
		 * @param auto true to auto detect the IPv6 address, false to disable.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setAutoAddress6(boolean auto) {
			autoAddr6 = auto;
			return this;
		}

		/**
		 * Set auto detect the IPv4 and IPv6 addresses. It will disable DHT on IPv4/6
		 * if there isn't an available IPv4/6 address.
		 *
		 * @param auto true to auto detect the IP addresses, false to disable.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setAutoAddress(boolean auto) {
			autoAddr4 = auto;
			autoAddr6 = auto;
			return this;
		}

		/**
		 * Set the IPv4 address for the DHT node.
		 *
		 * @param addr the string IPv4 address.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setAddress4(String addr) {
			Objects.requireNonNull(addr, "addr");

			try {
				return setAddress4(InetAddress.getByName(addr));
			} catch (UnknownHostException e) {
				throw new IllegalArgumentException("Invalid IPv4 address: " + addr, e);
			}
		}

		/**
		 * Set the IPv4 address for the DHT node.
		 *
		 * @param addr the IPv4 address.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setAddress4(InetAddress addr) {
			Objects.requireNonNull(addr, "addr");

			if (addr instanceof Inet4Address addr4)
				getConfiguration().addr4 = addr4;
			else
				throw new IllegalArgumentException("Invalid IPv4 address: " + addr);

			return this;
		}

		/**
		 * Set the IPv6 address for the DHT node.
		 *
		 * @param addr the string IPv6 address.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setAddress6(String addr) {
			Objects.requireNonNull(addr, "addr");

			try {
				return setAddress6(InetAddress.getByName(addr));
			} catch (IOException | IllegalArgumentException e) {
				throw new IllegalArgumentException("Invalid IPv6 address: " + addr, e);
			}
		}

		/**
		 * Set the IPv6 address for the DHT node.
		 *
		 * @param addr the IPv6 address.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setAddress6(InetAddress addr) {
			Objects.requireNonNull(addr, "addr");

			if (addr instanceof Inet6Address addr6)
				getConfiguration().addr6 = addr6;
			else
				throw new IllegalArgumentException("Invalid IPv6 address: " + addr);

			return this;
		}

		/**
		 * Set the DHT listen port. IPv4 and IPv6 networks will use same port.
		 *
		 * @param port the port to listen.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setPort(int port) {
			if (port <= 0 || port > 65535)
				throw new IllegalArgumentException("Invalid port: " + port);

			getConfiguration().port = port;
			return this;
		}

		public Builder setPrivateKey(byte[] privateKey) {
			if (privateKey != null && privateKey.length != 64)
				throw new IllegalArgumentException("Invalid private key length: " + privateKey.length);

			getConfiguration().privateKey = privateKey;
			return this;
		}

		/**
		 * Checks if there is an access control list path already been set.
		 *
		 * @return the Builder instance for method chaining.
		 */
		public boolean hasAccessControlsPath() {
			return getConfiguration().accessControlsPath != null;
		}

		/**
		 * Set the access control list path for the super node.
		 *
		 * @param path the string path.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setAccessControlsPath(String path) {
			return setAccessControlsPath(toPath(path));
		}

		public Builder setAccessControlsPath(File path) {
			setAccessControlsPath(path != null ? path.toPath() : null);
			return this;
		}

		/**
		 * Set the access control list path for the super node.
		 *
		 * @param path the Path object to the access control list directory.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setAccessControlsPath(Path path) {
			getConfiguration().accessControlsPath = path;
			return this;
		}

		/**
		 * Checks if there is a storage path already been set.
		 *
		 * @return the Builder instance for method chaining.
		 */
		public boolean hasDataPath() {
			return getConfiguration().dataPath != null;
		}

		/**
		 * Set the storage path for the DHT persistent data.
		 *
		 * @param path the string path.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setDataPath(String path) {
			return setDataPath(toPath(path));
		}

		/**
		 * Set the storage path for the DHT persistent data.
		 *
		 * @param path the File object to the storage path.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setDataPath(File path) {
			setDataPath(path != null ? path.toPath() : null);
			return this;
		}

		public Builder setDataPath(Path path) {
			getConfiguration().dataPath = path;
			return this;
		}

		/**
		 * Add a new bootstrap node.
		 *
		 * @param id the id of the bootstrap node.
		 * @param addr the string address of the bootstrap node.
		 * @param port the listen port of the bootstrap node.
		 * @return the Builder instance for method chaining.
		 */
		public Builder addBootstrap(String id, String addr, int port) {
			NodeInfo node = new NodeInfo(Id.of(id), addr, port);
			getConfiguration().bootstraps.add(node);
			return this;
		}

		/**
		 * Add a new bootstrap node.
		 *
		 * @param id the id of the bootstrap node.
		 * @param addr the address of the bootstrap node.
		 * @param port the listen port of the bootstrap node.
		 * @return the Builder instance for method chaining.
		 */
		public Builder addBootstrap(Id id, InetAddress addr, int port) {
			NodeInfo node = new NodeInfo(id, addr, port);
			getConfiguration().bootstraps.add(node);
			return this;
		}

		/**
		 * Add a new bootstrap node.
		 *
		 * @param id the id of the bootstrap node.
		 * @param addr the socket address of the bootstrap node.
		 * @return the Builder instance for method chaining.
		 */
		public Builder addBootstrap(Id id, InetSocketAddress addr) {
			NodeInfo node = new NodeInfo(id, addr);
			getConfiguration().bootstraps.add(node);
			return this;
		}

		/**
		 * Add a new bootstrap node.
		 *
		 * @param node the NodeInfo of the bootstrap node.
		 * @return the Builder instance for method chaining.
		 */
		public Builder addBootstrap(NodeInfo node) {
			Objects.requireNonNull(node, "node");
			getConfiguration().bootstraps.add(node);
			return this;
		}

		/**
		 * Add bootstrap nodes.
		 *
		 * @param nodes the NodeInfo collection of the bootstrap nodes.
		 * @return the Builder instance for method chaining.
		 */
		public Builder addBootstrap(Collection<NodeInfo> nodes) {
			Objects.requireNonNull(nodes, "nodes");
			getConfiguration().bootstraps.addAll(nodes);
			return this;
		}

		/**
		 * Add a new service and it's configuration data.
		 *
		 * @param clazz the full qualified class name of the service.
		 * @param configuration the service configuration data in Map object.
		 * @return the Builder instance for method chaining.
		 */
		public Builder addService(String clazz, Map<String, Object> configuration) {
			Objects.requireNonNull(clazz, "clazz");

			getConfiguration().services.put(clazz, Collections.unmodifiableMap(
					configuration == null || configuration.isEmpty() ?
					Collections.emptyMap() : configuration));
			return this;
		}

		/**
		 * Load the configuration data from the JSON file.
		 *
		 * @param file the string file path to load.
		 * @return the Builder instance for method chaining.
		 * @throws IOException if I/O error occurred during the loading.
		 */
		public Builder load(String file) throws IOException {
			Objects.requireNonNull(file, "file");
			Path configFile = toPath(file);
			return load(configFile);
		}

		public Builder load(File file) throws IOException {
			Objects.requireNonNull(file, "file");
			return load(file.toPath());
		}

		/**
		 * Load the configuration data from the JSON file.
		 *
		 * @param file the Path of file to load.
		 * @return the Builder instance for method chaining.
		 * @throws IOException if I/O error occurred during the loading.
		 */
		public Builder load(Path file) throws IOException {
			Objects.requireNonNull(file, "file");

			if (Files.notExists(file) || Files.isDirectory(file))
				throw new IllegalArgumentException("Invalid config file: " + file);

			try (InputStream in = Files.newInputStream(file)) {
				ObjectMapper mapper = new ObjectMapper();
				JsonNode root = mapper.readTree(in);

				boolean enabled = root.has("ipv4") ? root.get("ipv4").asBoolean() : AUTO_IPV4;
				setAutoAddress4(enabled);
				if (enabled) {
					if (root.has("address4"))
						setAddress4(root.get("address4").asText());
				}

				enabled = root.has("ipv6") ? root.get("ipv6").asBoolean() : AUTO_IPV6;
				setAutoAddress6(enabled);
				if (enabled) {
					if (root.has("address6"))
						setAddress6(root.get("address6").asText());
				}

				if (root.has("port"))
					setPort(root.get("port").asInt());

				if (root.has("accessControlsDir"))
					setAccessControlsPath(root.get("accessControlsDir").asText());

				if (root.has("dataDir"))
					setDataPath(root.get("dataDir").asText());

				if (root.has("bootstraps")) {
					JsonNode bootstraps = root.get("bootstraps");
					if (!bootstraps.isArray())
						throw new IOException("Config file error: bootstraps");

					for (JsonNode bootstrap : bootstraps) {
						if (!bootstrap.has("id"))
							throw new IOException("Config file error: bootstrap node id");

						if (!bootstrap.has("address"))
							throw new IOException("Config file error: bootstrap node address");

						if (!bootstrap.has("port"))
							throw new IOException("Config file error: bootstrap node port");

						try {
							Id id = Id.of(bootstrap.get("id").asText());
							InetAddress addr = InetAddress.getByName(bootstrap.get("address").asText());
							int port = bootstrap.get("port").asInt();

							addBootstrap(id, addr, port);
						} catch (Exception e) {
							throw new IOException("Config file error: bootstrap node - " +
									bootstrap.get("id").asText(), e);
						}
					}
				}

				if (root.has("services")) {
					JsonNode services = root.get("services");
					if (!services.isArray())
						throw new IOException("Config file error: services");

					for (JsonNode service : services) {
						if (!service.has("class"))
							throw new IOException("Config file error: service class name");

						String clazz = service.get("class").asText();

						Map<String, Object> configuration = null;
						if (service.has("configuration")) {
							JsonNode config = service.get("configuration");
							configuration = mapper.convertValue(config, new TypeReference<Map<String, Object>>(){});
						}

						addService(clazz, configuration);
					}
				}
			}

			return this;
		}

		/**
		 * Reset the configuration builder object to the initial state,
		 * clear all existing settings.
		 *
		 * @return the Builder instance for method chaining.
		 */
		public Builder clear() {
			autoAddr4 = AUTO_IPV4;
			autoAddr6 = AUTO_IPV6;
			conf = null;
			return this;
		}

		/**
		 * Creates the {@link Configuration} instance with current settings in this builder.
		 * After create the new {@link Configuration} instance, the builder will be reset to the
		 * initial state.
		 *
		 * @return the {@link Configuration} instance.
		 */
		public Configuration build() {
			DefaultConfiguration c = getConfiguration();
			conf = null;

			if (c.addr4 == null && autoAddr4)
				c.addr4 = (Inet4Address)AddressUtils.getAllAddresses().filter(Inet4Address.class::isInstance)
						.filter(AddressUtils::isAnyUnicast)
						.distinct()
						.findFirst().orElse(null);

			if (c.addr6 == null && autoAddr6)
				c.addr6 = (Inet6Address)AddressUtils.getAllAddresses().filter(Inet6Address.class::isInstance)
						.filter(AddressUtils::isAnyUnicast)
						.distinct()
						.findFirst().orElse(null);

			if (c.port <= 0 || c.port > 65535)
				c.port = DEFAULT_DHT_PORT;

			c.bootstraps = Collections.unmodifiableSet(
					c.bootstraps.isEmpty() ? Collections.emptySet() : c.bootstraps);

			c.services = Collections.unmodifiableMap(
					c.services.isEmpty() ? Collections.emptyMap() : c.services);

			return c;
		}

		private static Path toPath(String file) {
			if (file == null || file.isEmpty())
				return null;

			Path path = Path.of(file).normalize();
			if (path.startsWith("~"))
				path = Path.of(System.getProperty("user.home")).resolve(path.subpath(1, path.getNameCount()));
			else
				path = path.toAbsolutePath();

			return path;
		}
	}
}