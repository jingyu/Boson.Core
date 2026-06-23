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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * This class represents the node information in the Boson network; it contains
 * basic node network information. It supports both IPv4 and IPv6 addresses.
 */
public class NodeInfo {
	private final Id id;
	private final @Nullable InetSocketAddress addr4;
	private final @Nullable InetSocketAddress addr6;
	private int version;

	/**
	 * Construct a {@code NodeInfo} object.
	 *
	 * @param id the node id.
	 * @param sockAddr the node socket address, can be IPv4 or IPv6.
	 */
	public NodeInfo(Id id, InetSocketAddress sockAddr) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(sockAddr, "addr");
		if (sockAddr.getPort() <= 0 || sockAddr.getPort() > 65535)
			throw new IllegalArgumentException("Invalid port: " + sockAddr.getPort());

		this.id = id;
		if (sockAddr.getAddress() instanceof Inet4Address) {
			this.addr4 = sockAddr;
			this.addr6 = null;
		} else {
			this.addr4 = null;
			this.addr6 = sockAddr;
		}
	}

	/**
	 * Construct a {@code NodeInfo} object.
	 *
	 * @param id the node id.
	 * @param inetAddr the node IP address, can be IPv4 or IPv6.
	 * @param port the node port number.
	 */
	public NodeInfo(Id id, InetAddress inetAddr, int port) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(inetAddr, "addr");
		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port: " + port);

		this.id = id;
		InetSocketAddress sockAddr = new InetSocketAddress(inetAddr, port);
		if (inetAddr instanceof Inet4Address) {
			this.addr4 = sockAddr;
			this.addr6 = null;
		} else {
			this.addr4 = null;
			this.addr6 = sockAddr;
		}
	}

	/**
	 * Construct a {@code NodeInfo} object.
	 *
	 * @param id the node id.
	 * @param host the node host name or address string.
	 * @param port the node port number.
	 */
	public NodeInfo(Id id, String host, int port) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(host, "host");
		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port: " + port);

		this.id = id;
		InetSocketAddress sockAddr = new InetSocketAddress(host, port);
		if (sockAddr.getAddress() instanceof Inet4Address) {
			this.addr4 = sockAddr;
			this.addr6 = null;
		} else {
			this.addr4 = null;
			this.addr6 = sockAddr;
		}
	}

	/**
	 * Construct a {@code NodeInfo} object.
	 *
	 * @param id the node id.
	 * @param inetAddr the node raw IP address, can be IPv4 or IPv6.
	 * @param port the node port number.
	 */
	public NodeInfo(Id id, byte[] inetAddr, int port) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(inetAddr, "addr");
		if (port <= 0 || port > 65535)
			throw new IllegalArgumentException("Invalid port: " + port);

		InetSocketAddress sockAddr;
		try {
			sockAddr = new InetSocketAddress(InetAddress.getByAddress(inetAddr), port);
		} catch (UnknownHostException e) {
			throw new IllegalArgumentException("Invalid binary network address", e);
		}

		this.id = id;
		if (sockAddr.getAddress() instanceof Inet4Address) {
			this.addr4 = sockAddr;
			this.addr6 = null;
		} else {
			this.addr4 = null;
			this.addr6 = sockAddr;
		}
	}

	/**
	 * Construct a {@code NodeInfo} object with both IPv4 and IPv6 addresses.
	 *
	 * @param id the node id.
	 * @param sockAddr4 the IPv4 socket address, can be null.
	 * @param sockAddr6 the IPv6 socket address, can be null.
	 * @throws IllegalArgumentException if both addresses are null, or if the port is invalid.
	 */
	public NodeInfo(Id id, @Nullable InetSocketAddress sockAddr4, @Nullable InetSocketAddress sockAddr6) {
		Objects.requireNonNull(id, "id");
		if (sockAddr4 == null && sockAddr6 == null)
			throw new IllegalArgumentException("At least one address must be specified");

		if (sockAddr4 != null) {
			if (!(sockAddr4.getAddress() instanceof Inet4Address))
				throw new IllegalArgumentException("Invalid IPv4 address: " + sockAddr4.getAddress());

			if (sockAddr4.getPort() <= 0 || sockAddr4.getPort() > 65535)
				throw new IllegalArgumentException("Invalid port of IPv4 address: " + sockAddr4.getPort());
		}

		if (sockAddr6 != null) {
			if (!(sockAddr6.getAddress() instanceof Inet6Address))
				throw new IllegalArgumentException("Invalid IPv6 address: " + sockAddr6.getAddress());

			if (sockAddr6.getPort() <= 0 || sockAddr6.getPort() > 65535)
				throw new IllegalArgumentException("Invalid port of IPv6 address: " + sockAddr6.getPort());
		}

		this.id = id;
		this.addr4 = sockAddr4;
		this.addr6 = sockAddr6;
	}

	/**
	 * Copy constructor, create a {@code NodeInfo} from the given object.
	 *
	 * @param ni another node info object.
	 */
	protected NodeInfo(NodeInfo ni) {
		Objects.requireNonNull(ni, "ni");

		this.id = ni.id;
		this.addr4 = ni.addr4;
		this.addr6 = ni.addr6;
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
	 * Returns the IPv4 address if available, otherwise returns the IPv6 address.
	 *
	 * @return the socket address.
	 * @throws IllegalStateException if no address is available.
	 */
	public InetSocketAddress getAddress() {
		InetSocketAddress addr = addr4 != null ? addr4 : addr6;
		if (addr == null)
			throw new IllegalStateException("No address available");
		return addr;
	}

	/**
	 * Gets the IPv4 socket address of the node.
	 *
	 * @return the IPv4 socket address, or null if not available.
	 */
	public @Nullable InetSocketAddress getAddress4() {
		return addr4;
	}

	/**
	 * Gets the IPv6 socket address of the node.
	 *
	 * @return the IPv6 socket address, or null if not available.
	 */
	public @Nullable InetSocketAddress getAddress6() {
		return addr6;
	}

	/**
	 * Get the IP address of the node.
	 * Returns the IPv4 address if available, otherwise returns the IPv6 address.
	 *
	 * @return the IP address.
	 */
	public InetAddress getIpAddress() {
		return getAddress().getAddress();
	}

	/**
	 * Get the IPv4 address of the node.
	 *
	 * @return the IPv4 address, or null if not available.
	 */
	public @Nullable InetAddress getIpAddress4() {
		return addr4 != null ? addr4.getAddress() : null;
	}

	/**
	 * Get the IPv6 address of the node.
	 *
	 * @return the IPv6 address, or null if not available.
	 */
	public @Nullable InetAddress getIpAddress6() {
		return addr6 != null ? addr6.getAddress() : null;
	}

	/**
	 * Returns the String form of the IP address or hostname.
	 * This method will <b>not</b> attempt to do a reverse lookup.
	 * Returns the IPv4 address if available, otherwise returns the IPv6 address.
	 *
	 * @return the host name or string of IP address.
	 */
	public String getHost() {
		return getAddress().getHostString();
	}

	/**
	 * Returns the String form of the IPv4 address or hostname.
	 * This method will <b>not</b> attempt to do a reverse lookup.
	 *
	 * @return the host name or string of IPv4 address, or null if not available.
	 */
	public @Nullable String getHost4() {
		return addr4 != null ? addr4.getHostString() : null;
	}

	/**
	 * Returns the String form of the IPv6 address or hostname.
	 * This method will <b>not</b> attempt to do a reverse lookup.
	 *
	 * @return the host name or string of IPv6 address, or null if not available.
	 */
	public @Nullable String getHost6() {
		return addr6 != null ? addr6.getHostString() : null;
	}

	/**
	 * Get the port number of the node.
	 * Returns the IPv4 port if available, otherwise returns the IPv6 port.
	 *
	 * @return the port number.
	 */
	public int getPort() {
		return getAddress().getPort();
	}

	/**
	 * Get the IPv4 port number of the node.
	 *
	 * @return the port number, or -1 if not available.
	 */
	public int getPort4() {
		return addr4 != null ? addr4.getPort() : -1;
	}

	/**
	 * Get the IPv6 port number of the node.
	 *
	 * @return the port number, or -1 if not available.
	 */
	public int getPort6() {
		return addr6 != null ? addr6.getPort() : -1;
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
	 * Checks whether this node info conflicts with another, i.e. they share the same id
	 * <em>or</em> the same socket address. This is a partial match used to detect identity/address
	 * collisions, not full equality (see {@link #equals(Object)}).
	 *
	 * @param other another node info object to check
	 * @return true if this and {@code other} share the same id or the same address, false otherwise.
	 */
	public boolean matches(@Nullable NodeInfo other) {
		if (other != null)
			return this.id.equals(other.id) ||
					((addr4 != null || other.addr4 != null) && Objects.equals(addr4, other.addr4)) ||
					((addr6 != null || other.addr6 != null) && Objects.equals(addr6, other.addr6));
		else
			return false;
	}

	@Override
	public int hashCode() {
		return 0x6030A + Objects.hash(id, addr4, addr6);
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (o == this)
			return true;

		if (o instanceof NodeInfo that)
			return this.id.equals(that.id) &&
					Objects.equals(this.addr4, that.addr4) &&
					Objects.equals(this.addr6, that.addr6);

		return false;
	}

	@Override
	public String toString() {
		return id + "@" +
				(addr4 != null ? getHost() + ":" + getPort() : "") +
				(addr4 != null && addr6 != null ? "|" : "") +
				(addr6 != null ? " [" + getHost() + "]:" + getPort() : "");
	}
}