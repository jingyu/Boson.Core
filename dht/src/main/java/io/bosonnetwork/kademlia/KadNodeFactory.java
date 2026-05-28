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

package io.bosonnetwork.kademlia;

import io.bosonnetwork.BosonException;
import io.bosonnetwork.Node;
import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.NodeFactory;

/**
 * {@link NodeFactory} implementation that creates Kademlia DHT {@link KadNode} instances.
 * <p>
 * Registered as a service provider so that {@link Node#kadNode(NodeConfiguration)} can
 * discover it via {@link java.util.ServiceLoader} without a compile-time dependency from
 * {@code boson-api} on this module.
 */
public class KadNodeFactory implements NodeFactory {
	@Override
	public Node create(NodeConfiguration config) throws BosonException {
		return new KadNode(config);
	}
}
