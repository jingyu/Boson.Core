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

import static io.bosonnetwork.utils.Functional.unchecked;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ProtocolFamily;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.UnknownHostException;
import java.nio.channels.DatagramChannel;
import java.util.Collections;
import java.util.stream.Stream;

/**
 * Util class to manipulate the IP addresses.
 */
public class AddressUtils {
	private static final Subnet V4_MAPPED;
	// private final static NetMask V4_COMPAT = NetMask.fromString("0000::/96");
	private final static byte[] LOCAL_BROADCAST = new byte[] { (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff };

	static {
		try {
			// ::ffff:0:0/96
			V4_MAPPED = new Subnet(Inet6Address.getByAddress(null, new byte[] { 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
					0x00, 0x00, 0x00, 0x00, (byte) 0xff, (byte) 0xff, 0x00, 0x00, 0x00, 0x00, }, null), 96);
		} catch (Exception e) {
			throw new RuntimeException("INTERNAL ERROR: should never happen");
		}
	}

	/**
	 * The {@code Subnet} class represent a subnetwork -  a logical subdivision of an IP network.
	 * It's defined by a network id and a net mask.
	 */
	public static class Subnet {
		private final byte[] network;
		private final int mask;

		/**
		 * Creates a Subnet object from a CIDR network string.
		 *
		 * @param cidr the CIDR network string.
		 * @return the Subnet object to represent the CIDR network string.
		 */
		public static Subnet fromString(String cidr) {
			String[] parts = cidr.split("/");
			return new Subnet(unchecked(() -> InetAddress.getByName(parts[0])), Integer.parseInt(parts[1]));
		}

		/**
		 * Create a Subnet object from a network address and a network mask.
		 *
		 * @param network the network address.
		 * @param mask the network mask bits.
		 */
		public Subnet(InetAddress network, int mask) {
			this.mask = mask;
			this.network = network.getAddress();
			if (this.network.length * 8 < mask)
				throw new IllegalArgumentException(
						"mask cannot cover more bits than the length of the network address");
		}

		/**
		 * Checks if the address belongs to this subnetwork.
		 *
		 * @param addr the address to check.
		 * @return true if the address belongs to this subnetwork, false otherwise.
		 */
		public boolean contains(InetAddress addr) {
			byte[] other = addr.getAddress();
			if (network.length != other.length)
				return false;

			for (int i = 0; i < mask >>> 3; i++) {
				if (network[i] != other[i])
					return false;
			}

			if ((mask & 0x07) == 0)
				return true;

			int offset = (mask >>> 3);
			int probeMask = (0xff00 >> (mask & 0x07)) & 0xff;
			return (network[offset] & probeMask) == (other[offset] & probeMask);
		}
	}

	/**
	 * Checks if the socket address is a Bogon address.
	 *
	 * References:
	 *   - https://en.wikipedia.org/wiki/Bogon_filtering
	 *   - https://en.wikipedia.org/wiki/Reserved_IP_addresses
	 *   - https://en.wikipedia.org/wiki/Martian_packet
	 *
	 * @param addr the address to check.
	 * @return true if the address is a Bogon address, false otherwise.
	 */
	public static boolean isBogon(InetSocketAddress addr) {
		return isBogon(addr.getAddress(), addr.getPort());
	}

	/**
	 * Checks if the IP address and the port is a Bogon address.
	 *
	 * References:
	 *   - https://en.wikipedia.org/wiki/Bogon_filtering
	 *   - https://en.wikipedia.org/wiki/Reserved_IP_addresses
	 *   - https://en.wikipedia.org/wiki/Martian_packet
	 *
	 * @param addr the address to check.
	 * @param port the port number to check.
	 * @return true if the address is a Bogon address, false otherwise.
	 */
	public static boolean isBogon(InetAddress addr, int port) {
		return !(port > 0 && port <= 0xFFFF && isGlobalUnicast(addr));
	}

	/**
	 * Check if the address is a Teredo address.
	 *
	 * References:
	 *   - https://datatracker.ietf.org/doc/html/rfc4380
	 *   - https://en.wikipedia.org/wiki/Teredo_tunneling
	 *
	 * @param addr the address to check.
	 * @return true if the address is Teredo address, false otherwise.
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
	 * Checks if the address is a global unicast address.
	 *
	 * @param addr the address to check
	 * @return true if the address is global unicast address, false otherwise.
	 */
	public static boolean isGlobalUnicast(InetAddress addr) {
		// this would be rejected by a socket with broadcast disabled anyway, but filter
		// it to reduce exceptions
		if (addr instanceof Inet4Address && java.util.Arrays.equals(addr.getAddress(), LOCAL_BROADCAST))
			return false;
		if (addr instanceof Inet6Address && (addr.getAddress()[0] & 0xfe) == 0xfc) // fc00::/7
			return false;
		if (addr instanceof Inet6Address && (V4_MAPPED.contains(addr) || ((Inet6Address) addr).isIPv4CompatibleAddress()))
			return false;

		return !(addr.isAnyLocalAddress() || addr.isLinkLocalAddress() || addr.isLoopbackAddress()
				|| addr.isMulticastAddress() || addr.isSiteLocalAddress());
	}

	/**
	 * Checks if the address is an unicast address.
	 * Reference:
	 *   - https://en.wikipedia.org/wiki/Unicast
	 *
	 * @param addr the address to check
	 * @return true if the address is unicast address, false otherwise.
	 */
	public static boolean isAnyUnicast(InetAddress addr) {
		return addr.isSiteLocalAddress() || isGlobalUnicast(addr);
	}

	/**
	 * Creates a {@code InetAddress} object from the raw address bytes.
	 *
	 * @param raw the raw address bytes.
	 * @return the {@code InetAddress} object.
	 * @throws UnknownHostException if the address could not be determined.
	 */
	public static InetAddress fromBytesVerbatim(byte[] raw) throws UnknownHostException {
		// bypass ipv4 mapped address conversion
		if(raw.length == 16)
			return Inet6Address.getByAddress(null, raw, null);

		return InetAddress.getByAddress(raw);
	}

	/**
	 * Gets all available addresses.
	 *
	 * @return a sequential Stream with all addresses.
	 */
	public static Stream<InetAddress> getAllAddresses() {
		try {
			return Collections.list(NetworkInterface.getNetworkInterfaces()).stream().filter(iface -> {
				try {
					return iface.isUp();
				} catch (SocketException e) {
					e.printStackTrace();
					return false;
				}
			}).flatMap(iface -> Collections.list(iface.getInetAddresses()).stream());
		} catch (SocketException e) {
			e.printStackTrace();
			return Stream.empty();
		}
	}

	/**
	 * Gets all non-local addresses.
	 *
	 * @return a sequential Stream with all non-local addresses.
	 */
	public static Stream<InetAddress> getNonlocalAddresses() {
		return getAllAddresses().filter(addr -> !addr.isAnyLocalAddress() && !addr.isLoopbackAddress());
	}

	/*
	public static Stream<InetAddress> getAvailableGloballyRoutableAddrs(Stream<InetAddress> toFilter,
			Class<? extends InetAddress> type) {
		return toFilter.filter(type::isInstance).filter(AddressUtils::isGlobalUnicast)
				.sorted((a, b) -> Arrays.compareUnsigned(a.getAddress(), b.getAddress()));
	}
	*/

	/**
	 * Checks if the given address is capable for bind.
	 *
	 * @param addr the address to check.
	 * @return true is the address is capable for bind, false otherwise.
	 */
	public static boolean isValidBindAddress(InetAddress addr) {
		// we don't like them but have to allow them
		if (addr.isAnyLocalAddress())
			return true;
		try {
			NetworkInterface iface = NetworkInterface.getByInetAddress(addr);
			if (iface == null)
				return false;
			return iface.isUp() && !iface.isLoopback();
		} catch (SocketException e) {
			return false;
		}
	}

	/**
	 * Gets the wildcard local address.
	 *
	 * @param type the {@code InetAddress} class, could be {@code Inet4Address}
	 *        or {@code Inet6Address}.
	 * @return the wildcard local address object for the specified network type.
	 */
	public static InetAddress getAnyLocalAddress(Class<? extends InetAddress> type) {
		try {
			if (type == Inet6Address.class)
				return InetAddress.getByAddress(new byte[16]);
			if (type == Inet4Address.class)
				return InetAddress.getByAddress(new byte[4]);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}

		throw new RuntimeException("INTERNAL ERROR: should never happen");
	}

	/**
	 * Gets the address of the default routing interface.
	 *
	 * @param type the {@code InetAddress} class, could be {@code Inet4Address}
	 *        or {@code Inet6Address}.
	 * @return the address of the default routing interface.
	 */
	public static InetAddress getDefaultRoute(Class<? extends InetAddress> type) {
		InetAddress target = null;

		ProtocolFamily family = type == Inet6Address.class ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET;

		try (DatagramChannel chan = DatagramChannel.open(family)) {
			if (type == Inet4Address.class)
				target = InetAddress.getByAddress(new byte[] { 8, 8, 8, 8 });
			if (type == Inet6Address.class)
				target = InetAddress.getByName("2001:4860:4860::8888");

			chan.connect(new InetSocketAddress(target, 63));

			InetSocketAddress soa = (InetSocketAddress) chan.getLocalAddress();
			InetAddress local = soa.getAddress();

			if (type.isInstance(local) && !local.isAnyLocalAddress())
				return local;
			return null;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Convert the socket address to readable string with align support.
	 *
	 * @param sockAddr the socket address object to stringify.
	 * @param align whether to generate the aligned result.
	 * @return the stringify socket address.
	 */
	public static String toString(InetSocketAddress sockAddr, boolean align) {
		InetAddress addr = sockAddr.getAddress();
		int port = sockAddr.getPort();

		if (align)
			return addr instanceof Inet6Address ?
					String.format("%41s:%-5d", "[" + addr.getHostAddress() + "]", port)
					: String.format("%15s:%-5d", addr.getHostAddress(), port);
		else
			return (addr instanceof Inet6Address ?
					"[" + addr.getHostAddress() + "]" : addr.getHostAddress()) + ":"
					+ port;
	}

	/**
	 * Convert the socket address to readable string.
	 *
	 * @param sockAddr the socket address object to stringify.
	 * @return the stringify socket address.
	 */
	public static String toString(InetSocketAddress sockAddr) {
		return toString(sockAddr, false);
	}
}