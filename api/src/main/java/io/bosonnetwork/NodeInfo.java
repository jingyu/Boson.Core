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
import java.net.StandardProtocolFamily;
import java.net.UnknownHostException;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * This class represents the node information in the Boson network; it contains
 * basic node network information. It supports both IPv4 and IPv6 addresses.
 * <p>
 * A node is identified by its {@link Id} and may carry an IPv4 address, an IPv6 address, or both.
 * The generic accessors ({@link #getAddress()}, {@link #getHost()}, {@link #getPort()},
 * {@link #getIpAddress()}) prefer the IPv4 address and fall back to IPv6; use the family-specific
 * accessors to target a particular protocol family.
 * <p>
 * Instances are immutable: the id and addresses define {@link #equals(Object)}/{@link #hashCode()},
 * and the preferred protocol family is fixed at construction. {@link #narrowDown(StandardProtocolFamily)}
 * returns a new instance rather than mutating. Immutable instances are safe to share across threads.
 */
public class NodeInfo {
	private final Id id;
	private final @Nullable InetSocketAddress addr4;
	private final @Nullable InetSocketAddress addr6;
	private final StandardProtocolFamily defaultProtocolFamily;

	private NodeInfo(Id id, @Nullable InetSocketAddress sockAddr4, @Nullable InetSocketAddress sockAddr6) {
		Objects.requireNonNull(id, "id");
		if (sockAddr4 == null && sockAddr6 == null)
			throw new IllegalArgumentException("At least one address must be specified");

		if (sockAddr4 != null) {
			if (!(sockAddr4.getAddress() instanceof Inet4Address))
				throw new IllegalArgumentException("Invalid IPv4 address: " + sockAddr4.getAddress());

			if (sockAddr4.getPort() == 0)
				throw new IllegalArgumentException("Invalid port of IPv4 address: 0");
		}

		if (sockAddr6 != null) {
			if (!(sockAddr6.getAddress() instanceof Inet6Address))
				throw new IllegalArgumentException("Invalid IPv6 address: " + sockAddr6.getAddress());

			if (sockAddr6.getPort() == 0)
				throw new IllegalArgumentException("Invalid port of IPv6 address: 0");
		}

		this.id = id;
		this.addr4 = sockAddr4;
		this.addr6 = sockAddr6;

		// Generic accessors prefer IPv4 and fall back to IPv6. For a dual-stack node both addresses
		// are present, so default to IPv4; for a single-stack node default to the available family.
		this.defaultProtocolFamily = sockAddr4 != null ? StandardProtocolFamily.INET : StandardProtocolFamily.INET6;
	}

	protected NodeInfo(Id id, InetSocketAddress sockAddr) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(sockAddr, "sockAddr");
		if (sockAddr.isUnresolved())
			throw new IllegalArgumentException("Unresolved address: " + sockAddr);
		if (sockAddr.getPort() == 0)
			throw new IllegalArgumentException("Invalid port: 0");

		this.id = id;
		if (sockAddr.getAddress() instanceof Inet4Address) {
			this.addr4 = sockAddr;
			this.addr6 = null;
			this.defaultProtocolFamily = StandardProtocolFamily.INET;
		} else {
			this.addr4 = null;
			this.addr6 = sockAddr;
			this.defaultProtocolFamily = StandardProtocolFamily.INET6;
		}
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
		this.defaultProtocolFamily = ni.defaultProtocolFamily;
	}

	/**
	 * Construct a {@code NodeInfo} object.
	 *
	 * @param id the node id.
	 * @param sockAddr the node socket address, can be IPv4 or IPv6.
	 */
	public static NodeInfo of(Id id, InetSocketAddress sockAddr) {
		return new NodeInfo(id, sockAddr);
	}

	/**
	 * Construct a {@code NodeInfo} object.
	 *
	 * @param id the node id.
	 * @param inetAddr the node IP address, can be IPv4 or IPv6.
	 * @param port the node port number.
	 */
	public static NodeInfo of(Id id, InetAddress inetAddr, int port) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(inetAddr, "inetAddr");
		return new NodeInfo(id, new InetSocketAddress(inetAddr, port));
	}

	/**
	 * Construct a {@code NodeInfo} object.
	 *
	 * @param id the node id.
	 * @param host the node host name or address string.
	 * @param port the node port number.
	 */
	public static NodeInfo of(Id id, String host, int port) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(host, "host");
		return new NodeInfo(id, new InetSocketAddress(host, port));
	}

	/**
	 * Construct a {@code NodeInfo} object.
	 *
	 * @param id the node id.
	 * @param inetAddr the node raw IP address, can be IPv4 or IPv6.
	 * @param port the node port number.
	 */
	public static NodeInfo of(Id id, byte[] inetAddr, int port) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(inetAddr, "addr");
		try {
			return new NodeInfo(id, new InetSocketAddress(InetAddress.getByAddress(inetAddr), port));
		} catch (UnknownHostException e) {
			throw new IllegalArgumentException("Invalid binary network address", e);
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
	public static NodeInfo of(Id id, @Nullable InetSocketAddress sockAddr4, @Nullable InetSocketAddress sockAddr6) {
		return new NodeInfo(id, sockAddr4, sockAddr6);
	}

	/**
	 * Construct a {@code NodeInfo} object with an optional IPv4 and an optional IPv6 address.
	 *
	 * @param id the node id.
	 * @param inetAddr4 the IPv4 address, can be null.
	 * @param port4 the IPv4 port number, ignored if {@code inetAddr4} is null.
	 * @param inetAddr6 the IPv6 address, can be null.
	 * @param port6 the IPv6 port number, ignored if {@code inetAddr6} is null.
	 * @throws IllegalArgumentException if both addresses are null, or if an address/port is invalid.
	 */
	public static NodeInfo of(Id id, @Nullable InetAddress inetAddr4, int port4, @Nullable InetAddress inetAddr6, int port6) {
		Objects.requireNonNull(id, "id");
		if (inetAddr4 == null && inetAddr6 == null)
			throw new IllegalArgumentException("At least one address must be specified");

		InetSocketAddress sockAddr4 = null;
		if (inetAddr4 != null) {
			if (!(inetAddr4 instanceof Inet4Address))
				throw new IllegalArgumentException("Invalid IPv4 address: " + inetAddr4);
			if (port4 <= 0 || port4 > 65535)
				throw new IllegalArgumentException("Invalid port4: " + port4);

			sockAddr4 = new InetSocketAddress(inetAddr4, port4);
		}

		InetSocketAddress sockAddr6 = null;
		if (inetAddr6 != null) {
			if (!(inetAddr6 instanceof Inet6Address))
				throw new IllegalArgumentException("Invalid IPv6 address: " + inetAddr6);
			if (port6 <= 0 || port6 > 65535)
				throw new IllegalArgumentException("Invalid port6: " + port6);

			sockAddr6 = new InetSocketAddress(inetAddr6, port6);
		}

		return new NodeInfo(id, sockAddr4, sockAddr6);
	}

	/**
	 * Construct a {@code NodeInfo} object with an optional IPv4 and an optional IPv6 host address.
	 *
	 * @param id the node id.
	 * @param host4 the IPv4 host name or address string, can be null.
	 * @param port4 the IPv4 port number, ignored if {@code host4} is null.
	 * @param host6 the IPv6 host name or address string, can be null.
	 * @param port6 the IPv6 port number, ignored if {@code host6} is null.
	 * @throws IllegalArgumentException if both hosts are null, or if an address/port is invalid.
	 */
	public static NodeInfo of(Id id, @Nullable String host4, int port4, @Nullable String host6, int port6) {
		Objects.requireNonNull(id, "id");
		if (host4 == null && host6 == null)
			throw new IllegalArgumentException("At least one host address must be specified");

		InetSocketAddress sockAddr4 = null;
		if (host4 != null) {
			if (port4 <= 0 || port4 > 65535)
				throw new IllegalArgumentException("Invalid port4: " + port4);

			sockAddr4 = new InetSocketAddress(host4, port4);
			if (!(sockAddr4.getAddress() instanceof Inet4Address))
				throw new IllegalArgumentException("Invalid IPv4 address: " + host4);
		}

		InetSocketAddress sockAddr6 = null;
		if (host6 != null) {
			if (port6 <= 0 || port6 > 65535)
				throw new IllegalArgumentException("Invalid port6: " + port6);

			sockAddr6 = new InetSocketAddress(host6, port6);
			if (!(sockAddr6.getAddress() instanceof Inet6Address))
				throw new IllegalArgumentException("Invalid IPv6 address: " + host6);
		}

		return new NodeInfo(id, sockAddr4, sockAddr6);
	}

	/**
	 * Construct a {@code NodeInfo} object with an optional IPv4 and an optional IPv6 raw address.
	 *
	 * @param id the node id.
	 * @param inetAddr4 the raw IPv4 address bytes, can be null.
	 * @param port4 the IPv4 port number, ignored if {@code inetAddr4} is null.
	 * @param inetAddr6 the raw IPv6 address bytes, can be null.
	 * @param port6 the IPv6 port number, ignored if {@code inetAddr6} is null.
	 * @throws IllegalArgumentException if both addresses are null, or if an address/port is invalid.
	 */
	public static NodeInfo of(Id id, byte @Nullable [] inetAddr4, int port4, byte @Nullable [] inetAddr6, int port6) {
		Objects.requireNonNull(id, "id");
		if (inetAddr4 == null && inetAddr6 == null)
			throw new IllegalArgumentException("At least one address must be specified");

		InetSocketAddress sockAddr4 = null;
		if (inetAddr4 != null) {
			if (port4 <= 0 || port4 > 65535)
				throw new IllegalArgumentException("Invalid port4: " + port4);

			InetAddress ia;
			try {
				ia = InetAddress.getByAddress(inetAddr4);
			} catch (UnknownHostException e) {
				throw new IllegalArgumentException("Invalid binary inetAddr4");
			}
			if (!(ia instanceof Inet4Address))
				throw new IllegalArgumentException("Invalid IPv4 address: " + ia);

			sockAddr4 = new InetSocketAddress(ia, port4);
		}

		InetSocketAddress sockAddr6 = null;
		if (inetAddr6 != null) {
			if (port6 <= 0 || port6 > 65535)
				throw new IllegalArgumentException("Invalid port6: " + port6);

			InetAddress ia;
			try {
				ia = InetAddress.getByAddress(inetAddr6);
			} catch (UnknownHostException e) {
				throw new IllegalArgumentException("Invalid binary inetAddr6");
			}
			if (!(ia instanceof Inet6Address))
				throw new IllegalArgumentException("Invalid IPv6 address: " + ia);

			sockAddr6 = new InetSocketAddress(ia, port6);
		}

		return new NodeInfo(id, sockAddr4, sockAddr6);
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
	 * Returns a view of this node narrowed to a single protocol family, dropping any address of the
	 * other family. The returned node carries only the requested family's address, so its generic
	 * accessors unambiguously refer to that family and it compares equal only to other single-family
	 * nodes with the same id and address. If this node already has only the requested family, it is
	 * returned unchanged.
	 *
	 * @param family the protocol family to keep (INET or INET6); the node must have an address for it.
	 * @return a single-address {@code NodeInfo} for the requested family.
	 * @throws IllegalStateException if no address of the requested family is available.
	 * @throws IllegalArgumentException if the family is not INET or INET6.
	 */
	public NodeInfo narrowDown(StandardProtocolFamily family) {
		InetSocketAddress addr = switch (family) {
			case INET -> addr4;
			case INET6 -> addr6;
			default -> throw new IllegalArgumentException("Unsupported protocol family: " + family);
		};

		if (addr == null)
			throw new IllegalStateException("No " +
					(family == StandardProtocolFamily.INET ? "IPv4" : "IPv6") + " address is available");

		// Already single-family (of the requested family, since its address is present): share it.
		if (!hasMultiAddresses())
			return this;

		return new NodeInfo(id, addr);
	}

	/**
	 * Checks whether the node has an address of the given protocol family.
	 *
	 * @param family the protocol family (INET or INET6).
	 * @return true if an address of that family is available.
	 * @throws IllegalArgumentException if the family is not INET or INET6.
	 */
	public boolean hasAddress(StandardProtocolFamily family) {
		return switch (family) {
			case INET -> addr4 != null;
			case INET6 -> addr6 != null;
			default -> throw new IllegalArgumentException("Unsupported protocol family: " + family);
		};
	}

	/**
	 * Checks whether the node has an IPv4 address.
	 *
	 * @return true if an IPv4 address is available.
	 */
	public boolean hasAddress4() {
		return addr4 != null;
	}

	/**
	 * Checks whether the node has an IPv6 address.
	 *
	 * @return true if an IPv6 address is available.
	 */
	public boolean hasAddress6() {
		return addr6 != null;
	}

	/**
	 * Checks whether the node is dual-stack (has both an IPv4 and an IPv6 address).
	 *
	 * @return true if both an IPv4 and an IPv6 address are available.
	 */
	public boolean hasMultiAddresses() {
		return addr4 != null && addr6 != null;
	}

	/**
	 * Returns the protocol family used by the generic accessors ({@link #getAddress()},
	 * {@link #getHost()}, {@link #getPort()}, {@link #getIpAddress()}). For a dual-stack node this is
	 * IPv4 by default; for a single-stack node it is the only available family.
	 *
	 * @return the preferred protocol family (INET or INET6).
	 */
	public StandardProtocolFamily getPreferredFamily() {
		return defaultProtocolFamily;
	}

	/**
	 * Gets the socket address of the node for the {@linkplain #getPreferredFamily() preferred family}.
	 * For a dual-stack node this is the IPv4 address; for a single-stack node it is the only available
	 * address. Use {@link #getAddress4()}/{@link #getAddress6()} or {@link #getAddress(StandardProtocolFamily)}
	 * to target a specific family.
	 *
	 * @return the socket address.
	 */
	public InetSocketAddress getAddress() {
		return Objects.requireNonNull(getAddress(defaultProtocolFamily));
	}

	/**
	 * Gets the socket address of the node for the given protocol family.
	 *
	 * @param family the protocol family (INET or INET6).
	 * @return the socket address, or null if not available for that family.
	 * @throws IllegalArgumentException if the family is not INET or INET6.
	 */
	public @Nullable InetSocketAddress getAddress(StandardProtocolFamily family) {
		return switch (family) {
			case INET -> addr4;
			case INET6 -> addr6;
			default -> throw new IllegalArgumentException("Unsupported protocol family: " + family);
		};
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
		return Objects.requireNonNull(getIpAddress(defaultProtocolFamily));
	}

	/**
	 * Gets the IP address of the node for the given protocol family.
	 *
	 * @param family the protocol family (INET or INET6).
	 * @return the IP address, or null if not available for that family.
	 * @throws IllegalArgumentException if the family is not INET or INET6.
	 */
	public @Nullable InetAddress getIpAddress(StandardProtocolFamily family) {
		return switch (family) {
			case INET -> addr4 != null ? addr4.getAddress() : null;
			case INET6 -> addr6 != null ? addr6.getAddress() : null;
			default -> throw new IllegalArgumentException("Unsupported protocol family: " + family);
		};
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
		return Objects.requireNonNull(getHost(defaultProtocolFamily));
	}

	/**
	 * Returns the String form of the IP address or hostname for the given protocol family.
	 * This method will <b>not</b> attempt to do a reverse lookup.
	 *
	 * @param family the protocol family (INET or INET6).
	 * @return the host string, or null if not available for that family.
	 * @throws IllegalArgumentException if the family is not INET or INET6.
	 */
	public @Nullable String getHost(StandardProtocolFamily family) {
		return switch (family) {
			case INET -> addr4 != null ? addr4.getHostString() : null;
			case INET6 -> addr6 != null ? addr6.getHostString() : null;
			default -> throw new IllegalArgumentException("Unsupported protocol family: " + family);
		};
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
		return getPort(defaultProtocolFamily);
	}

	/**
	 * Gets the port number of the node for the given protocol family.
	 *
	 * @param family the protocol family (INET or INET6).
	 * @return the port number, or -1 if not available for that family.
	 * @throws IllegalArgumentException if the family is not INET or INET6.
	 */
	public int getPort(StandardProtocolFamily family) {
		return switch (family) {
			case INET -> addr4 != null ? addr4.getPort() : -1;
			case INET6 -> addr6 != null ? addr6.getPort() : -1;
			default -> throw new IllegalArgumentException("Unsupported protocol family: " + family);
		};
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
	 * Checks whether this node info conflicts with another, i.e.; they share the same id
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
				(addr4 != null ? getHost4() + ":" + getPort4() : "") +
				(addr4 != null && addr6 != null ? "|" : "") +
				(addr6 != null ? " [" + getHost6() + "]:" + getPort6() : "");
	}
}