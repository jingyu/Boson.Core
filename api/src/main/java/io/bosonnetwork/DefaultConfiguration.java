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
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
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

	InetSocketAddress addr4;
	InetSocketAddress addr6;

	private File accessControlsPath;
	private File storagePath;
	private Set<NodeInfo> bootstraps;
	private Map<String, Map<String, Object>> services;

	private DefaultConfiguration() {
		this.bootstraps = new HashSet<>();
		this.services = new LinkedHashMap<>();
	}

	/**
	 * IPv4 address for the DHT node. Null IPv4 address will disable the DHT on IPv4.
	 *
	 * @return the InetSocketAddress object of the IPv4 address.
	 */
	@Override
	public InetSocketAddress IPv4Address() {
		return addr4;
	}

	/**
	 * IPv6 address for the DHT node. Null IPv6 address will disable the DHT on IPv6.
	 *
	 * @return the InetSocketAddress object of the IPv6 address.
	 */
	@Override
	public InetSocketAddress IPv6Address() {
		return addr6;
	}

	/**
	 * The access control lists directory.
	 *
	 * Null path will use default access control: allow all
	 *
	 * @return  a File object point to the access control lists path.
	 */
	@Override
	public File accessControlsPath() {
		return accessControlsPath;
	}

	/**
	 * If a Path that points to a writable directory is returned then the node info and
	 * the routing table will be persisted to that directory periodically and during shutdown.
	 *
	 * Null path will disable the DHT persist it's data.
	 *
	 * @return a File object point to the storage path.
	 */
	@Override
	public File storagePath() {
		return storagePath;
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

		private Inet4Address inetAddr4;
		private Inet6Address inetAddr6;
		private int port = DEFAULT_DHT_PORT;

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
		public Builder setAutoIPv4Address(boolean auto) {
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
		public Builder setAutoIPv6Address(boolean auto) {
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
		public Builder setAutoIPAddress(boolean auto) {
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
		public Builder setIPv4Address(String addr) {
			try {
				return setIPv4Address(addr != null ? InetAddress.getByName(addr) : null);
			} catch (IOException | IllegalArgumentException e) {
				throw new IllegalArgumentException("Invalid IPv4 address: " + addr, e);
			}
		}

		/**
		 * Set the IPv4 address for the DHT node.
		 *
		 * @param addr the IPv4 address.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setIPv4Address(InetAddress addr) {
			if (addr != null && !(addr instanceof Inet4Address))
				throw new IllegalArgumentException("Invalid IPv4 address: " + addr);

			this.inetAddr4 = (Inet4Address)addr;
			return this;
		}

		/**
		 * Set the IPv6 address for the DHT node.
		 *
		 * @param addr the string IPv6 address.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setIPv6Address(String addr) {
			try {
				return setIPv6Address(addr != null ? InetAddress.getByName(addr) : null);
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
		public Builder setIPv6Address(InetAddress addr) {
			if (addr != null && !(addr instanceof Inet6Address))
				throw new IllegalArgumentException("Invalid IPv6 address: " + addr);

			this.inetAddr6 = (Inet6Address)addr;
			return this;
		}

		/**
		 * Set the DHT listen port. IPv4 and IPv6 networks will use same port.
		 *
		 * @param port the port to listen.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setListeningPort(int port) {
			if (port <= 0 || port > 65535)
				throw new IllegalArgumentException("Invalid port: " + port);

			this.port = port;
			return this;
		}

		/**
		 * Checks if there is a access control list path already been set.
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
			return setAccessControlsPath(toFile(path));
		}

		/**
		 * Set the access control list path for the super node.
		 *
		 * @param path the File object to the access control list directory.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setAccessControlsPath(File path) {
			getConfiguration().accessControlsPath = path;
			return this;
		}

		/**
		 * Checks if there is a storage path already been set.
		 *
		 * @return the Builder instance for method chaining.
		 */
		public boolean hasStoragePath() {
			return getConfiguration().storagePath != null;
		}

		/**
		 * Set the storage path for the DHT persistent data.
		 *
		 * @param path the string path.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setStoragePath(String path) {
			return setStoragePath(toFile(path));
		}

		/**
		 * Set the storage path for the DHT persistent data.
		 *
		 * @param path the File object to the storage path.
		 * @return the Builder instance for method chaining.
		 */
		public Builder setStoragePath(File path) {
			getConfiguration().storagePath = path;
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
			if (node == null)
				throw new IllegalArgumentException("Invaild node info: null");

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
			if (nodes == null)
				throw new IllegalArgumentException("Invaild node info: null");

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
			if (clazz == null || clazz.isEmpty())
				throw new IllegalArgumentException("Invaild service class name");

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
			File configFile = toFile(file);
			return load(configFile);
		}

		/**
		 * Load the configuration data from the JSON file.
		 *
		 * @param file the File object to load.
		 * @return the Builder instance for method chaining.
		 * @throws IOException if I/O error occurred during the loading.
		 */
		public Builder load(File file) throws IOException {
			if (file == null || !file.exists() || file.isDirectory())
				throw new IllegalArgumentException("Invalid config file: " + String.valueOf(file));

			try (FileInputStream in = new FileInputStream(file)) {
				ObjectMapper mapper = new ObjectMapper();
				JsonNode root = mapper.readTree(in);

				boolean enabled = root.has("ipv4") ? root.get("ipv4").asBoolean() : AUTO_IPV4;
				setAutoIPv4Address(enabled);
				if (enabled) {
					if (root.has("address4"))
						setIPv4Address(root.get("address4").asText());
				}

				enabled = root.has("ipv6") ?  root.get("ipv6").asBoolean() : AUTO_IPV6;
				setAutoIPv6Address(enabled);
				if (enabled) {
					if (root.has("address6"))
						setIPv6Address(root.get("address6").asText());
				}

				if (root.has("port"))
					setListeningPort(root.get("port").asInt());

				if (root.has("accessControlsDir"))
					setAccessControlsPath(root.get("accessControlsDir").asText());

				if (root.has("dataDir"))
					setStoragePath(root.get("dataDir").asText());

				if (root.has("bootstraps")) {
					JsonNode bootstraps = root.get("bootstraps");
					if (!bootstraps.isArray())
						throw new IOException("Config file error: bootstaps");

					for (JsonNode bootstrap : bootstraps) {
						if (!bootstrap.has("id"))
							throw new IOException("Config file error: bootstap node id");

						if (!bootstrap.has("address"))
							throw new IOException("Config file error: bootstap node address");

						if (!bootstrap.has("port"))
							throw new IOException("Config file error: bootstap node port");

						try {
							Id id = Id.of(bootstrap.get("id").asText());
							InetAddress addr = InetAddress.getByName(bootstrap.get("address").asText());
							int port = bootstrap.get("port").asInt();

							addBootstrap(id, addr, port);
						} catch (Exception e) {
							throw new IOException("Config file error: bootstap node - " +
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

			inetAddr4 = null;
			inetAddr6 = null;
			port = DEFAULT_DHT_PORT;

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

			if (inetAddr4 == null && autoAddr4)
				inetAddr4 = (Inet4Address)AddressUtils.getAllAddresses().filter(Inet4Address.class::isInstance)
						.filter((a) -> AddressUtils.isAnyUnicast(a))
						.distinct()
						.findFirst().orElse(null);

			if (inetAddr6 == null && autoAddr6)
				inetAddr6 = (Inet6Address)AddressUtils.getAllAddresses().filter(Inet6Address.class::isInstance)
						.filter((a) -> AddressUtils.isAnyUnicast(a))
						.distinct()
						.findFirst().orElse(null);

			c.addr4 = inetAddr4 != null ? new InetSocketAddress(inetAddr4, port) : null;
			c.addr6 = inetAddr6 != null ? new InetSocketAddress(inetAddr6, port) : null;

			c.bootstraps = Collections.unmodifiableSet(
					(c.bootstraps == null || c.bootstraps.isEmpty()) ?
					Collections.emptySet() : c.bootstraps);

			c.services = Collections.unmodifiableMap(
					(c.services == null || c.services.isEmpty()) ?
					Collections.emptyMap() : c.services);

			return c;
		}

		private static File toFile(String file) {
			if (file == null || file.isEmpty())
				return null;

			return file.startsWith("~" + File.separator) || file.equals("~") ?
				new File(System.getProperty("user.home") + file.substring(1)) :
			    new File(file);
		}
	}
}