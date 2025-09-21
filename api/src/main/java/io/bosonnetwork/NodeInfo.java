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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * THis class represent the node information in the Boson network, it contains
 * basic node network information.
 */
public class NodeInfo {
	private final Id id;
	private final InetSocketAddress addr;
	private int version;

	/**
	 * Construct a {@code NodeInfo} object.
	 *
	 * @param id the node id.
	 * @param addr the node socket address.
	 */
	public NodeInfo(Id id, InetSocketAddress addr) {
		if (id == null)
			throw new IllegalArgumentException("Invalid node id: null");

		if (addr == null)
			throw new IllegalArgumentException("Invalid socket address: null");

		if (addr.getPort() <= 0 || addr.getPort() > 65535)
			throw new IllegalArgumentException("Invalid port: " + addr.getPort());

		this.id = id;
		this.addr = addr;
	}

	/**
	 * Construct a {@code NodeInfo} object.
	 *
	 * @param id the node id.
	 * @param addr the node IP address.
	 * @param port the node port number.
	 */
	public NodeInfo(Id id, InetAddress addr, int port) {
		if (id == null)
			throw new IllegalArgumentException("Invalid node id: null");

		if (addr == null)
			throw new IllegalArgumentException("Invalid socket address: null");

		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port: " + port);

		this.id = id;
		this.addr = new InetSocketAddress(addr, port);
	}

	/**
	 * Construct a {@code NodeInfo} object.
	 *
	 * @param id the node id.
	 * @param host the node host name or address.
	 * @param port the node port number.
	 */
	public NodeInfo(Id id, String host, int port) {
		if (id == null)
			throw new IllegalArgumentException("Invalid node id: null");

		if (host == null)
			throw new IllegalArgumentException("Invalid socket address: null");

		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port: " + port);

		// TODO: not try to do the name resolution if the host is an host name
		this.addr = new InetSocketAddress(host, port);
		if (addr.isUnresolved())
			throw new IllegalArgumentException("Unknown host");

		this.id = id;
	}

	/**
	 * Construct a {@code NodeInfo} object.
	 *
	 * @param id the node id.
	 * @param addr the node raw IP address.
	 * @param port the node port number.
	 */
	public NodeInfo(Id id, byte[] addr, int port) {
		if (id == null)
			throw new IllegalArgumentException("Invalid node id: null");
		if (addr == null)
			throw new IllegalArgumentException("Invalid socket address: null");
		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port: " + port);

		try {
			this.addr = new InetSocketAddress(InetAddress.getByAddress(addr), port);
		} catch (UnknownHostException e) {
			throw new IllegalArgumentException("Invalid binary inet address", e);
		}

		this.id = id;
	}

	/**
	 * Copy constructor, create a {@code NodeInfo} from the given object.
	 *
	 * @param ni another node info object.
	 */
	protected NodeInfo(NodeInfo ni) {
		if (ni == null)
			throw new IllegalArgumentException("Invalid node info: null");

		this.id = ni.id;
		this.addr = ni.addr;
		this.version = ni.version;
	}

	/**
	 * Gets the node id.
	 *
	 * @return the node id.
	 */
	public Id getId() {
		return id;
	}

	/**
	 * Gets the socket address of the node.
	 *
	 * @return the socket address.
	 */
	public InetSocketAddress getAddress() {
		return addr;
	}

	/**
	 * Get the IP address of the node.
	 *
	 * @return the IP address.
	 */
	public InetAddress getIpAddress() {
		return addr.getAddress();
	}

	/**
	 * Returns the String form of the IP address or hostname.
	 * This method will <b>not</b> attempt to do a reverse lookup.
	 *
	 * @return the host name or string of IP address.
	 */
	public String getHost() {
		return addr.getHostString();
	}

	/**
	 * Get the port number of the node.
	 *
	 * @return the port number.
	 */
	public int getPort() {
		return addr.getPort();
	}

	/**
	 * Sets the node version number.
	 *
	 * @param version the version number.
	 */
	public void setVersion(int version) {
		this.version = version;
	}

	/**
	 * Gets the node version.
	 *
	 * @return the version number.
	 */
	public int getVersion() {
		return version;
	}

	/**
	 * Checks if the node information is identical with the other one.
	 *
	 * @param other another node info object to check
	 * @return true if the other node info object is identical with this, false otherwise.
	 */
	public boolean matches(NodeInfo other) {
		if (other != null)
			return this.id.equals(other.id) || this.addr.equals(other.addr);
		else
			return false;
	}

	@Override
	public int hashCode() {
		return 0x6030A + Objects.hash(id, addr, version);
	}

	@Override
	public boolean equals(Object o) {
		if (o == this)
			return true;

		if (o instanceof NodeInfo that)
			return this.id.equals(that.id) &&
					this.addr.equals(that.addr);

		return false;
	}

	@Override
	public String toString() {
		return id.toString() + "@" + getHost() + ":" + getPort();
	}
}