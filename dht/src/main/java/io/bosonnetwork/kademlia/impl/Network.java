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

package io.bosonnetwork.kademlia.impl;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;

/**
 * Defined the DHT network types.
 */
public enum Network {
	/**
	 * IPv4 network.
	 */
	IPv4(StandardProtocolFamily.INET, Inet4Address.class, 20 + 8, 1400),

	/**
	 * IPv6 network.
	 */
	IPv6(StandardProtocolFamily.INET6, Inet6Address.class, 40 + 8, 1200);

	private final StandardProtocolFamily protocolFamily;
	private final Class<? extends InetAddress> preferredAddressType;
	private final int protocolHeaderSize;
	private final int maxPacketSize;

	/**
	 * Constructs a Network enum constant with the specified protocol family, preferred address type,
	 * UDP protocol header size, and maximum UDP payload size.
	 *
	 * @param family the protocol family (e.g., INET or INET6)
	 * @param addressType the preferred InetAddress subclass for this network (e.g., Inet4Address or Inet6Address)
	 * @param headerSize the size in bytes of the network and UDP headers for this protocol
	 * @param maxPacketSize the maximum UDP payload size for this network type; see {@link #maxPacketSize()}
	 */
	Network(StandardProtocolFamily family, Class<? extends InetAddress> addressType, int headerSize, int maxPacketSize) {
		this.protocolFamily = family;
		this.preferredAddressType = addressType;
		this.protocolHeaderSize = headerSize;
		this.maxPacketSize = maxPacketSize;
	}

	/**
	 * Checks if the specified socket address can apply for this network.
	 *
	 * @param addr the socket address to check.
	 * @return true if the address can apply for this network, otherwise false.
	 */
	public boolean canUseSocketAddress(InetSocketAddress addr) {
		return canUseAddress(addr.getAddress());
	}

	/**
	 * Checks if the specified IP address can apply for this network.
	 *
	 * @param addr the IP address to check.
	 * @return true if the address can apply for this network, otherwise false.
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
	public StandardProtocolFamily protocolFamily() {
		return protocolFamily;
	}

	/**
	 * Get the combined IP and UDP header size of this network type.
	 * <p>
	 * This documents the other half of the {@link #maxPacketSize()} arithmetic rather than being
	 * something a sender subtracts: the budget already excludes these bytes, so subtracting them a
	 * second time silently shrinks every message. It is here so the relationship
	 * {@code maxPacketSize() + protocolHeaderSize() <= path MTU} can be checked against a real link.
	 * </p>
	 *
	 * @return the IP and UDP header size in bytes.
	 */
	public int protocolHeaderSize() {
		return protocolHeaderSize;
	}

	/**
	 * Get the maximum UDP payload size of this network type - the budget a message must fit in to
	 * travel as a single unfragmented datagram.
	 * <p>
	 * This is the <b>payload</b>, not the IP packet: the packet on the wire is this plus
	 * {@link #protocolHeaderSize()}. A message that exceeds the path MTU is fragmented, and a
	 * fragmented UDP datagram is lost entirely if any one fragment is lost - middleboxes commonly drop
	 * fragments outright - so overshooting does not degrade gradually, it turns a working exchange into
	 * a silent black hole on some paths.
	 * </p>
	 * <p>
	 * <b>IPv6, 1200.</b> With the 48-byte header that is a 1248-byte packet, inside the 1280-byte
	 * minimum link MTU that IPv6 guarantees (RFC 8200). The same floor QUIC mandates, and for the same
	 * reason: it is the largest size that needs no path discovery to be safe.
	 * </p>
	 * <p>
	 * <b>IPv4, 1400.</b> With the 28-byte header that is a 1428-byte packet. The Ethernet limit of 1500
	 * would allow more, but a client is rarely on plain Ethernet end to end: GRE caps at 1476, DS-Lite
	 * and 6in4 around 1460-1480, IPsec around 1400-1438. Sizing to the medium rather than to the
	 * tunnels above it means the loss shows up only for the clients behind one, which is the hardest
	 * form of this defect to diagnose.
	 * </p>
	 *
	 * @return the maximum UDP payload size in bytes.
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