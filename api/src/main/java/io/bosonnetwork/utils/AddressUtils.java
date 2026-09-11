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

package io.bosonnetwork.utils;

import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import io.vertx.core.net.SocketAddress;

/**
 * Utility class for manipulating IP addresses, supporting both IPv4 and IPv6.
 * Provides methods to check for Bogon, Martian, global unicast, Teredo, and 6to4 addresses,
 * as well as utilities for network interface and socket address handling.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Bogon_filtering">Bogon filtering</a>
 * @see <a href="https://en.wikipedia.org/wiki/Reserved_IP_addresses">Reserved IP addresses</a>
 * @see <a href="https://en.wikipedia.org/wiki/Martian_packet">Martian packet</a>
 */
public final class AddressUtils {
	private static final int IPV4_OCTETS = 4;
	private static final int IPV6_GROUPS = 8;

	/**
	 * A "::" must stand for at least one all-zero group, so a compressed address carries at
	 * most this many explicit groups.
	 */
	private static final int IPV6_MAX_EXPLICIT_GROUPS = IPV6_GROUPS - 1;

	/** Groups an embedded dotted-quad tail is worth, e.g. the "1.2.3.4" in "::ffff:1.2.3.4". */
	private static final int IPV4_TAIL_GROUPS = 2;

	private AddressUtils() {
	}

	// IPv4 Bogon ranges. Some entries overlap with InetAddress.isSiteLocalAddress() /
	// isLoopbackAddress() / isLinkLocalAddress() / isMulticastAddress() - kept here explicitly
	// so the table reads as a self-contained list of RFC-classified non-routable ranges.
	// Reference: RFC 1918, RFC 6890
	private static final String[] IPV4_BOGON_RANGES = {
			"0.0.0.0/8",            // Any local
			"10.0.0.0/8",           // Site local
			"100.64.0.0/10",        // Private network - shared address space (RFC 6598)
			"127.0.0.0/8",          // Loopback
			"169.254.0.0/16",       // Link local
			"172.16.0.0/12",        // Site local
			"192.0.0.0/24",         // Reserved (IANA)
			"192.0.2.0/24",         // Documentation (TEST-NET-1)
			"192.168.0.0/16",       // Site local
			"198.18.0.0/15",        // Benchmarking (RFC 2544)
			"198.51.100.0/24",      // Documentation (TEST-NET-2)
			"203.0.113.0/24",       // Documentation (TEST-NET-3)
			"224.0.0.0/4",          // Multicast
			"233.252.0.0/24",       // Documentation
			"240.0.0.0/4",          // Reserved (partially allocated)
			"255.255.255.255/32"    // Broadcast
	};

	// IPv6 Bogon ranges. Some entries overlap with InetAddress.isLinkLocalAddress() /
	// isSiteLocalAddress() / isMulticastAddress(), and some are subsets of broader entries in this
	// table (e.g. ::ffff:0:0/96 within ::/8; Teredo / Benchmarking / ORCHID within 2001::/23) - kept here
	// explicitly so the table reads as a self-contained list of RFC-classified non-routable ranges.
	// Reference: RFC 4291, RFC 6890
	private static final String[] IPV6_BOGON_RANGES = {
			"::/8",                 // Reserved
			"::ffff:0:0/96",        // IPv4-mapped address
			"100::/64",             // Discarded
			"2001::/23",            // IETF Protocol Assignments
			"2001::/32",            // Teredo (RFC 4380)
			"2001:2::/48",          // Benchmarking (RFC 5180)
			"2001:10::/28",         // ORCHID (RFC 4843)
			"2001:20::/28",         // ORCHIDv2 (RFC 7343)
			"2001:db8::/32",        // Documentation (RFC 3849)
			"2002::/16",            // 6to4 (RFC 3056)
			"3fff::/20",            // Documentation
			"3ffe::/16",            // 6bone testing
			"5f00::/16",            // Segment Routing (SRv6) SIDs
			"fc00::/7",             // Unique local address (RFC 4193)
			"fe80::/10",            // Link local
			"fec0::/10",            // Site local
			"ff00::/8"              // Multicast
	};

	private static final List<Subnet> bogonSubnetsIpv4;
	private static final List<Subnet> bogonSubnetsIpv6;

	static {
		// Initialize Bogon subnets
		try {
			// IPv4 Bogon subnets
			List<Subnet> ipv4Subnets = new ArrayList<>(IPV4_BOGON_RANGES.length);
			for (String cidr : IPV4_BOGON_RANGES)
				ipv4Subnets.add(Subnet.of(cidr));
			bogonSubnetsIpv4 = List.copyOf(ipv4Subnets);

			// IPv6 Bogon subnets
			List<Subnet> ipv6Subnets = new ArrayList<>(IPV6_BOGON_RANGES.length);
			for (String cidr : IPV6_BOGON_RANGES)
				ipv6Subnets.add(Subnet.of(cidr));
			bogonSubnetsIpv6 = List.copyOf(ipv6Subnets);
		} catch (Exception e) {
			throw new RuntimeException("Failed to initialize Bogon subnets", e);
		}
	}

	/**
	 * Represents a subnetwork defined by a network ID and a netmask.
	 * Uses byte-based comparisons for efficient IPv4 and IPv6 address matching.
	 */
	protected static class Subnet {
		private final byte[] network;
		private final int maskBits;

		/**
		 * Creates a Subnet from a CIDR notation string.
		 *
		 * @param cidr the CIDR notation (e.g., "192.168.1.0/24" or "2001:db8::/32")
		 * @return a Subnet object representing the CIDR network
		 * @throws IllegalArgumentException if the CIDR string is invalid
		 */
		public static Subnet of(String cidr) {
			String[] parts = cidr.split("/");
			if (parts.length != 2)
				throw new IllegalArgumentException("Invalid CIDR: " + cidr);

			try {
				byte[] addr;
				int maskBits = Integer.parseInt(parts[1]);

				// Special handling for ::ffff:0:0/96 (IPv4-mapped address)
				if ("::ffff:0:0".equals(parts[0]) && maskBits == 96) {
					// Create IPv6 address: 00:00:00:00:00:00:00:00:00:00:ff:ff:00:00:00:00
					addr = new byte[16];
					addr[10] = (byte) 0xff;
					addr[11] = (byte) 0xff;
				} else {
					addr = InetAddress.getByName(parts[0]).getAddress();
				}

				int maxBits = addr.length * 8;
				if (maskBits < 0 || maskBits > maxBits)
					throw new IllegalArgumentException("Invalid mask bits: " + maskBits + " for address length " + maxBits);

				return new Subnet(addr, maskBits);
			} catch (UnknownHostException e) {
				throw new IllegalArgumentException("Invalid CIDR: " + cidr, e);
			}
		}

		/**
		 * Creates a Subnet from a network address and mask bits.
		 *
		 * @param network  the network address
		 * @param maskBits the number of mask bits (0-32 for IPv4, 0-128 for IPv6)
		 * @return a Subnet object representing the network and mask bits
		 * @throws IllegalArgumentException if the address or mask is invalid
		 */
		public static Subnet of(InetAddress network, int maskBits) {
			Objects.requireNonNull(network, "Network address must not be null");
			byte[] addr = network.getAddress();
			int maxBits = addr.length * 8;
			if (maskBits < 0 || maskBits > maxBits)
				throw new IllegalArgumentException("Invalid mask bits: " + maskBits + " for address length " + maxBits);

			return new Subnet(addr, maskBits);
		}

		private Subnet(byte[] network, int maskBits) {
			// defensive copy: callers should not be able to mutate our network bytes post-construction
			this.network = network.clone();
			this.maskBits = maskBits;
		}

		/**
		 * Checks if an address belongs to this subnetwork.
		 *
		 * @param addr the address to check
		 * @return true if the address is in the subnetwork, false otherwise
		 */
		public boolean contains(InetAddress addr) {
			byte[] ipAddress = addr.getAddress();
			if (network.length != ipAddress.length)
				return false; // Different address types (IPv4 vs IPv6)

			// Check whole bytes
			int wholeBytes = maskBits >>> 3; // maskBits / 8
			for (int i = 0; i < wholeBytes; i++) {
				if (network[i] != ipAddress[i])
					return false;
			}

			// Check partial byte, if any
			if ((maskBits & 0x07) == 0) // maskBits % 8 == 0
				return true; // No partial byte to check

			int remainingBits = maskBits & 0x07; // maskBits % 8
			int probeMask = (0xff00 >>> remainingBits) & 0xff; // e.g., for 3 bits: 11100000
			return (network[wholeBytes] & probeMask) == (ipAddress[wholeBytes] & probeMask);
		}

		@Override
		public String toString() {
			try {
				return String.format("%s/%d", InetAddress.getByAddress(network).getHostAddress(), maskBits);
			} catch (UnknownHostException e) {
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * Checks if the socket address is a Bogon address or has an invalid port.
	 * A Bogon address is an IP address that should not appear in public Internet routing tables.
	 *
	 * @param addr the socket address to check
	 * @return true if the address is a Bogon address or the port is invalid (&lt;= 0 or &gt; 65535), false otherwise
	 * @throws NullPointerException if addr is null
	 */
	public static boolean isBogon(InetSocketAddress addr) {
		Objects.requireNonNull(addr, "Socket address cannot be null");
		return addr.getPort() <= 0 || addr.getPort() > 65535 || isBogon(addr.getAddress());
	}

	/**
	 * Checks if the Vert.x socket address is a Bogon address or has an invalid port.
	 *
	 * @param addr the Vert.x socket address to check
	 * @return true if the address is a Bogon address or the port is invalid (&lt;= 0 or &gt; 65535), false otherwise
	 * @throws IllegalArgumentException if the address is invalid
	 * @throws NullPointerException     if addr is null
	 */
	public static boolean isBogon(SocketAddress addr) {
		Objects.requireNonNull(addr, "Socket address cannot be null");
		if (addr.port() <= 0 || addr.port() > 65535)
			return true;

		if (addr.hostAddress() == null) // Unresolved Vert.x SocketAddress
			return false;

		try {
			return isBogon(InetAddress.getByName(addr.hostAddress()));
		} catch (UnknownHostException e) {
			throw new IllegalArgumentException("Invalid address: " + addr.hostAddress(), e);
		}
	}

	/**
	 * Checks if the IP address is a Bogon address.
	 * Bogon addresses include private, reserved, unallocated, or special-purpose addresses
	 * that should not be routed on the public Internet.
	 * <p>
	 * For IPv4-mapped IPv6 addresses (::ffff:0:0/96), checks the embedded IPv4 address.
	 * <p>
	 * References:
	 * <ul>
	 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc1918">RFC 1918</a> - Address Allocation for Private Internets</li>
	 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc6890">RFC 6890</a> - Special-Purpose IP Address Registries</li>
	 *   <li><a href="https://en.wikipedia.org/wiki/Bogon_filtering">Bogon filtering</a></li>
	 * </ul>
	 *
	 * @param addr the IP address to check
	 * @return true if the address is a Bogon address, false otherwise
	 * @throws NullPointerException if addr is null
	 */
	public static boolean isBogon(InetAddress addr) {
		Objects.requireNonNull(addr, "Address cannot be null");

		if (isSpecialUseAddress(addr))
			return true;

		// Handle IPv4-mapped addresses (::ffff:0:0/96) - check the embedded IPv4 surface too
		InetAddress unmapped = unmapIPv4MappedIPv6(addr);
		if (unmapped != null) {
			if (isSpecialUseAddress(unmapped))
				return true;
			for (Subnet subnet : bogonSubnetsIpv4) {
				if (subnet.contains(unmapped))
					return true;
			}
		}

		// Check against Bogon ranges
		List<Subnet> bogonSubnets = addr instanceof Inet4Address ? bogonSubnetsIpv4 : bogonSubnetsIpv6;
		for (Subnet subnet : bogonSubnets) {
			if (subnet.contains(addr))
				return true;
		}

		return false;
	}

	/**
	 * Returns {@code true} if the address falls into one of the InetAddress-classified
	 * "special-use" categories: any-local, loopback, link-local, multicast (including all
	 * MC scopes), and site-local. Shared between {@link #isBogon(InetAddress)} and
	 * {@link #isMartian(InetAddress)} so the two stay in lockstep.
	 */
	private static boolean isSpecialUseAddress(InetAddress addr) {
		return addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress() ||
				addr.isMulticastAddress() || addr.isSiteLocalAddress() || addr.isMCLinkLocal() ||
				addr.isMCNodeLocal() || addr.isMCOrgLocal() || addr.isMCSiteLocal();
	}

	/**
	 * Determines if the given InetAddress represents an IPv4-mapped IPv6 address.
	 * An IPv4-mapped IPv6 address has the first 10 bytes set to 0, the 11th and 12th
	 * bytes set to 0xFF, and the last 4 bytes representing an IPv4 address.
	 *
	 * @param addr the InetAddress to check. It can be an instance of Inet6Address
	 *             or another type of InetAddress.
	 * @return true if the given address is an IPv4-mapped IPv6 address; false otherwise.
	 */
	public static boolean isIPv4Mapped(InetAddress addr) {
		if (!(addr instanceof Inet6Address))
			return false;

		byte[] bytes = addr.getAddress();
		for (int i = 0; i < 10; i++) {
			if (bytes[i] != 0)
				return false;
		}

		return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
	}

	/**
	 * If {@code addr} is an IPv4-mapped IPv6 address ({@code ::ffff:0:0/96}), returns the
	 * embedded IPv4 address; otherwise returns {@code null}. Lets classification routines
	 * apply IPv4 rules to the mapped form.
	 */
	private static @Nullable InetAddress unmapIPv4MappedIPv6(InetAddress addr) {
		if (!(addr instanceof Inet6Address))
			return null;
		byte[] bytes = addr.getAddress();
		if (bytes.length != 16)
			return null;
		for (int i = 0; i < 10; i++) {
			if (bytes[i] != 0)
				return null;
		}
		if (bytes[10] != (byte) 0xff || bytes[11] != (byte) 0xff)
			return null;
		try {
			byte[] ipv4 = new byte[4];
			System.arraycopy(bytes, 12, ipv4, 0, 4);
			return InetAddress.getByAddress(ipv4);
		} catch (UnknownHostException e) {
			return null; // Should not happen
		}
	}

	/**
	 * Checks if the IP address is a Martian address, a subset of Bogon addresses.
	 * Martian addresses are private, reserved, or multicast addresses that should not appear
	 * in public Internet routing tables, often due to misconfiguration or spoofing.
	 * <p>
	 * References:
	 * <ul>
	 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc1812">RFC 1812</a> - Requirements for IP Version 4 Routers</li>
	 *   <li><a href="https://en.wikipedia.org/wiki/Martian_packet">Martian packet</a></li>
	 * </ul>
	 *
	 * @param addr the IP address to check
	 * @return true if the address is a Martian address, false otherwise
	 * @throws NullPointerException if addr is null
	 */
	public static boolean isMartian(InetAddress addr) {
		Objects.requireNonNull(addr, "Address cannot be null");
		if (isSpecialUseAddress(addr))
			return true;
		// Apply the same check on the IPv4 surface of an IPv4-mapped IPv6 address, so an address
		// like ::ffff:127.0.0.1 is classified the same way isBogon() classifies it.
		InetAddress unmapped = unmapIPv4MappedIPv6(addr);
		return unmapped != null && isSpecialUseAddress(unmapped);
	}

	/**
	 * Checks if the IP address is a Teredo address.
	 * Teredo addresses (2001:0::/32) are used for IPv6 tunneling over IPv4 networks.
	 * <p>
	 * References:
	 * <ul>
	 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc4380">RFC 4380</a> - Teredo: Tunneling IPv6 over UDP</li>
	 *   <li><a href="https://en.wikipedia.org/wiki/Teredo_tunneling">Teredo tunneling</a></li>
	 * </ul>
	 *
	 * @param addr the IP address to check
	 * @return true if the address is a Teredo address, false otherwise
	 */
	public static boolean isTeredo(InetAddress addr) {
		if (!(addr instanceof Inet6Address))
			return false;

		byte[] raw = addr.getAddress();
		// https://datatracker.ietf.org/doc/html/rfc4380#section-2.6
		// prefix 2001:0000:/32
		return raw[0] == 0x20 && raw[1] == 0x01 && raw[2] == 0x00 && raw[3] == 0x00;
	}

	/**
	 * Checks if the IP address is a 6to4 address.
	 * 6to4 addresses (2002::/16) are used for automatic IPv6 tunneling over IPv4 networks.
	 * <p>
	 * References:
	 * <ul>
	 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc3056">RFC 3056</a> - Connection of IPv6 Domains via IPv4 Clouds</li>
	 * </ul>
	 *
	 * @param addr the IP address to check
	 * @return true if the address is a 6to4 address, false otherwise
	 */
	public static boolean is6to4(InetAddress addr) {
		if (!(addr instanceof Inet6Address)) {
			return false;
		}
		byte[] raw = addr.getAddress();
		// 6to4 prefix: 2002::/16
		return raw[0] == 0x20 && raw[1] == 0x02;
	}

	/**
	 * Checks if the IP address is a global unicast address.
	 * For IPv6, global unicast addresses are in the 2000::/3 range (RFC 4291) and not Bogon.
	 * For IPv4, global unicast addresses are non-Bogon addresses.
	 * <p>
	 * References:
	 * <ul>
	 *   <li><a href="https://datatracker.ietf.org/doc/html/rfc4291">RFC 4291</a> - IP Version 6 Addressing Architecture</li>
	 * </ul>
	 *
	 * @param addr the IP address to check
	 * @return true if the address is a global unicast address, false otherwise
	 * @throws NullPointerException if addr is null
	 */
	public static boolean isGlobalUnicast(InetAddress addr) {
		Objects.requireNonNull(addr, "Address cannot be null");
		if (addr instanceof Inet6Address) {
			byte[] bytes = addr.getAddress();
			// Global unicast: 2000::/3 (0010... or 0011...)
			return (bytes[0] & 0xe0) == 0x20 && !isBogon(addr);
		}

		return !isBogon(addr);
	}

	/**
	 * Checks if the IP address is a private (non-globally-routable) address.
	 * <p>
	 * Returns true for:
	 * <ul>
	 *   <li>IPv4 site-local (RFC 1918: {@code 10/8}, {@code 172.16/12}, {@code 192.168/16}) via
	 *       {@link InetAddress#isSiteLocalAddress()};</li>
	 *   <li>IPv4 shared address space (RFC 6598 / CGN, {@code 100.64.0.0/10}) - not covered by
	 *       {@code isSiteLocalAddress()};</li>
	 *   <li>IPv6 site-local ({@code fec0::/10}, deprecated) via {@code isSiteLocalAddress()};</li>
	 *   <li>IPv6 Unique Local Addresses (RFC 4193, {@code fc00::/7}).</li>
	 * </ul>
	 *
	 * @param addr the IP address to check
	 * @return true if the address is private, false otherwise
	 * @throws NullPointerException if addr is null
	 */
	public static boolean isPrivate(InetAddress addr) {
		Objects.requireNonNull(addr, "Address cannot be null");
		if (addr.isSiteLocalAddress())
			return true;
		byte[] b = addr.getAddress();
		if (addr instanceof Inet4Address)
			// RFC 6598: 100.64.0.0/10 - first byte 0x64 (100), top 2 bits of second byte = 01
			return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 0x40;
		if (addr instanceof Inet6Address)
			// RFC 4193: fc00::/7 - top 7 bits = 1111110 (0xfc or 0xfd in the leading byte)
			return (b[0] & 0xfe) == 0xfc;
		return false;
	}

	/**
	 * Checks if the IP address is a unicast address.
	 * <p>
	 * References:
	 * <ul>
	 *   <li><a href="https://en.wikipedia.org/wiki/Unicast">Unicast</a></li>
	 * </ul>
	 *
	 * @param addr the IP address to check
	 * @return true if the address is a unicast address, false otherwise
	 * @throws NullPointerException if addr is null
	 */
	public static boolean isAnyUnicast(InetAddress addr) {
		Objects.requireNonNull(addr, "Address cannot be null");
		return !addr.isAnyLocalAddress() && !addr.isLoopbackAddress() &&
				!addr.isLinkLocalAddress() && !addr.isMulticastAddress();
	}

	/**
	 * Retrieves all available IP addresses from active network interfaces.
	 *
	 * @return a sequential Stream of all available IP addresses
	 */
	public static Stream<InetAddress> getAllAddresses() {
		try {
			return Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
					.filter(iface -> {
						try {
							return iface.isUp();
						} catch (SocketException e) {
							return false;
						}
					}).flatMap(iface -> Collections.list(iface.getInetAddresses()).stream());
		} catch (SocketException e) {
			return Stream.empty();
		}
	}

	/**
	 * Retrieves all non-local IP addresses from active network interfaces.
	 *
	 * @return a sequential Stream of non-local IP addresses
	 */
	public static Stream<InetAddress> getNonlocalAddresses() {
		return getAllAddresses().filter(addr ->
				!addr.isAnyLocalAddress() && !addr.isLoopbackAddress() &&
						!addr.isLinkLocalAddress() && !addr.isMulticastAddress());
	}

	/**
	 * Checks if the given IP address is valid for binding.
	 *
	 * @param addr the IP address to check
	 * @return true if the address is valid for binding, false otherwise
	 * @throws NullPointerException if addr is null
	 */
	public static boolean isValidBindAddress(InetAddress addr) {
		Objects.requireNonNull(addr, "Address cannot be null");
		// Allow any-local addresses for binding
		if (addr.isAnyLocalAddress())
			return true;

		try {
			NetworkInterface iface = NetworkInterface.getByInetAddress(addr);
			return iface != null && iface.isUp() && !iface.isLoopback() && !iface.isPointToPoint();
		} catch (SocketException e) {
			return false;
		}
	}

	/**
	 * Gets the wildcard local address for the specified address type.
	 *
	 * @param type the address class (Inet4Address or Inet6Address)
	 * @return the wildcard local address (0.0.0.0 for IPv4, :: for IPv6)
	 * @throws IllegalArgumentException if the type is not supported
	 */
	public static InetAddress getAnyLocalAddress(Class<? extends InetAddress> type) {
		try {
			if (type == Inet4Address.class)
				return InetAddress.getByAddress(new byte[4]);
			else if (type == Inet6Address.class)
				return InetAddress.getByAddress(new byte[16]);
			else
				throw new IllegalArgumentException("Unsupported type: " + type);
		} catch (UnknownHostException e) {
			throw new RuntimeException("INTERNAL ERROR: should never happen", e);
		}
	}

	/**
	 * Gets the IP address of the default routing interface for the specified address type.
	 * <p>
	 * Uses the well-known UDP-{@code connect} trick: opening an unbound {@link DatagramSocket}
	 * and {@code connect}ing it to a public address forces the kernel to pick the local address
	 * the OS would use to reach that destination - without actually sending any packets. The
	 * targets are Google DNS ({@code 8.8.8.8} for IPv4, {@code 2001:4860:4860::8888} for IPv6),
	 * which the kernel only needs to be able to route to, not to talk to.
	 *
	 * @param type the address class (Inet4Address or Inet6Address)
	 * @return the address of the default routing interface, or {@code null} if no such route
	 * exists (e.g. IPv6 unconfigured, host offline) or the resolved local address is the
	 * wildcard
	 * @throws IllegalArgumentException if the type is not supported
	 */
	public static @Nullable InetAddress getDefaultRouteAddress(Class<? extends InetAddress> type) {
		if (type != Inet4Address.class && type != Inet6Address.class)
			throw new IllegalArgumentException("Unsupported type: " + type);

		try (DatagramSocket socket = new DatagramSocket()) {
			InetAddress target;
			if (type == Inet4Address.class)
				target = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
			else
				target = InetAddress.getByName("2001:4860:4860::8888");

			socket.connect(new InetSocketAddress(target, 53));
			InetAddress local = socket.getLocalAddress();

			if (type.isInstance(local) && !local.isAnyLocalAddress())
				return local;

			return null;
		} catch (SocketException | UnknownHostException e) {
			// "No route", "address family not supported", offline, etc. - not a programming error.
			return null;
		} catch (Exception e) {
			throw new RuntimeException("Failed to get default route address", e);
		}
	}

	/**
	 * Retrieves the default network interface associated with the specified address type.
	 * The default network interface is determined by resolving the default routing
	 * address for the provided address type.
	 *
	 * @param type the address class (Inet4Address or Inet6Address) used to determine
	 *             the default network interface.
	 * @return the default {@code NetworkInterface} for the specified address type,
	 * or {@code null} if no default interface is found.
	 * @throws RuntimeException if there is an error retrieving the network interface.
	 */
	public static @Nullable NetworkInterface getDefaultNetworkInterface(Class<? extends InetAddress> type) {
		InetAddress defaultAddress = getDefaultRouteAddress(type);
		if (defaultAddress == null)
			return null;

		try {
			return NetworkInterface.getByInetAddress(defaultAddress);
		} catch (SocketException e) {
			throw new RuntimeException("Failed to get default network interface", e);
		}
	}

	/**
	 * Retrieves a network interface by its name.
	 *
	 * @param name the name of the network interface to retrieve
	 * @return the {@link NetworkInterface} object, or {@code null} if no interface
	 *         with that name exists
	 * @throws RuntimeException if an I/O error occurs while querying network interfaces
	 */
	public static @Nullable NetworkInterface getNetworkInterface(String name) {
		Objects.requireNonNull(name, "Network interface name cannot be null");
		try {
			return NetworkInterface.getByName(name);
		} catch (SocketException e) {
			throw new RuntimeException("Failed to get network interface: " + name, e);
		}
	}

	/**
	 * Converts a socket address to a readable string, with optional alignment.
	 * IPv6 addresses are enclosed in square brackets.
	 *
	 * @param addr  the socket address to convert
	 * @param align whether to align the output (e.g., fixed width for IPv4/IPv6)
	 * @return the formatted string representation of the socket address
	 * @throws NullPointerException if sockAddr is null
	 */
	public static String toString(InetSocketAddress addr, boolean align) {
		Objects.requireNonNull(addr, "Socket address cannot be null");
		InetAddress ipAddress = addr.getAddress();
		int port = addr.getPort();

		if (align) {
			return ipAddress instanceof Inet6Address ?
					String.format("%41s:%-5d", "[" + ipAddress.getHostAddress() + "]", port) :
					String.format("%15s:%-5d", ipAddress.getHostAddress(), port);
		} else {
			return (ipAddress instanceof Inet6Address ?
					"[" + ipAddress.getHostAddress() + "]" : ipAddress.getHostAddress()) + ":" + port;
		}
	}

	/**
	 * Converts a socket address to a readable string without alignment.
	 *
	 * @param addr the socket address to convert
	 * @return the formatted string representation of the socket address
	 * @throws NullPointerException if sockAddr is null
	 */
	public static String toString(InetSocketAddress addr) {
		return toString(addr, false);
	}

	/**
	 * Checks whether the given string is a dotted-quad IPv4 address literal such as
	 * {@code 192.168.0.1}.
	 *
	 * <p>Four decimal octets of 0-255 separated by dots are required. Leading zeros are
	 * rejected because {@code 010} is octal to some resolvers and decimal to others, and the
	 * legacy short forms accepted by {@code InetAddress} ({@code 1.2}, {@code 0x7f.1}) are
	 * rejected as well.
	 *
	 * @param addr the address string to test, may be null
	 * @return true if the string is an IPv4 literal
	 */
	public static boolean isIPv4Literal(@Nullable String addr) {
		if (addr == null || addr.isEmpty())
			return false;

		return isIPv4(addr);
	}

	private static boolean isIPv4(String addr) {
		int octets = 0;
		int start = 0;

		for (int i = 0; i <= addr.length(); i++) {
			if (i < addr.length() && addr.charAt(i) != '.')
				continue;

			int digits = i - start;
			if (digits < 1 || digits > 3)
				return false;
			if (digits > 1 && addr.charAt(start) == '0')
				return false;

			int octet = 0;
			for (int j = start; j < i; j++) {
				char c = addr.charAt(j);
				if (c < '0' || c > '9')
					return false;
				octet = octet * 10 + (c - '0');
			}
			if (octet > 255)
				return false;

			octets++;
			start = i + 1;
		}

		return octets == IPV4_OCTETS;
	}

	/**
	 * Checks whether the given string is an IPv6 address literal, with or without the
	 * enclosing brackets used by URIs and {@code host:port} pairs, such as {@code ::1} or
	 * {@code [::1]}.
	 *
	 * <p>Accepts the forms of RFC 4291: full and {@code ::}-compressed groups of one to four
	 * hex digits, and a trailing embedded dotted-quad such as {@code ::ffff:192.168.0.1}. A
	 * RFC 4007 zone id is allowed after a {@code %}, bare or bracketed, such as
	 * {@code [fe80::1%25eth0]}. Brackets, when present, must be the outermost characters and
	 * are never accepted around an IPv4 literal.
	 *
	 * <p>Groups are held to the RFC's one to four hex digits. Implementations disagree on
	 * over-long groups whose value still fits in 16 bits: {@code InetAddress} and BSD/macOS
	 * {@code inet_pton} bound the group by value and accept {@code 00001::1}, while Python's
	 * {@code ipaddress} rejects it. This rejects it.
	 *
	 * @param addr the string to test, may be null
	 * @return true if the string is an IPv6 literal
	 */
	public static boolean isIPv6Literal(@Nullable String addr) {
		if (addr == null || addr.isEmpty())
			return false;

		String address = addr;
		if (address.charAt(0) == '[') {
			int end = address.length() - 1;
			if (end < 2 || address.charAt(end) != ']')
				return false;
			address = address.substring(1, end);
		}
		if (address.indexOf('[') >= 0 || address.indexOf(']') >= 0)
			return false;

		int zone = address.indexOf('%');
		if (zone >= 0) {
			if (!isZoneId(address.substring(zone + 1)))
				return false;
			address = address.substring(0, zone);
		}

		return isIPv6(address);
	}

	private static boolean isIPv6(String addr) {
		int compressed = addr.indexOf("::");
		if (compressed >= 0 && addr.indexOf("::", compressed + 1) >= 0)
			return false;

		// The embedded dotted-quad may only sit at the very end of the address, so it is
		// legal in the tail run always and in the head run only when there is no "::".
		String headRun = compressed >= 0 ? addr.substring(0, compressed) : addr;
		String tailRun = compressed >= 0 ? addr.substring(compressed + 2) : "";

		int head = countGroups(headRun, compressed < 0);
		int tail = countGroups(tailRun, true);
		if (head < 0 || tail < 0)
			return false;

		int groups = head + tail;
		return compressed >= 0 ? groups <= IPV6_MAX_EXPLICIT_GROUPS : groups == IPV6_GROUPS;
	}

	/**
	 * Counts the 16-bit groups in one colon-separated run of an IPv6 literal.
	 *
	 * @param run           the run, containing no "::"
	 * @param allowIPv4Tail whether the final token may be a dotted-quad worth two groups
	 * @return the number of groups, or -1 if the run is malformed
	 */
	private static int countGroups(String run, boolean allowIPv4Tail) {
		if (run.isEmpty())
			return 0;

		int groups = 0;
		int start = 0;

		for (int i = 0; i <= run.length(); i++) {
			if (i < run.length() && run.charAt(i) != ':')
				continue;

			if (i == start)
				return -1;

			String token = run.substring(start, i);
			if (token.indexOf('.') >= 0) {
				if (!allowIPv4Tail || i != run.length() || !isIPv4(token))
					return -1;
				return groups + IPV4_TAIL_GROUPS;
			}
			if (!isHexGroup(token))
				return -1;

			groups++;
			start = i + 1;
		}

		return groups;
	}

	private static boolean isHexGroup(String token) {
		if (token.length() > 4)
			return false;

		for (int i = 0; i < token.length(); i++) {
			char c = token.charAt(i);
			boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
			if (!hex)
				return false;
		}

		return true;
	}

	/**
	 * RFC 4007 leaves the zone id implementation defined - in practice an interface name or
	 * index. Restricted to the RFC 3986 "unreserved" characters so that a second '%' cannot
	 * be smuggled in; this also covers the RFC 6874 URI form, whose "%25eth0" is read as the
	 * zone id "25eth0".
	 */
	private static boolean isZoneId(String zone) {
		if (zone.isEmpty())
			return false;

		for (int i = 0; i < zone.length(); i++) {
			char c = zone.charAt(i);
			boolean unreserved = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
					|| c == '-' || c == '.' || c == '_' || c == '~';
			if (!unreserved)
				return false;
		}

		return true;
	}

	/**
	 * Parses an IP address literal into an {@link InetAddress} without ever resolving a name.
	 *
	 * <p>The string must pass {@link #isIPv4Literal} or {@link #isIPv6Literal} first, so a host
	 * name - or a form those reject but {@code InetAddress} would accept or look up, such as
	 * {@code 1.2} - returns {@code null} instead of reaching a resolver. Callers can therefore
	 * check a configured address without blocking on DNS or being steered by it. A zone id that
	 * names no interface on this machine cannot be parsed either, and also returns {@code null}.
	 *
	 * @param addr the address to parse, may be null; an IPv6 literal may be bracketed
	 * @return the parsed address, or {@code null} if the string is not a literal
	 */
	public static @Nullable InetAddress literalAddress(@Nullable String addr) {
		if (addr == null || addr.isEmpty())
			return null;

		if (!isIPv4Literal(addr) && !isIPv6Literal(addr))
			return null;

		try {
			return InetAddress.getByName(addr);
		} catch (UnknownHostException e) {
			return null;
		}
	}

	/**
	 * Checks whether the given host is a wildcard ("any local") address literal, such as
	 * {@code 0.0.0.0}, {@code ::} or {@code [::]}.
	 *
	 * <p>Only literals qualify: a host name is never resolved, so it is never a wildcard.
	 *
	 * @param host the host to test, may be null
	 * @return true if the host is a wildcard address literal
	 */
	public static boolean isWildcard(@Nullable String host) {
		InetAddress address = literalAddress(host);
		return address != null && address.isAnyLocalAddress();
	}

	/**
	 * Formats a {@code host:port} pair, bracketing an IPv6 literal so that the port stays
	 * unambiguous: {@code 2001:db8::1:9090} could be read either way, {@code [2001:db8::1]:9090}
	 * cannot.
	 *
	 * @param host the host; an IPv6 literal may already be bracketed
	 * @param port the port
	 * @return the {@code host:port} string
	 */
	public static String endpointOf(String host, int port) {
		String h;
		if (isIPv6Literal(host))
			h = host.length() > 1 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']' ?
					host : "[" + host + "]";
		else
			h = host;

		return h + ":" + port;
	}
}
