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
	void testConstructors() throws Exception {
		Id id = Id.random();
		String host = "203.0.113.10";
		int port = 12345;
		InetAddress addr = InetAddress.getByName(host);
		InetSocketAddress socketAddr = new InetSocketAddress(addr, port);

		// Test constructor with InetSocketAddress
		NodeInfo ni1 = new NodeInfo(id, socketAddr);
		assertEquals(id, ni1.getId());
		assertEquals(socketAddr, ni1.getAddress());
		assertEquals(host, ni1.getHost());
		assertEquals(port, ni1.getPort());

		// Test constructor with InetAddress and port
		NodeInfo ni2 = new NodeInfo(id, addr, port);
		assertEquals(id, ni2.getId());
		assertEquals(socketAddr, ni2.getAddress());

		// Test constructor with host string and port
		NodeInfo ni3 = new NodeInfo(id, host, port);
		assertEquals(id, ni3.getId());
		assertEquals(socketAddr, ni3.getAddress());

		// Test constructor with raw byte address and port
		NodeInfo ni4 = new NodeInfo(id, addr.getAddress(), port);
		assertEquals(id, ni4.getId());
		assertEquals(socketAddr, ni4.getAddress());

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

		assertThrows(IllegalArgumentException.class, () -> new NodeInfo(null, addr, 1234));
		assertThrows(IllegalArgumentException.class, () -> new NodeInfo(id, (InetAddress) null, 1234));
		assertThrows(IllegalArgumentException.class, () -> new NodeInfo(id, addr, 0));
		assertThrows(IllegalArgumentException.class, () -> new NodeInfo(id, addr, 65536));

		assertThrows(IllegalArgumentException.class, () -> new NodeInfo(id, (String) null, 1234));
		assertThrows(IllegalArgumentException.class, () -> new NodeInfo(id, (byte[]) null, 1234));
		assertThrows(IllegalArgumentException.class, () -> new NodeInfo(id, new byte[3], 1234)); // Invalid IP length
	}

	@Test
	void testMatches() {
		Id id1 = Id.random();
		Id id2 = Id.random();
		String host1 = "1.1.1.1";
		String host2 = "2.2.2.2";
		int port = 1234;

		NodeInfo ni1 = new NodeInfo(id1, host1, port);
		NodeInfo ni2 = new NodeInfo(id1, host2, port); // Same ID, different addr
		NodeInfo ni3 = new NodeInfo(id2, host1, port); // Different ID, same addr
		NodeInfo ni4 = new NodeInfo(id2, host2, port); // Different ID and addr

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

		NodeInfo ni1 = new NodeInfo(id, host, port);
		NodeInfo ni2 = new NodeInfo(id, host, port);
		NodeInfo ni3 = new NodeInfo(Id.random(), host, port);

		assertEquals(ni1, ni2);
		assertEquals(ni1.hashCode(), ni2.hashCode());
		assertNotEquals(ni1, ni3);
	}

	@Test
	void testJson() {
		Id id = Id.random();
		NodeInfo ni = new NodeInfo(id, "203.0.113.10", 1234);

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
		NodeInfo ni = new NodeInfo(id, "github.com", 1234);

		String json = Json.toString(ni);
		System.out.println(json);
		System.out.println(Json.toPrettyString(ni));

		NodeInfo ni2 = Json.parse(json, NodeInfo.class);
		assertEquals(ni, ni2);

		String json2 = Json.toString(ni2);
		assertEquals(json, json2);
	}

	@Test
	void testCbor() {
		Id id = Id.random();
		NodeInfo ni = new NodeInfo(id, "203.0.113.10", 1234);

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
		NodeInfo ni = new NodeInfo(id, "non-exists-host.com", 1234);

		byte[] cbor = Json.toBytes(ni);
		System.out.println(Hex.encode(cbor));
		System.out.println(Json.toPrettyString(Json.parse(cbor, List.class)));

		NodeInfo ni2 = Json.parse(cbor, NodeInfo.class);
		assertEquals(ni, ni2);

		byte[] cbor2 = Json.toBytes(ni2);
		assertArrayEquals(cbor, cbor2);
	}
}