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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.junit.jupiter.api.Disabled;
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

	@Disabled
	@Test
	void testUpdateBogonRanges() {
		// Store original subnets to restore after test
		List<?> originalIpv4Subnets = null;
		List<?> originalIpv6Subnets = null;

		try {
			Field ipv4Field = AddressUtils.class.getDeclaredField("bogonSubnetsIpv4");
			Field ipv6Field = AddressUtils.class.getDeclaredField("bogonSubnetsIpv6");
			ipv4Field.setAccessible(true);
			ipv6Field.setAccessible(true);
			originalIpv4Subnets = (List<?>) ipv4Field.get(null);
			originalIpv6Subnets = (List<?>) ipv6Field.get(null);

			// Mock network calls
			AddressUtils.updateBogonRanges();

			// Verify updated Bogon lists using reflection
			ipv4Field = AddressUtils.class.getDeclaredField("bogonSubnetsIpv4");
			ipv6Field = AddressUtils.class.getDeclaredField("bogonSubnetsIpv6");
			ipv4Field.setAccessible(true);
			ipv6Field.setAccessible(true);
			List<?> ipv4Subnets = (List<?>) ipv4Field.get(null);
			List<?> ipv6Subnets = (List<?>) ipv6Field.get(null);

			// Verify updated Bogon lists
			assertNotNull(ipv4Subnets, "IPv4 Bogon subnets should not be null");
			assertNotNull(ipv6Subnets, "IPv6 Bogon subnets should not be null");
			assertFalse(ipv4Subnets.isEmpty(), "IPv4 Bogon subnets should not be empty");
			assertFalse(ipv6Subnets.isEmpty(), "IPv6 Bogon subnets should not be empty");

			ipv4Subnets.forEach(System.out::println);
			ipv6Subnets.forEach(System.out::println);
		} catch (NoSuchFieldException | IllegalAccessException e) {
			fail(e);
		} finally {
			// Restore original subnets
			try {
				Field ipv4Field = AddressUtils.class.getDeclaredField("bogonSubnetsIpv4");
				Field ipv6Field = AddressUtils.class.getDeclaredField("bogonSubnetsIpv6");
				ipv4Field.setAccessible(true);
				ipv6Field.setAccessible(true);
				if (originalIpv4Subnets != null)
					ipv4Field.set(null, originalIpv4Subnets);
				if (originalIpv6Subnets != null)
					ipv6Field.set(null, originalIpv6Subnets);
			} catch (NoSuchFieldException | IllegalAccessException e) {
				fail(e);
			}
		}
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
}