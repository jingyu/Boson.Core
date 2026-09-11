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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.junit.jupiter.api.Test;

public class AddressUtilsTests {
	@Test
	void testIsGlobalUnicast() throws UnknownHostException {
		// IPv4 global unicast
		assertTrue(AddressUtils.isGlobalUnicast(InetAddress.getByName("8.8.8.8")), "Public IPv4 should be global unicast");
		assertFalse(AddressUtils.isGlobalUnicast(InetAddress.getByName("192.168.1.1")), "Private IPv4 should not be global unicast");
		assertFalse(AddressUtils.isGlobalUnicast(InetAddress.getByName("0.0.0.0")), "Any local IPv4 should not be global unicast");
		assertFalse(AddressUtils.isGlobalUnicast(InetAddress.getByName("169.254.1.0")), "Private IPv4 should not be global unicast");
		assertFalse(AddressUtils.isGlobalUnicast(InetAddress.getByName("127.0.0.15")), "Loopback IPv4 should not be global unicast");

		// IPv6 global unicast
		assertTrue(AddressUtils.isGlobalUnicast(InetAddress.getByName("2001:470::1")), "Global unicast IPv6 should be detected");
		assertTrue(AddressUtils.isGlobalUnicast(InetAddress.getByName("2001:4860:4860::8888")), "Global unicast IPv6 should be detected");
		assertFalse(AddressUtils.isGlobalUnicast(InetAddress.getByName("::0")), "Any local IPv6 should not be global unicast");
		assertFalse(AddressUtils.isGlobalUnicast(InetAddress.getByName("2001:db8::1")), "Documentation IPv6 should not be global unicast");
		assertFalse(AddressUtils.isGlobalUnicast(InetAddress.getByName("fe80::1")), "Link-local IPv6 should not be global unicast");
		assertFalse(AddressUtils.isGlobalUnicast(InetAddress.getByName("2001:0::1")), "Teredo IPv6 should not be global unicast");
		assertFalse(AddressUtils.isGlobalUnicast(InetAddress.getByName("2002::1")), "6to4 IPv6 should not be global unicast");
		assertFalse(AddressUtils.isGlobalUnicast(InetAddress.getByName("::1")), "Loopback IPv6 should not be global unicast");

		// IPv4-mapped addresses
		assertTrue(AddressUtils.isGlobalUnicast(InetAddress.getByName("::ffff:8.8.8.8")), "IPv4-mapped public should be global unicast");
		assertFalse(AddressUtils.isGlobalUnicast(InetAddress.getByName("::ffff:192.168.1.1")), "IPv4-mapped private should not be global unicast");

		// Null address
		assertThrows(NullPointerException.class, () -> AddressUtils.isGlobalUnicast(null),
				"Null address should throw NullPointerException");
	}

	@Test
	void testIsAnyUnicast() throws UnknownHostException {
		// Unicast addresses
		assertTrue(AddressUtils.isAnyUnicast(InetAddress.getByName("8.8.8.8")), "Public IPv4 should be unicast");
		assertTrue(AddressUtils.isAnyUnicast(InetAddress.getByName("192.168.1.1")), "Private IPv4 should be unicast");
		assertTrue(AddressUtils.isAnyUnicast(InetAddress.getByName("2001:470::1")), "Global unicast IPv6 should be unicast");
		assertTrue(AddressUtils.isAnyUnicast(InetAddress.getByName("2001:db8::1")), "Documentation IPv6 should be unicast");

		// Non-unicast addresses
		assertFalse(AddressUtils.isAnyUnicast(InetAddress.getByName("0.0.0.0")), "Any-local IPv4 should not be unicast");
		assertFalse(AddressUtils.isAnyUnicast(InetAddress.getByName("127.0.0.1")), "Loopback IPv4 should not be unicast");
		assertFalse(AddressUtils.isAnyUnicast(InetAddress.getByName("169.254.0.1")), "Link-local IPv4 should not be unicast");
		assertFalse(AddressUtils.isAnyUnicast(InetAddress.getByName("224.0.0.1")), "Multicast IPv4 should not be unicast");
		assertFalse(AddressUtils.isAnyUnicast(InetAddress.getByName("::")), "Any-local IPv6 should not be unicast");
		assertFalse(AddressUtils.isAnyUnicast(InetAddress.getByName("::1")), "Loopback IPv6 should not be unicast");
		assertFalse(AddressUtils.isAnyUnicast(InetAddress.getByName("fe80::1")), "Link-local IPv6 should not be unicast");
		assertFalse(AddressUtils.isAnyUnicast(InetAddress.getByName("ff02::1")), "Multicast IPv6 should not be unicast");

		// Null address
		assertThrows(NullPointerException.class, () -> AddressUtils.isAnyUnicast(null),
				"Null address should throw NullPointerException");
	}

	@Test
	public void testIsBogon() throws Exception {
		// IPv4 addresses
		assertFalse(AddressUtils.isBogon(InetAddress.getByName("8.8.8.8")), "Public IPv4 should not be Bogon");
		assertFalse(AddressUtils.isBogon(InetAddress.getByName("151.101.2.132")), "Public IPv4 should not be Bogon");
		assertTrue(AddressUtils.isBogon(InetAddress.getByName("192.168.1.1")), "Private IPv4 should be Bogon");
		assertTrue(AddressUtils.isBogon(InetAddress.getByName("10.0.0.1")), "Private IPv4 should be Bogon");
		assertTrue(AddressUtils.isBogon(InetAddress.getByName("0.0.0.0")), "Any-local IPv4 should be Bogon");
		assertTrue(AddressUtils.isBogon(InetAddress.getByName("127.0.0.1")), "Loopback IPv4 should be Bogon");
		assertTrue(AddressUtils.isBogon(InetAddress.getByName("192.0.2.1")), "TEST-NET-1 should be Bogon");
		assertTrue(AddressUtils.isBogon(InetAddress.getByName("255.255.255.255")), "Broadcast should be Bogon");

		// IPv6 addresses
		assertFalse(AddressUtils.isBogon(InetAddress.getByName("2001:470::1")), "Global unicast IPv6 should not be Bogon");
		assertFalse(AddressUtils.isBogon(InetAddress.getByName("::ffff:8.8.8.8")), "IPv4-mapped public should not be Bogon");
		assertTrue(AddressUtils.isBogon(InetAddress.getByName("2001:0::1")), "Teredo IPv6 should not be Bogon");
		assertTrue(AddressUtils.isBogon(InetAddress.getByName("2001:db8::1")), "Documentation IPv6 should be Bogon");
		assertTrue(AddressUtils.isBogon(InetAddress.getByName("fe80::1")), "Link-local IPv6 should be Bogon");
		assertTrue(AddressUtils.isBogon(InetAddress.getByName("::1")), "Loopback IPv6 should be Bogon");
		assertTrue(AddressUtils.isBogon(InetAddress.getByName("2002::1")), "6to4 IPv6 should be Bogon");
		assertTrue(AddressUtils.isBogon(InetAddress.getByName("::ffff:192.168.1.1")), "IPv4-mapped private should be Bogon");

		// Null address
		assertThrows(NullPointerException.class, () -> AddressUtils.isBogon((InetAddress) null),
				"Null InetAddress should throw NullPointerException");
	}

	@Test
	void testIsMartian() throws UnknownHostException {
		// IPv4 Martian addresses
		assertTrue(AddressUtils.isMartian(InetAddress.getByName("192.168.1.1")), "Private IPv4 should be Martian");
		assertTrue(AddressUtils.isMartian(InetAddress.getByName("10.0.0.1")), "Private IPv4 should be Martian");
		assertTrue(AddressUtils.isMartian(InetAddress.getByName("127.0.0.1")), "Loopback IPv4 should be Martian");
		assertTrue(AddressUtils.isMartian(InetAddress.getByName("0.0.0.0")), "Any-local IPv4 should be Martian");
		assertTrue(AddressUtils.isMartian(InetAddress.getByName("169.254.0.1")), "Link-local IPv4 should be Martian");
		assertTrue(AddressUtils.isMartian(InetAddress.getByName("224.0.0.1")), "Multicast IPv4 should be Martian");

		// IPv6 Martian addresses
		assertTrue(AddressUtils.isMartian(InetAddress.getByName("fe80::1")), "Link-local IPv6 should be Martian");
		assertTrue(AddressUtils.isMartian(InetAddress.getByName("::1")), "Loopback IPv6 should be Martian");
		assertTrue(AddressUtils.isMartian(InetAddress.getByName("::")), "Any-local IPv6 should be Martian");
		assertTrue(AddressUtils.isMartian(InetAddress.getByName("ff02::1")), "Multicast IPv6 should be Martian");

		// Non-Martian addresses
		assertFalse(AddressUtils.isMartian(InetAddress.getByName("8.8.8.8")), "Public IPv4 should not be Martian");
		assertFalse(AddressUtils.isMartian(InetAddress.getByName("2001:470::1")), "Global unicast IPv6 should not be Martian");

		// Null address
		assertThrows(NullPointerException.class, () -> AddressUtils.isMartian(null),
				"Null address should throw NullPointerException");
	}

	@Test
	void testIsTeredo() throws UnknownHostException {
		assertTrue(AddressUtils.isTeredo(InetAddress.getByName("2001:0::1")), "Teredo address should be detected");
		assertFalse(AddressUtils.isTeredo(InetAddress.getByName("2001:470::1")), "Non-Teredo IPv6 should not be detected");
		assertFalse(AddressUtils.isTeredo(InetAddress.getByName("8.8.8.8")), "IPv4 address should not be Teredo");
		assertFalse(AddressUtils.isTeredo(InetAddress.getByName("::ffff:8.8.8.8")), "IPv4-mapped address should not be Teredo");
	}

	@Test
	void testIs6to4() throws UnknownHostException {
		assertTrue(AddressUtils.is6to4(InetAddress.getByName("2002::1")), "6to4 address should be detected");
		assertFalse(AddressUtils.is6to4(InetAddress.getByName("2001:470::1")), "Non-6to4 IPv6 should not be detected");
		assertFalse(AddressUtils.is6to4(InetAddress.getByName("8.8.8.8")), "IPv4 address should not be 6to4");
		assertFalse(AddressUtils.is6to4(InetAddress.getByName("::ffff:8.8.8.8")), "IPv4-mapped address should not be 6to4");
	}

	@Test
	void testIsValidBindAddress() throws UnknownHostException {
		// Any-local addresses
		assertTrue(AddressUtils.isValidBindAddress(InetAddress.getByName("0.0.0.0")), "IPv4 any-local should be bindable");
		assertTrue(AddressUtils.isValidBindAddress(InetAddress.getByName("::")), "IPv6 any-local should be bindable");

		// Local interface addresses (depends on system configuration)
		InetAddress localAddr = InetAddress.getLocalHost();
		if (!localAddr.isLoopbackAddress()) {
			assertTrue(AddressUtils.isValidBindAddress(localAddr), "Local address should be bindable if interface is up");
		}

		List<InetAddress> addrs = AddressUtils.getAllAddresses()
				.filter(Inet4Address.class::isInstance)
				.filter(AddressUtils::isAnyUnicast)
				.distinct()
				.toList();
		addrs.forEach(System.out::println);
		addrs.forEach(AddressUtils::isValidBindAddress);

		// Loopback address (not bindable)
		assertFalse(AddressUtils.isValidBindAddress(InetAddress.getByName("127.0.0.1")), "Loopback address should not be bindable");

		// Null address
		assertThrows(NullPointerException.class, () -> AddressUtils.isValidBindAddress(null),
				"Null address should throw NullPointerException");
	}

	@Test
	public void getAllAddress() {
		List<InetAddress> addrs = AddressUtils.getAllAddresses().toList();
		addrs.forEach(System.out::println);
		assertFalse(addrs.isEmpty());
	}

	@Test
	void testGetNonlocalAddresses() {
		AddressUtils.getNonlocalAddresses().forEach(addr -> {
			assertNotNull(addr, "Address should not be null");
			assertFalse(addr.isAnyLocalAddress(), "Address should not be any-local");
			assertFalse(addr.isLoopbackAddress(), "Address should not be loopback");
			assertFalse(addr.isLinkLocalAddress(), "Address should not be link-local");
			assertFalse(addr.isMulticastAddress(), "Address should not be multicast");
		});
	}

	@Test
	void testGetAnyLocalAddress() throws UnknownHostException {
		// IPv4 wildcard
		InetAddress ipv4Any = AddressUtils.getAnyLocalAddress(Inet4Address.class);
		assertTrue(ipv4Any.isAnyLocalAddress(), "IPv4 wildcard should be any-local");
		assertEquals(InetAddress.getByName("0.0.0.0"), ipv4Any, "IPv4 wildcard should be 0.0.0.0");

		// IPv6 wildcard
		InetAddress ipv6Any = AddressUtils.getAnyLocalAddress(Inet6Address.class);
		assertTrue(ipv6Any.isAnyLocalAddress(), "IPv6 wildcard should be any-local");
		assertEquals(InetAddress.getByName("::"), ipv6Any, "IPv6 wildcard should be ::");

		// Invalid type
		assertThrows(IllegalArgumentException.class, () -> AddressUtils.getAnyLocalAddress(null),
				"Unsupported type should throw IllegalArgumentException");
	}

	@Test
	void testGetDefaultRouteAddress() {
		// IPv4 default route
		InetAddress ipv4Route = AddressUtils.getDefaultRouteAddress(Inet4Address.class);
		if (ipv4Route != null) {
			assertInstanceOf(Inet4Address.class, ipv4Route, "IPv4 route should be Inet4Address");
			assertFalse(ipv4Route.isAnyLocalAddress(), "IPv4 route should not be any-local");
		}

		// IPv6 default route
		/*/
		InetAddress ipv6Route = AddressUtils.getDefaultRouteAddress(Inet6Address.class);
		if (ipv6Route != null) {
			assertInstanceOf(Inet6Address.class, ipv6Route, "IPv6 route should be Inet6Address");
			assertFalse(ipv6Route.isAnyLocalAddress(), "IPv6 route should not be any-local");
		}
		*/

		// Invalid type
		assertThrows(IllegalArgumentException.class, () -> AddressUtils.getDefaultRouteAddress(null),
				"Unsupported type should throw IllegalArgumentException");
	}

	@Test
	void testSubnetMatching() throws UnknownHostException {
		// Test IPv4 subnet
		AddressUtils.Subnet subnet1 = AddressUtils.Subnet.of("192.168.1.0/24");
		assertTrue(subnet1.contains(InetAddress.getByName("192.168.1.1")), "192.168.1.1 should be in 192.168.1.0/24");
		assertFalse(subnet1.contains(InetAddress.getByName("192.168.2.1")), "192.168.2.1 should not be in 192.168.1.0/24");
		assertFalse(subnet1.contains(InetAddress.getByName("2001:470::1")), "IPv6 address should not match IPv4 subnet");

		// Test IPv6 subnet
		AddressUtils.Subnet subnet2 = AddressUtils.Subnet.of("2001:db8::/32");
		assertTrue(subnet2.contains(InetAddress.getByName("2001:db8::1")), "2001:db8::1 should be in 2001:db8::/32");
		assertFalse(subnet2.contains(InetAddress.getByName("2001:470::1")), "2001:470::1 should not be in 2001:db8::/32");
		assertFalse(subnet2.contains(InetAddress.getByName("8.8.8.8")), "IPv4 address should not match IPv6 subnet");

		// Test IPv4-mapped subnet (::ffff:0:0/96)
		AddressUtils.Subnet subnet3 = AddressUtils.Subnet.of("::ffff:0:0/96");
		// assertTrue(subnet3.contains(InetAddress.getByName("::ffff:8.8.8.8")), "IPv4-mapped public should be in ::ffff:0:0/96");
		// assertTrue(subnet3.contains(InetAddress.getByName("::ffff:192.168.1.1")), "IPv4-mapped private should be in ::ffff:0:0/96");
		assertFalse(subnet3.contains(InetAddress.getByName("2001:470::1")), "Non-IPv4-mapped IPv6 should not be in ::ffff:0:0/96");

		// Test invalid CIDR
		assertThrows(IllegalArgumentException.class, () -> AddressUtils.Subnet.of("192.168.1.0/33"),
				"Invalid mask bits should throw IllegalArgumentException");
		assertThrows(IllegalArgumentException.class, () -> AddressUtils.Subnet.of("invalid-cidr"),
				"Invalid CIDR should throw IllegalArgumentException");
	}

	/**
	 * Tests the Subnet.fromInetAddress method for creating subnets from InetAddress.
	 */
	@Test
	void testSubnetFromInetAddress() throws UnknownHostException {
		// Valid IPv4 subnet
		AddressUtils.Subnet subnet1 = AddressUtils.Subnet.of(InetAddress.getByName("192.168.1.0"), 24);
		assertTrue(subnet1.contains(InetAddress.getByName("192.168.1.1")), "192.168.1.1 should be in subnet");

		// Valid IPv6 subnet
		AddressUtils.Subnet subnet2 = AddressUtils.Subnet.of(InetAddress.getByName("2001:db8::"), 32);
		assertTrue(subnet2.contains(InetAddress.getByName("2001:db8::1")), "2001:db8::1 should be in subnet");

		// Invalid mask bits
		assertThrows(IllegalArgumentException.class,
				() -> AddressUtils.Subnet.of(InetAddress.getByName("192.168.1.0"), 33),
				"Invalid IPv4 mask bits should throw IllegalArgumentException");
		assertThrows(IllegalArgumentException.class,
				() -> AddressUtils.Subnet.of(InetAddress.getByName("2001:db8::"), 129),
				"Invalid IPv6 mask bits should throw IllegalArgumentException");

		// Null address
		assertThrows(NullPointerException.class,
				() -> AddressUtils.Subnet.of(null, 24),
				"Null address should throw NullPointerException");
	}

	@Test
	void testIPv4Accepted() {
		String[] accepted = {
				"0.0.0.0", "1.2.3.4", "8.8.8.8", "127.0.0.1", "192.168.0.1",
				"255.255.255.255", "10.0.0.255", "172.16.254.1", "0.0.0.1",
		};

		for (String addr : accepted)
			assertTrue(AddressUtils.isIPv4Literal(addr), addr);
	}

	@Test
	void testIPv4Rejected() {
		String[] rejected = {
				"", ".", "1.2.3", "1.2.3.4.5", "1.2.3.4.", ".1.2.3", "1..2.3", "256.1.1.1",
				"1.2.3.256", "999.1.1.1", "1.2.3.-4", "-1.2.3.4", "+1.2.3.4", "a.b.c.d",
				"1.2.3.4 ", " 1.2.3.4", "1.2.3.4:80", "1.2.3.4%eth0", "1,2,3,4",
				"0x7f.0.0.1", "1.2.3.4/24", "\uFF11.2.3.4",
		};

		for (String addr : rejected)
			assertFalse(AddressUtils.isIPv4Literal(addr), addr);
	}

	/**
	 * The JDK's {@code InetAddress.getByName} resolves all of these; they are rejected here
	 * because a validator that accepts them disagrees with itself about what an octet is.
	 * Short forms are BSD legacy ({@code 16909060} == 1.0.0.4) and leading zeros are octal to
	 * some resolvers and decimal to others - the ambiguity behind CVE-2021-29441 style SSRF
	 * bypasses.
	 */
	@Test
	void testIPv4RejectedByDesign() {
		String[] rejected = {
				"1", "1.2", "1.2.3", "16909060", "01.2.3.4", "1.02.3.4", "1.2.3.04", "00.0.0.0",
		};

		for (String addr : rejected)
			assertFalse(AddressUtils.isIPv4Literal(addr), addr);
	}

	@Test
	void testIPv6Accepted() {
		String[] accepted = {
				"::", "::0", "::1", "1::", "fe80::1", "ff01::101", "2001:db8::1",
				"0:0:0:0:0:0:0:1", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7:A",
				"2001:db8::8:800:200c:417a", "2001:0db8:0000:0000:0008:0800:200c:417a",
				"ABCD:EF01:2345:6789:ABCD:EF01:2345:6789",
				// "::" stands for exactly one group here - the JDK accepts it, so this does too.
				"1:2:3:4:5:6:7::", "::1:2:3:4:5:6:7", "1:2:3:4:5:6::7",
				// embedded dotted-quad tails
				"::ffff:192.168.0.1", "::FFFF:192.168.0.1", "::1.2.3.4", "64:ff9b::192.0.2.33",
				"0:0:0:0:0:ffff:192.168.1.1", "1:2:3:4:5:6:1.2.3.4", "::ffff:0:1.2.3.4",
				"1:2:3:4:5::1.2.3.4",
		};

		for (String addr : accepted)
			assertTrue(AddressUtils.isIPv6Literal(addr), addr);
	}

	@Test
	void testIPv6AcceptedWithBrackets() {
		String[] accepted = {
				"[::]", "[::1]", "[fe80::1]", "[2001:db8::1]", "[1:2:3:4:5:6:7:8]",
				"[::ffff:192.168.0.1]", "[0:0:0:0:0:0:0:1]",
		};

		for (String addr : accepted)
			assertTrue(AddressUtils.isIPv6Literal(addr), addr);
	}

	@Test
	void testIPv6AcceptedWithZone() {
		String[] accepted = {
				"fe80::1%eth0", "fe80::1%en0", "::1%lo", "::1%1", "fe80::1%12",
				"fe80::1%vlan.100", "fe80::1%utun_0", "fe80::1%25eth0",
				"[fe80::1%eth0]", "[fe80::1%25eth0]", "[::1%1]",
		};

		for (String addr : accepted)
			assertTrue(AddressUtils.isIPv6Literal(addr), addr);
	}

	@Test
	void testIPv6Rejected() {
		String[] rejected = {
				"", ":", ":::", "::::", "1:::2", "1::2::3", "::1::2", "1:2",
				"1:2:3:4:5:6:7", "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:8::",
				"12345::1", "gggg::1", "1:2:3:4:5:6:7:g", "0x1::", "1:2:3:4:5:6:7:8:",
				":1:2:3:4:5:6:7:8", " 1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7:8 ",
				"::ffff:1.2.3", "::ffff:1.2.3.4.5", "::ffff:256.0.0.1", "::ffff:01.2.3.4",
				"1:2:3:4:5:6:1.2.3.4:8", "1.2.3.4::5", "1.2.3.4:5:6", "1:2:3:4:5:6::1.2.3.4",
				// Groups longer than four hex digits are rejected here per RFC 4291 even when
				// the value still fits in 16 bits. The JDK's parser bounds the group by value
				// rather than by digit count and accepts every one of these.
				"00001::1", "0ffff::1", "049fd::d7", "002E81::Ad", "00000:0:0:0:0:0:0:1",
				"0000000000000001::1",
		};

		for (String addr : rejected)
			assertFalse(AddressUtils.isIPv6Literal(addr), addr);

		// A bare IPv4 literal is not an IPv6 literal - use "::ffff:1.2.3.4" for that - but it
		// is of course still an IPv4 literal, hence the IPv6-only assertion.
		assertFalse(AddressUtils.isIPv6Literal("1.2.3.4"));
		assertFalse(AddressUtils.isIPv6Literal("0.0.0.0"));
	}

	@Test
	void testIPv6RejectedBracketsAndZones() {
		String[] rejected = {
				"[]", "[", "]", "[::1", "::1]", "[::1]]", "[[::1]", "[::1]:80", " [::1]",
				"[::1] ", "[::1]x", "1.2.3.4]", "[1.2.3.4]", "[0.0.0.0]",
				"fe80::1%", "fe80::1% ", "fe80::1%eth 0", "fe80::1%eth0%1", "fe80::1%eth/0",
				"fe80::1%[eth0]", "[fe80::1%]", "[fe80::1%eth:0]",
		};

		for (String addr : rejected)
			assertFalse(AddressUtils.isIPv6Literal(addr), addr);
	}

	@Test
	void testNullOrEmptyIsRejected() {
		assertFalse(AddressUtils.isIPv4Literal(null));
		assertFalse(AddressUtils.isIPv4Literal(""));
		assertFalse(AddressUtils.isIPv6Literal(null));
		assertFalse(AddressUtils.isIPv6Literal(""));
	}

	@Test
	void testEndpointOf() {
		assertEquals("1.2.3.4:80", AddressUtils.endpointOf("1.2.3.4", 80));
		assertEquals("203.0.113.10:40000", AddressUtils.endpointOf("203.0.113.10", 40000));
		assertEquals("[1:2:3:4:5:6:7:8]:80", AddressUtils.endpointOf("1:2:3:4:5:6:7:8", 80));
		assertEquals("[1:2:3:4:5:6:7:8]:80", AddressUtils.endpointOf("[1:2:3:4:5:6:7:8]", 80));
		assertEquals("proxy.example.com:40000", AddressUtils.endpointOf("proxy.example.com", 40000));
		assertEquals("[2001:db8::1]:40000", AddressUtils.endpointOf("2001:db8::1", 40000));
		assertEquals("[2001:db8::1]:40000", AddressUtils.endpointOf("[2001:db8::1]", 40000));
		assertEquals("[::1]:40000", AddressUtils.endpointOf("::1", 40000));
	}

	/**
	 * literalAddress must never resolve a name. A host name, and every form the JDK would accept or
	 * look up but these validators reject, comes back as null rather than as a lookup result.
	 */
	@Test
	void testLiteralAddressNeverResolvesNames() {
		String[] notLiterals = {
				"localhost", "example.com", "proxy.internal", "1.2", "16909060", "01.2.3.4", "00001::1",
				"[1.2.3.4]", "", " 1.2.3.4",
		};

		for (String addr : notLiterals)
			assertNull(AddressUtils.literalAddress(addr), addr);
		assertNull(AddressUtils.literalAddress(null));

		// Syntactically a literal, but the zone names no interface here, so it cannot be parsed.
		assertNull(AddressUtils.literalAddress("fe80::1%no-such-interface-xyz"));
	}

	@Test
	void testLiteralAddressParsesLiterals() throws Exception {
		assertEquals(InetAddress.getByName("192.168.0.1"), AddressUtils.literalAddress("192.168.0.1"));
		assertEquals(InetAddress.getByName("::1"), AddressUtils.literalAddress("::1"));
		assertEquals(InetAddress.getByName("::1"), AddressUtils.literalAddress("[::1]"));
		assertEquals(InetAddress.getByName("2001:db8::1"), AddressUtils.literalAddress("2001:db8::1"));
		// An IPv4-mapped IPv6 literal comes back as the IPv4 address, as the JDK maps it.
		assertInstanceOf(Inet4Address.class, AddressUtils.literalAddress("::ffff:192.168.0.1"));
	}

	@Test
	void testIsWildcard() {
		for (String wildcard : new String[] { "0.0.0.0", "::", "::0", "[::]", "0:0:0:0:0:0:0:0", "::ffff:0.0.0.0" })
			assertTrue(AddressUtils.isWildcard(wildcard), wildcard);

		// Host names are never resolved, so even one that means "this machine" is not a wildcard.
		for (String concrete : new String[] { "127.0.0.1", "::1", "[::1]", "192.168.0.1", "localhost",
				"example.com", "0.0.0.0.", "00.0.0.0", "" })
			assertFalse(AddressUtils.isWildcard(concrete), concrete);
		assertFalse(AddressUtils.isWildcard(null));
	}
}
