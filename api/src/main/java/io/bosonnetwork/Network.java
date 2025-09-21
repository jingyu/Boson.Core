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
import java.net.ProtocolFamily;
import java.net.StandardProtocolFamily;

/**
 * Defined the DHT network types.
 */
public enum Network {
	/**
	 * IPv4 network.
	 */
	IPv4(StandardProtocolFamily.INET, Inet4Address.class, 20 + 8, 1450),

	/**
	 * IPv6 network.
	 */
	IPv6(StandardProtocolFamily.INET6, Inet6Address.class, 40 + 8, 1200);

	private final ProtocolFamily protocolFamily;
	private final Class<? extends InetAddress> preferredAddressType;
	private final int protocolHeaderSize;
	private final int maxPacketSize;

	/**
	 * Constructs a Network enum constant with the specified protocol family, preferred address type,
	 * UDP protocol header size, and maximum UDP packet size.
	 *
	 * @param family the protocol family (e.g., INET or INET6)
	 * @param addressType the preferred InetAddress subclass for this network (e.g., Inet4Address or Inet6Address)
	 * @param headerSize the size in bytes of the network and UDP headers for this protocol
	 * @param maxPacketSize the maximum supported UDP packet size for this network type
	 */
	Network(ProtocolFamily family, Class<? extends InetAddress> addressType, int headerSize, int maxPacketSize) {
		this.protocolFamily = family;
		this.preferredAddressType = addressType;
		this.protocolHeaderSize = headerSize;
		this.maxPacketSize = maxPacketSize;
	}

	/**
	 * Checks if the specified socket address can apply for this network.
	 *
	 * @param addr the socket address to check.
	 * @return true is the address can apply for this network, otherwise false.
	 */
	public boolean canUseSocketAddress(InetSocketAddress addr) {
		return canUseAddress(addr.getAddress());
	}

	/**
	 * Checks if the specified IP address can apply for this network.
	 *
	 * @param addr the IP address to check.
	 * @return true is the address can apply for this network, otherwise false.
	 */
	public boolean canUseAddress(InetAddress addr) {
		return preferredAddressType.isInstance(addr);
	}

	/**
	 * Get the {@link Network} type from the socket address.
	 *
	 * @param addr the socket address.
	 * @return the network type of the specified socket address.
	 */
	public static Network of(InetSocketAddress addr) {
		return of(addr.getAddress());
	}

	/**
	 * Get the {@link Network} type from the IP address object.
	 *
	 * @param addr the IP address object.
	 * @return the network type of the specified IP address.
	 */
	public static Network of(InetAddress addr) {
		return (addr instanceof Inet4Address) ? IPv4 : IPv6;
	}

	/**
	 * Get the ProtocolFamily of this network type.
	 *
	 * @return the ProtocolFamily of this network type.
	 */
	ProtocolFamily protocolFamily() {
		return protocolFamily;
	}

	/**
	 * Get the UDP protocol header size of this network type.
	 *
	 * @return the UDP protocol header size.
	 */
	public int protocolHeaderSize() {
		return protocolHeaderSize;
	}

	/**
	 * Get the maximum UDP packet size of this network type.
	 *
	 * @return the maximum UDP packet.
	 */
	public int maxPacketSize() {
		return maxPacketSize;
	}

	/**
	 * Determines if this network type is IPv4.
	 *
	 * @return true if this network type is IPv4, otherwise false.
	 */
	public boolean isIPv4() {
		return this == IPv4;
	}

	/**
	 * Determines if this network type is IPv6.
	 *
	 * @return true if this network type is IPv6, otherwise false.
	 */
	public boolean isIPv6() {
		return this == IPv6;
	}

	/**
	 * Returns a String object of the network name.
	 *
	 * @return the name of the network.
	 */
	@Override
	public String toString() {
		return name();
	}
}