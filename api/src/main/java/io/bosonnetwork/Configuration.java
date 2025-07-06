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

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * The Configuration interface used to customize the Boson DHT node upon initialization.
 * Configuration contains various parameters used to setup DHT node.
 */
public interface Configuration {
	/**
	 * IPv4 address for the DHT node. Null IPv4 address will disable the DHT on IPv4.
	 *
	 * @return the InetAddress object of the IPv4 address.
	 */
	default Inet4Address address4() {
		return null;
	}

	/**
	 * IPv6 address for the DHT node. Null IPv6 address will disable the DHT on IPv6.
	 *
	 * @return the InetAddress object of the IPv6 address.
	 */
	default Inet6Address address6() {
		return null;
	}

	/**
	 * The port for the DHT node.
	 *
	 * @return the port number.
	 */
	default int port() {
		return 39001;
	}

	/**
	 * The DHT node private key. if no key is provided then the DHT node will generate a random key.
	 *
	 * @return the private key or null if not set.
	 */
	default byte[] privateKey() {
		return null;
	}

	/**
	 * The access control lists directory.
	 * Null path will use default access control: allow all
	 *
	 * @return  a Path object point to the access control lists path.
	 */
	default Path accessControlsPath() {
		return null;
	}

	/**
	 * If a Path that points to a writable directory is returned then the node info and
	 * the routing table will be persisted to that directory periodically and during shutdown.
	 * Null path will disable the DHT persist data.
	 *
	 * @return a Path object point to the storage path.
	 */
	default Path dataPath() {
		return null;
	}

	/**
	 * The bootstrap nodes for the new DHT node.
	 *
	 * @return a Collection for the bootstrap nodes.
	 */
	default Collection<NodeInfo> bootstrapNodes() {
		return Collections.emptyList();
	}

	/**
	 * The Boson services to be loaded within the DHT node.
	 *
	 * @return a Map object of service class(FQN) and service configuration.
	 */
	default Map<String, Map<String, Object>> services() {
		return Collections.emptyMap();
	}
}