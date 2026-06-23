package io.bosonnetwork;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.json.Json;
import io.bosonnetwork.utils.Hex;

public class NodeInfoTests {
	@Test
	void testConstructors4() throws Exception {
		Id id = Id.random();
		String host = "203.0.113.10";
		int port = 12345;
		InetAddress addr = InetAddress.getByName(host);
		InetSocketAddress socketAddr = new InetSocketAddress(addr, port);

		// Test constructor with InetSocketAddress
		NodeInfo ni1 = NodeInfo.of(id, socketAddr);
		assertEquals(id, ni1.getId());
		assertEquals(socketAddr, ni1.getAddress4());
		assertEquals(host, ni1.getHost4());
		assertEquals(port, ni1.getPort4());

		// Test constructor with InetAddress and port
		NodeInfo ni2 = NodeInfo.of(id, addr, port);
		assertEquals(id, ni2.getId());
		assertEquals(socketAddr, ni2.getAddress4());

		// Test constructor with host string and port
		NodeInfo ni3 = NodeInfo.of(id, host, port);
		assertEquals(id, ni3.getId());
		assertEquals(socketAddr, ni3.getAddress4());

		// Test constructor with raw byte address and port
		NodeInfo ni4 = NodeInfo.of(id, addr.getAddress(), port);
		assertEquals(id, ni4.getId());
		assertEquals(socketAddr, ni4.getAddress4());

		// Test copy constructor
		ni1.setVersion(5);
		NodeInfo ni5 = new NodeInfo(ni1);
		assertEquals(ni1, ni5);
		assertEquals(5, ni5.getVersion());
	}

	@Test
	void testConstructors6() throws Exception {
		Id id = Id.random();
		String host = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";
		int port = 12345;
		InetAddress addr = InetAddress.getByName(host);
		InetSocketAddress socketAddr = new InetSocketAddress(addr, port);

		// Test constructor with InetSocketAddress
		NodeInfo ni1 = NodeInfo.of(id, socketAddr);
		assertEquals(id, ni1.getId());
		assertEquals(socketAddr, ni1.getAddress6());
		assertEquals(addr.getHostAddress(), ni1.getHost6()); // IPv6 address maybe compressed
		assertEquals(port, ni1.getPort6());

		// Test constructor with InetAddress and port
		NodeInfo ni2 = NodeInfo.of(id, addr, port);
		assertEquals(id, ni2.getId());
		assertEquals(socketAddr, ni2.getAddress6());

		// Test constructor with host string and port
		NodeInfo ni3 = NodeInfo.of(id, host, port);
		assertEquals(id, ni3.getId());
		assertEquals(socketAddr, ni3.getAddress6());

		// Test constructor with raw byte address and port
		NodeInfo ni4 = NodeInfo.of(id, addr.getAddress(), port);
		assertEquals(id, ni4.getId());
		assertEquals(socketAddr, ni4.getAddress6());

		// Test copy constructor
		ni1.setVersion(5);
		NodeInfo ni5 = new NodeInfo(ni1);
		assertEquals(ni1, ni5);
		assertEquals(5, ni5.getVersion());
	}

	@Test
	void testConstructors46() throws Exception {
		Id id = Id.random();
		String host4 = "203.0.113.10";
		int port4 = 12343;
		String host6 = "2001:0db8:85a3:0000:0000:8a2e:0370:7334";
		int port6 = 12345;
		InetAddress addr4 = InetAddress.getByName(host4);
		InetSocketAddress socketAddr4 = new InetSocketAddress(addr4, port4);
		InetAddress addr6 = InetAddress.getByName(host6);
		InetSocketAddress socketAddr6 = new InetSocketAddress(addr6, port6);

		// Test constructor with InetSocketAddress
		NodeInfo ni1 = NodeInfo.of(id, socketAddr4, socketAddr6);
		assertEquals(id, ni1.getId());
		assertEquals(socketAddr4, ni1.getAddress4());
		assertEquals(host4, ni1.getHost4());
		assertEquals(port4, ni1.getPort4());
		assertEquals(socketAddr6, ni1.getAddress6());
		assertEquals(addr6.getHostAddress(), ni1.getHost6()); // IPv6 address maybe compressed
		assertEquals(port6, ni1.getPort6());

		// Generic accessors on a dual-stack node must prefer IPv4 (and must not throw).
		assertTrue(ni1.hasMultiAddresses());
		assertEquals(socketAddr4, ni1.getAddress());
		assertEquals(addr4, ni1.getIpAddress());
		assertEquals(host4, ni1.getHost());
		assertEquals(port4, ni1.getPort());

		// narrowDown switches the generic accessors to the requested family.
		ni1.narrowDown(java.net.StandardProtocolFamily.INET6);
		assertEquals(socketAddr6, ni1.getAddress());
		assertEquals(port6, ni1.getPort());
		ni1.narrowDown(java.net.StandardProtocolFamily.INET);
		assertEquals(socketAddr4, ni1.getAddress());

		// Test constructor with InetAddress and port
		NodeInfo ni2 = NodeInfo.of(id, addr4, port4, addr6, port6);
		assertEquals(id, ni2.getId());
		assertEquals(socketAddr4, ni2.getAddress4());
		assertEquals(socketAddr6, ni2.getAddress6());

		// Test constructor with host string and port
		NodeInfo ni3 = NodeInfo.of(id, host4, port4, host6, port6);
		assertEquals(id, ni3.getId());
		assertEquals(socketAddr4, ni3.getAddress4());
		assertEquals(socketAddr6, ni3.getAddress6());

		// Test constructor with raw byte address and port
		NodeInfo ni4 = NodeInfo.of(id, addr4.getAddress(), port4, addr6.getAddress(), port6);
		assertEquals(id, ni4.getId());
		assertEquals(socketAddr4, ni4.getAddress4());
		assertEquals(socketAddr6, ni4.getAddress6());

		// Test copy constructor
		ni1.setVersion(5);
		NodeInfo ni5 = new NodeInfo(ni1);
		assertEquals(ni1, ni5);
		assertEquals(5, ni5.getVersion());
	}

	@Test
	void testInvalidConstructors() {
		Id id = Id.random();
		InetAddress addr = InetAddress.getLoopbackAddress();

		assertThrows(NullPointerException.class, () -> NodeInfo.of(null, addr, 1234));
		assertThrows(NullPointerException.class, () -> NodeInfo.of(id, (InetAddress) null, 1234));
		assertThrows(IllegalArgumentException.class, () -> NodeInfo.of(id, addr, 0));
		assertThrows(IllegalArgumentException.class, () -> NodeInfo.of(id, addr, 65536));

		assertThrows(NullPointerException.class, () -> NodeInfo.of(id, (String) null, 1234));
		assertThrows(NullPointerException.class, () -> NodeInfo.of(id, (byte[]) null, 1234));
		assertThrows(IllegalArgumentException.class, () -> NodeInfo.of(id, new byte[3], 1234)); // Invalid IP length
	}

	@Test
	void testMatches() {
		Id id1 = Id.random();
		Id id2 = Id.random();
		String host1 = "1.1.1.1";
		String host2 = "2.2.2.2";
		int port = 1234;

		NodeInfo ni1 = NodeInfo.of(id1, host1, port);
		NodeInfo ni2 = NodeInfo.of(id1, host2, port); // Same ID, different addr
		NodeInfo ni3 = NodeInfo.of(id2, host1, port); // Different ID, same addr
		NodeInfo ni4 = NodeInfo.of(id2, host2, port); // Different ID and addr

		assertTrue(ni1.matches(ni2));
		assertTrue(ni1.matches(ni3));
		assertFalse(ni1.matches(ni4));
		assertFalse(ni1.matches(null));
	}

	@Test
	void testEqualsAndHashCode() {
		Id id = Id.random();
		String host = "203.0.113.10";
		int port = 1234;

		NodeInfo ni1 = NodeInfo.of(id, host, port);
		NodeInfo ni2 = NodeInfo.of(id, host, port);
		NodeInfo ni3 = NodeInfo.of(Id.random(), host, port);

		assertEquals(ni1, ni2);
		assertEquals(ni1.hashCode(), ni2.hashCode());
		assertNotEquals(ni1, ni3);
	}

	@Test
	void testJson4() {
		Id id = Id.random();
		NodeInfo ni = NodeInfo.of(id, "203.0.113.10", 1234);

		String json = Json.toString(ni);
		System.out.println(json);
		System.out.println(Json.toPrettyString(ni));

		NodeInfo ni2 = Json.parse(json, NodeInfo.class);
		assertEquals(ni, ni2);

		String json2 = Json.toString(ni2);
		assertEquals(json, json2);
	}

	@Test
	void testJson6() {
		Id id = Id.random();
		NodeInfo ni = NodeInfo.of(id, "2001:0db8:85a3:0000:0000:8a2e:0370:7334", 1234);

		String json = Json.toString(ni);
		System.out.println(json);
		System.out.println(Json.toPrettyString(ni));

		NodeInfo ni2 = Json.parse(json, NodeInfo.class);
		assertEquals(ni, ni2);

		String json2 = Json.toString(ni2);
		assertEquals(json, json2);
	}

	@Test
	void testJson46() {
		Id id = Id.random();
		NodeInfo ni = NodeInfo.of(id, new InetSocketAddress("203.0.113.10", 1234), new InetSocketAddress("2001:0db8:85a3:0000:0000:8a2e:0370:7334", 1234));

		String json = Json.toString(ni);
		System.out.println(json);
		System.out.println(Json.toPrettyString(ni));

		NodeInfo ni2 = Json.parse(json, NodeInfo.class);
		assertEquals(ni, ni2);

		String json2 = Json.toString(ni2);
		assertEquals(json, json2);
	}

	@Test
	void testJsonWithHostName() {
		Id id = Id.random();
		NodeInfo ni = NodeInfo.of(id, "github.com", 1234);

		String json = Json.toString(ni);
		System.out.println(json);
		System.out.println(Json.toPrettyString(ni));

		NodeInfo ni2 = Json.parse(json, NodeInfo.class);
		assertEquals(ni, ni2);

		String json2 = Json.toString(ni2);
		assertEquals(json, json2);
	}

	@Test
	void testCbor4() {
		Id id = Id.random();
		NodeInfo ni = NodeInfo.of(id, "203.0.113.10", 1234);

		byte[] cbor = Json.toBytes(ni);
		System.out.println(Hex.encode(cbor));
		System.out.println(Json.toPrettyString(Json.parse(cbor, List.class)));

		NodeInfo ni2 = Json.parse(cbor, NodeInfo.class);
		assertEquals(ni, ni2);

		byte[] cbor2 = Json.toBytes(ni2);
		assertArrayEquals(cbor, cbor2);
	}

	@Test
	void testCbor6() {
		Id id = Id.random();
		NodeInfo ni = NodeInfo.of(id, "2001:0db8:85a3:0000:0000:8a2e:0370:7334", 1234);

		byte[] cbor = Json.toBytes(ni);
		System.out.println(Hex.encode(cbor));
		System.out.println(Json.toPrettyString(Json.parse(cbor, List.class)));

		NodeInfo ni2 = Json.parse(cbor, NodeInfo.class);
		assertEquals(ni, ni2);

		byte[] cbor2 = Json.toBytes(ni2);
		assertArrayEquals(cbor, cbor2);
	}

	@Test
	void testCbor46() {
		Id id = Id.random();
		NodeInfo ni = NodeInfo.of(id, new InetSocketAddress("203.0.113.10", 1234), new InetSocketAddress("2001:0db8:85a3:0000:0000:8a2e:0370:7334", 1234));

		byte[] cbor = Json.toBytes(ni);
		System.out.println(Hex.encode(cbor));
		System.out.println(Json.toPrettyString(Json.parse(cbor, List.class)));

		NodeInfo ni2 = Json.parse(cbor, NodeInfo.class);
		assertEquals(ni, ni2);

		byte[] cbor2 = Json.toBytes(ni2);
		assertArrayEquals(cbor, cbor2);
	}

	@Test
	void testCborWithUnresolvedHostName() {
		Id id = Id.random();
		assertThrows(IllegalArgumentException.class, () -> NodeInfo.of(id, "non-exists-host.com", 1234));
	}

	@Test
	void testAddressOrderIsLenient() {
		// Deserialization is family-aware: an IPv6 address listed before IPv4 is accepted and routed
		// to the correct slot regardless of position.
		String json1 = "[\"GZgsJAKT9SCVsro1Uj7npAe88E7j5jWawZyYbdES1yJJ\",\"2001:db8:85a3:0:0:8a2e:370:7334\",1234,\"203.0.113.10\",5678]";
		NodeInfo ni = Json.parse(json1, NodeInfo.class);
		assertTrue(ni.hasMultiAddresses());
		assertEquals(5678, ni.getPort4());
		assertEquals(1234, ni.getPort6());
		assertEquals("203.0.113.10", ni.getHost4());
	}

	@Test
	void testDuplicateAddressFamilyRejected() {
		// Two addresses of the same family is invalid (a node has at most one IPv4 and one IPv6).
		String json2 = "[\"GZgsJAKT9SCVsro1Uj7npAe88E7j5jWawZyYbdES1yJJ\",\"203.0.113.10\",1234,\"203.0.113.11\",1234]";
		assertThrows(IllegalArgumentException.class, () -> Json.parse(json2, NodeInfo.class));

		String json3 = "[\"GZgsJAKT9SCVsro1Uj7npAe88E7j5jWawZyYbdES1yJJ\",\"2001:db8:85a3:0:0:8a2e:370:7333\",1234,\"2001:db8:85a3:0:0:8a2e:370:7334\",1234]";
		assertThrows(IllegalArgumentException.class, () -> Json.parse(json3, NodeInfo.class));
	}
}