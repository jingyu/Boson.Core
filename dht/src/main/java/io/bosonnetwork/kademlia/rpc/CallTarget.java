/*
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

package io.bosonnetwork.kademlia.rpc;

/**
 * A node this node already knows something about from having dealt with it itself.
 * <p>
 * Implemented by the node types that carry local history - a routing table entry, a lookup candidate
 * seeded from one - so that {@link RpcCall} can read that history without knowing which layer produced
 * the target. A plain {@code NodeInfo} implements none of this, and that is the point: it is a node
 * somebody described to us, about which we know nothing of our own.
 * </p>
 * <p>
 * <b>The distinction is who established what is claimed here.</b> Both answers come from our own
 * dealings with the node, never from what a third party said about it, which is why they are worth
 * acting on at all.
 * </p>
 */
public interface CallTarget {
	/**
	 * Whether this node has answered us, rather than merely been described to us.
	 *
	 * @return true if we have had a reply from it ourselves.
	 */
	boolean isReachable();

	/**
	 * The round-trip time to expect from this node, in milliseconds.
	 * <p>
	 * <b>A non-positive value means we have no basis for one</b>, and the RPC layer then substitutes its
	 * own adaptive estimate - see {@code RpcCall.setExpectedRttIfAbsent}. Answering with a made-up
	 * constant instead of saying "unknown" silently disables that estimate, which is what the sampler is
	 * there to provide for exactly the nodes we have never timed.
	 * </p>
	 *
	 * @return the expected RTT in milliseconds, or a non-positive value if unknown.
	 */
	int getRTT();
}
