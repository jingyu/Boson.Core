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

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;

import io.vertx.core.Vertx;

import io.bosonnetwork.crypto.Signature;

/**
 * Configuration interface for customizing the initialization and behavior of a Boson DHT node.
 * <p>
 * Implementations of this interface provide various parameters that define how a DHT node is set up,
 * and operational features such as metrics and spam throttling.
 * </p>
 * <p>
 * This interface offers default implementations for all methods, allowing users to override only the
 * parameters they wish to customize. The configuration values affect the lifecycle and runtime behavior
 * of the DHT node.
 * </p>
 */
public interface NodeConfiguration {
	/**
	 * Provides the Vert.x instance to be used by the DHT node.
	 * <p>
	 * If {@code null} is returned, the node will create and manage its own default Vert.x instance.
	 * </p>
	 *
	 * @return the {@link Vertx} instance to use, or {@code null} to use the default instance.
	 */
	default Vertx vertx() {
		return null;
	}

	/**
	 * Specifies the IPv4 address to which the DHT node should bind.
	 * <p>
	 * Returning {@code null} disables IPv4 binding.
	 * </p>
	 *
	 * @return the IPv4 address as a string, or {@code null} if IPv4 is disabled.
	 */
	default String host4() {
		return null;
	}

	/**
	 * Specifies the IPv6 address to which the DHT node should bind.
	 * <p>
	 * Returning {@code null} disables IPv6 binding.
	 * </p>
	 *
	 * @return the IPv6 address as a string, or {@code null} if IPv6 is disabled.
	 */
	default String host6() {
		return null;
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
	default int port() {
		return 39001;
	}

	/**
	 * Provides the private key used by the DHT node for cryptographic operations.
	 * <p>
	 * If {@code null} is returned, the node will generate a random private key upon startup.
	 * </p>
	 *
	 * @return the private key, or {@code null} if no key is set.
	 */
	default Signature.PrivateKey privateKey() {
		return null;
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
	default Path dataDir() {
		return null;
	}

	/**
	 * Provides the URL for external storage used by the DHT node.
	 *
	 * @return the external storage URL as a string, or {@code null} if not configured.
	 */
	default String storageURI() {
		return null;
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
	default Collection<NodeInfo> bootstrapNodes() {
		return Collections.emptyList();
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
	default boolean enableMetrics() {
		return false;
	}

	/**
	 * Indicates whether spam throttling is enabled to mitigate excessive or malicious traffic.
	 *
	 * @return {@code true} if spam throttling is enabled; {@code false} otherwise.
	 */
	default boolean enableSpamThrottling() {
		return true;
	}

	/**
	 * Indicates whether suspicious node detection is enabled.
	 * <p>
	 * This feature helps identify and potentially isolate nodes exhibiting abnormal or malicious behavior.
	 * </p>
	 *
	 * @return {@code true} if suspicious node detection is enabled; {@code false} otherwise.
	 */
	default boolean enableSuspiciousNodeDetector() {
		return true;
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
	default boolean enableDeveloperMode() {
		return false;
	}

	/**
	 * Creates a new builder for constructing a {@link DefaultNodeConfiguration} instance.
	 *
	 * @return a new {@link DefaultNodeConfiguration.Builder} instance.
	 */
	static DefaultNodeConfiguration.Builder builder() {
		return new DefaultNodeConfiguration.Builder();
	}
}