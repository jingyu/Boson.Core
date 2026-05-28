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

/**
 * Service provider interface for creating {@link Node} instances.
 * <p>
 * Implementations are discovered at runtime via the {@link java.util.ServiceLoader}
 * mechanism, which decouples the {@code boson-api} contract from the concrete node
 * implementation (e.g. the Kademlia DHT node in {@code boson-dht}). Providers register
 * themselves through a {@code META-INF/services/io.bosonnetwork.NodeFactory} entry, or a
 * {@code provides io.bosonnetwork.NodeFactory with ...} declaration when running on the
 * Java module path.
 *
 * @see Node#kadNode(NodeConfiguration)
 */
public interface NodeFactory {
	/**
	 * Creates and initializes a new {@link Node} instance using the provided configuration.
	 *
	 * @param config the node configuration
	 * @return an initialized {@link Node} instance
	 * @throws BosonException if the node cannot be initialized
	 */
	Node create(NodeConfiguration config) throws BosonException;
}
