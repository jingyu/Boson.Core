package io.bosonnetwork.kademlia;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Path;

import io.vertx.core.Vertx;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.NodeConfiguration;
import io.bosonnetwork.utils.AddressUtils;

public class TestNodeWithHostOrInterface {
	private static final Path testDir = Path.of(System.getProperty("java.io.tmpdir"), "boson", "NodeTests");

	@Test
	void testNodeOnHost() {
		String address = AddressUtils.getDefaultRouteAddress(Inet4Address.class).getHostAddress();

		System.out.printf("Address: %s%n", address);

		NodeConfiguration config = NodeConfiguration.builder()
				.vertx(Vertx.vertx())
				.host4(address)
				.port(39101)
				.dataDir(testDir)
				.generatePrivateKey()
				.build();

		KadNode node = new KadNode(config);
		node.start().join();
		assertEquals(address, node.getHost4());
		assertEquals(39101, node.getPort());
		node.stop().join();
	}

	@Test
	void testNodeOnInterface() {
		NetworkInterface nif = AddressUtils.getDefaultNetworkInterface(Inet4Address.class);
		String host = nif.inetAddresses()
				.filter(a -> a instanceof Inet4Address)
				.filter(AddressUtils::isAnyUnicast)
				.findFirst()
				.map(InetAddress::getHostAddress)
				.orElseThrow(() -> new IllegalStateException("No applicable IPv4 address found"));

		System.out.printf("Interface: %s, address: %s%n", nif.getName(), host);

		NodeConfiguration config = NodeConfiguration.builder()
				.vertx(Vertx.vertx())
				.networkInterface4(nif.getName())
				.port(39101)
				.dataDir(testDir)
				.generatePrivateKey()
				.build();

		KadNode node = new KadNode(config);
		node.start().join();
		assertEquals(host, node.getHost4());
		assertEquals(39101, node.getPort());
		node.stop().join();
	}
}
