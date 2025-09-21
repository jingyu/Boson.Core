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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ProtocolFamily;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

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
public class AddressUtils {
	// IPv4 Bogon ranges (excludes ranges covered by InetAddress methods)
	// Reference: RFC 1918, RFC 6890
	private static final String[] IPV4_BOGON_RANGES = {
			// "0.0.0.0/8", 		// Any local
			// "10.0.0.0/8",		// Site local
			"100.64.0.0/10",        // Private network - shared address space (RFC 6598)
			// "127.0.0.0/8",		// Loopback
			// "169.254.0.0/16",	// Link local
			// "172.16.0.0/12",		// Site local
			"192.0.0.0/24",         // Reserved (IANA)
			"192.0.2.0/24",         // Documentation (TEST-NET-1)
			// "192.168.0.0/16",	// Site local
			"198.18.0.0/15",        // Benchmarking (RFC 2544)
			"198.51.100.0/24",      // Documentation (TEST-NET-2)
			"203.0.113.0/24",       // Documentation (TEST-NET-3)
			// "224.0.0.0/4",		// Multicast
			"233.252.0.0/24",       // Documentation
			"240.0.0.0/4",          // Reserved (partially allocated)
			"255.255.255.255/32"	// Broadcast
	};

	// IPv6 Bogon ranges (excludes ranges covered by InetAddress methods)
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
			"fc00::/7"              // Unique local address (RFC 4193)
			// "fe80::/10",			// Link local
			// "fec0::/10",			// Site local
			// "ff00::/8"			// Multicast
	};

	private static List<Subnet> bogonSubnetsIpv4;
	private static List<Subnet> bogonSubnetsIpv6;

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
		 * @param maskBits the number of mask bits (0–32 for IPv4, 0–128 for IPv6)
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
			this.network = network;
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
	 * Updates Bogon ranges from external sources (e.g., Team Cymru).
	 * Fetches the latest IPv4 and IPv6 Bogon lists and updates the internal subnet lists.
	 * Should be called periodically to keep Bogon ranges current.
	 *
	 * @throws RuntimeException if the update fails due to network or parsing errors
	 */
	public static void updateBogonRanges() {
		List<Subnet> ipv4Subnets = new ArrayList<>();
		List<Subnet> ipv6Subnets = new ArrayList<>();

		try {
			// Load IPv4 Bogon list
			URL ipv4BogonUrl = new URL("https://www.team-cymru.org/Services/Bogons/fullbogons-ipv4.txt");
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(ipv4BogonUrl.openStream()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (!line.startsWith("#") && !line.trim().isEmpty())
						ipv4Subnets.add(Subnet.of(line.trim()));
				}
			}

			// Load IPv6 Bogon list
			URL ipv6BogonUrl = new URL("https://www.team-cymru.org/Services/Bogons/fullbogons-ipv6.txt");
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(ipv6BogonUrl.openStream()))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (!line.startsWith("#") && !line.trim().isEmpty())
						ipv6Subnets.add(Subnet.of(line.trim()));
				}
			}

			// Update static lists (thread-safe)
			bogonSubnetsIpv4 = List.copyOf(ipv4Subnets);
			bogonSubnetsIpv6 = List.copyOf(ipv6Subnets);
		} catch (IOException e) {
			throw new RuntimeException("Failed to update Bogon ranges", e);
		}
	}

	/**
	 * Checks if the socket address is a Bogon address or has an invalid port.
	 * A Bogon address is an IP address that should not appear in public Internet routing tables.
	 *
	 * @param addr the socket address to check
	 * @return true if the address is a Bogon address or the port is invalid (≤ 0 or > 65535), false otherwise
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
	 * @return true if the address is a Bogon address or the port is invalid (≤ 0 or > 65535), false otherwise
	 * @throws IllegalArgumentException if the address is invalid
	 * @throws NullPointerException if addr is null
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

		// Check common Bogon types using InetAddress
		if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress() ||
				addr.isMulticastAddress() || addr.isSiteLocalAddress() || addr.isMCLinkLocal() ||
				addr.isMCNodeLocal() || addr.isMCOrgLocal() || addr.isMCSiteLocal())
			return true;

		// Handle IPv4-mapped addresses (::ffff:0:0/96)
		if (addr instanceof Inet6Address) {
			byte[] bytes = addr.getAddress();
			if (bytes.length == 16 &&
					bytes[0] == 0 && bytes[1] == 0 && bytes[2] == 0 && bytes[3] == 0 &&
					bytes[4] == 0 && bytes[5] == 0 && bytes[6] == 0 && bytes[7] == 0 &&
					bytes[8] == 0 && bytes[9] == 0 && bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff) {
				try {
					// Extract IPv4 part
					byte[] ipv4Bytes = new byte[4];
					System.arraycopy(bytes, 12, ipv4Bytes, 0, 4);
					InetAddress ipv4Addr = InetAddress.getByAddress(ipv4Bytes);
					// Check IPv4 Bogon ranges and properties
					if (ipv4Addr.isAnyLocalAddress() || ipv4Addr.isLoopbackAddress() ||
							ipv4Addr.isLinkLocalAddress() || ipv4Addr.isMulticastAddress() ||
							ipv4Addr.isSiteLocalAddress())
						return true;

					for (Subnet subnet : bogonSubnetsIpv4) {
						if (subnet.contains(ipv4Addr))
							return true;
					}
				} catch (UnknownHostException e) {
					return false; // Should not happen
				}
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
		return addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress() ||
				addr.isMulticastAddress() || addr.isSiteLocalAddress() || addr.isMCLinkLocal() ||
				addr.isMCNodeLocal() || addr.isMCOrgLocal() || addr.isMCSiteLocal();
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
	 * Uses a test connection to a public address (e.g., 8.8.8.8 for IPv4, 2001:4860:4860::8888 for IPv6).
	 *
	 * @param type the address class (Inet4Address or Inet6Address)
	 * @return the address of the default routing interface, or null if not found
	 * @throws IllegalArgumentException if the type is not supported
	 */
	public static InetAddress getDefaultRouteAddress(Class<? extends InetAddress> type) {
		InetAddress target = null;
		ProtocolFamily family = type == Inet6Address.class ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET;

		try (DatagramChannel ch = DatagramChannel.open(family)) {
			if (type == Inet4Address.class)
				target = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
			else if (type == Inet6Address.class)
				target = InetAddress.getByName("2001:4860:4860::8888");
			else
				throw new IllegalArgumentException("Unsupported type: " + type);

			ch.connect(new InetSocketAddress(target, 63));
			InetSocketAddress soa = (InetSocketAddress) ch.getLocalAddress();
			InetAddress local = soa.getAddress();

			if (type.isInstance(local) && !local.isAnyLocalAddress())
				return local;

			return null;
		} catch (IOException e) {
			throw new RuntimeException("Failed to get default route address", e);
		}
	}

	/**
	 * Converts a socket address to a readable string, with optional alignment.
	 * IPv6 addresses are enclosed in square brackets.
	 *
	 * @param addr the socket address to convert
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
}