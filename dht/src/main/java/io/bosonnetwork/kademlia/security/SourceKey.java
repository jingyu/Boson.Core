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

package io.bosonnetwork.kademlia.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * Reduces a source address to the unit this node holds a sender accountable in.
 * <p>
 * Every defense here - the rate throttle and the suspicious-node detector both - assumes that the thing it
 * counts against is a resource the sender had to acquire. That assumption holds for an IPv4 address and
 * fails for a single IPv6 address: the smallest allocation an IPv6 subscriber or VPS tenant receives is a
 * routed /64, which is 1.8e19 addresses that the holder genuinely receives at. Counting per 128-bit address
 * therefore gives one sender an unlimited supply of fresh budgets, and makes any per-address ban worthless
 * against it.
 * </p>
 * <p>
 * So the unit is the allocation boundary rather than the address: <b>IPv4 /32, IPv6 /64</b>. IPv4 is left
 * whole because an individual IPv4 address is already the scarce thing; splitting IPv6 at /64 is the
 * narrowest cut that a sender cannot widen for free.
 * </p>
 * <p>
 * The throttle, the detector and the routing table's diversity budget share this so that "one source" means
 * the same thing to all of them. If any two disagreed, a sender could sit under one budget while exhausting
 * the other.
 * </p>
 * <p>
 * Public for that last one: the routing table lives in another package and must count in the same unit. The
 * definition stays here, with the defenses that established it.
 * </p>
 */
public final class SourceKey {
	/**
	 * Prefix length that defines one IPv6 source, in bits. The standard end-site allocation, so it is the
	 * smallest block a sender cannot multiply without going back to its provider.
	 */
	public static final int IPV6_PREFIX_BITS = 64;

	private static final int IPV6_PREFIX_BYTES = IPV6_PREFIX_BITS / 8;
	private static final int IPV6_BYTES = 16;

	private SourceKey() {
	}

	/**
	 * Reduces a host address, in literal string form, to its accountable unit.
	 * <p>
	 * IPv4 literals are returned unchanged and never parsed - that is the common path on the packet-receive
	 * hot loop, and it is worth keeping allocation-free. The colon test is what separates the two families;
	 * an IPv4 literal cannot contain one.
	 * </p>
	 *
	 * @param host the host address in literal form, as it arrives from the socket.
	 * @return the key to count this source under; the argument itself for IPv4.
	 */
	public static String of(String host) {
		if (host.indexOf(':') < 0)
			return host;

		try {
			return of(InetAddress.getByName(host)).getHostAddress();
		} catch (UnknownHostException e) {
			// Not a literal we can parse. Counting it under its own name is the conservative choice: it
			// keeps the traffic accounted for rather than dropping it out of every budget.
			return host;
		}
	}

	/**
	 * Reduces an address to its accountable unit.
	 * <p>
	 * An IPv4-mapped form is checked for explicitly and returned whole. Java normally hands these back as an
	 * {@link java.net.Inet4Address} already, so the branch is defensive rather than load-bearing - but
	 * masking one would clear the low 64 bits of {@code ::ffff:a.b.c.d} and drop every IPv4 source on the
	 * internet into a single bucket, which is worth one comparison to rule out.
	 * </p>
	 *
	 * @param addr the address to reduce.
	 * @return the address itself for IPv4, or the /64 network address for IPv6.
	 */
	public static InetAddress of(InetAddress addr) {
		if (!(addr instanceof Inet6Address))
			return addr;

		byte[] bytes = addr.getAddress();
		if (bytes.length != IPV6_BYTES || isIPv4Mapped(bytes))
			return addr;

		Arrays.fill(bytes, IPV6_PREFIX_BYTES, bytes.length, (byte) 0);
		try {
			return InetAddress.getByAddress(bytes);
		} catch (UnknownHostException e) {
			// Unreachable: the length is fixed by the array we just built.
			return addr;
		}
	}

	/**
	 * Tests the {@code ::ffff:0:0/96} prefix that carries an IPv4 address inside a 16-byte form.
	 *
	 * @param bytes a 16-byte address.
	 * @return true if the address is IPv4-mapped.
	 */
	private static boolean isIPv4Mapped(byte[] bytes) {
		for (int i = 0; i < 10; i++) {
			if (bytes[i] != 0)
				return false;
		}

		return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
	}
}
