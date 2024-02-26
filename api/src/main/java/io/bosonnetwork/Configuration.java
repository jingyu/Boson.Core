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
import java.net.InetSocketAddress;
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
	 * @return the InetSocketAddress object of the IPv4 address.
	 */
	default public InetSocketAddress IPv4Address() {
		return null;
	}

	/**
	 * IPv6 address for the DHT node. Null IPv6 address will disable the DHT on IPv6.
	 *
	 * @return the InetSocketAddress object of the IPv6 address.
	 */
	default public InetSocketAddress IPv6Address() {
		return null;
	}

	/**
	 * The access control lists directory.
	 *
	 * Null path will use default access control: allow all
	 *
	 * @return  a File object point to the access control lists path.
	 */
	default public File accessControlsPath() {
		return null;
	}

	/**
	 * If a Path that points to a writable directory is returned then the node info and
	 * the routing table will be persisted to that directory periodically and during shutdown.
	 *
	 * Null path will disable the DHT persist it's data.
	 *
	 * @return a File object point to the storage path.
	 */
	default public File storagePath() {
		return null;
	}

	/**
	 * The bootstrap nodes for the new DHT node.
	 *
	 * @return a Collection for the bootstrap nodes.
	 */
	default public Collection<NodeInfo> bootstrapNodes() {
		return Collections.emptyList();
	}

	/**
	 * The Boson services to be loaded within the DHT node.
	 *
	 * @return a Map object of service class(FQN) and service configuration.
	 */
	default public  Map<String, Map<String, Object>> services() {
		return Collections.emptyMap();
	}
}
