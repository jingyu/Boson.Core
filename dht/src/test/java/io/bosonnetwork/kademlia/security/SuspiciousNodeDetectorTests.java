package io.bosonnetwork.kademlia.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import io.vertx.core.net.SocketAddress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.bosonnetwork.Id;

public class SuspiciousNodeDetectorTests {
	// Observation: 2 minutes
	// Hits: 5 times
	// Ban: 2 minutes
	private static final long OBSERVATION_PERIOD = 60 * 1000;
	private static final int HITS = 32;
	private static final long BAN_DURATION = 60 * 1000;

	private SuspiciousNodeDetector detector;

	@BeforeEach
	void setup() {
		detector = SuspiciousNodeDetector.create(OBSERVATION_PERIOD, HITS, BAN_DURATION);
	}

	@Test
	@Timeout(value = 3, unit = TimeUnit.MINUTES)
	public void testInconsistentId() throws Exception {
		var addr1 = SocketAddress.inetSocketAddress(39001, "192.168.8.1");
		for (var i = 0; i <= HITS; i++)
			detector.inconsistent(addr1, Id.random());

		var addr2 = SocketAddress.inetSocketAddress(39001, "192.168.8.6");
		for (var i = 0; i <= HITS; i++)
			detector.inconsistent(addr2, Id.random());

		var addr3 = SocketAddress.inetSocketAddress(39001, "192.168.8.9");
		for (var i = 0; i <= HITS; i++)
			detector.inconsistent(addr3, Id.random());

		var addr4 = SocketAddress.inetSocketAddress(39001, "192.168.8.16");
		for (var i = 0; i <= HITS - 3; i++)
			detector.inconsistent(addr4, Id.random());

		var addr5 = SocketAddress.inetSocketAddress(39001, "192.168.8.18");
		for (var i = 0; i <= HITS - 2; i++)
			detector.inconsistent(addr5, Id.random());

		var addr6 = SocketAddress.inetSocketAddress(39002, "192.168.8.36");

		System.out.println(detector);
		assertEquals(4, detector.getObservedSize());
		assertEquals(3, detector.getBannedSize());

		assertTrue(detector.isSuspicious(addr1));
		assertTrue(detector.isSuspicious(addr2));
		assertTrue(detector.isSuspicious(addr3));
		assertTrue(detector.isSuspicious(addr4));
		assertTrue(detector.isSuspicious(addr5));
		assertFalse(detector.isSuspicious(addr6));

		assertTrue(detector.isBanned(addr1.host()));
		assertTrue(detector.isBanned(addr2.host()));
		assertTrue(detector.isBanned(addr3.host()));
		assertFalse(detector.isBanned(addr4.host()));
		assertFalse(detector.isBanned(addr5.host()));
		assertFalse(detector.isBanned(addr6.host()));

		detector.purge();

		System.out.println(detector);
		assertEquals(4, detector.getObservedSize());
		assertEquals(3, detector.getBannedSize());

		System.out.println("Waiting for several seconds ......");
		Thread.sleep(10 * 1000);

		for (var i = 0; i <= 5; i++)
			detector.inconsistent(addr4, Id.random());

		for (var i = 0; i <= HITS - 5; i++)
			detector.inconsistent(addr6, Id.random());

		System.out.println(detector);
		assertEquals(4, detector.getObservedSize());
		assertEquals(4, detector.getBannedSize());

		assertTrue(detector.isSuspicious(addr1));
		assertTrue(detector.isSuspicious(addr2));
		assertTrue(detector.isSuspicious(addr3));
		assertTrue(detector.isSuspicious(addr4));
		assertTrue(detector.isSuspicious(addr5));
		assertTrue(detector.isSuspicious(addr6));

		assertTrue(detector.isBanned(addr1.host()));
		assertTrue(detector.isBanned(addr2.host()));
		assertTrue(detector.isBanned(addr3.host()));
		assertTrue(detector.isBanned(addr4.host()));
		assertFalse(detector.isBanned(addr5.host()));
		assertFalse(detector.isBanned(addr6.host()));

		System.out.println("Waiting for the observation period to purge ......");
		Thread.sleep(OBSERVATION_PERIOD - 9000);
		detector.purge();

		System.out.println(detector);
		assertEquals(2, detector.getObservedSize());
		assertEquals(1, detector.getBannedSize());

		assertFalse(detector.isSuspicious(addr1));
		assertFalse(detector.isSuspicious(addr2));
		assertFalse(detector.isSuspicious(addr3));
		assertTrue(detector.isSuspicious(addr4));
		assertFalse(detector.isSuspicious(addr5));
		assertTrue(detector.isSuspicious(addr6));

		assertFalse(detector.isBanned(addr1.host()));
		assertFalse(detector.isBanned(addr2.host()));
		assertFalse(detector.isBanned(addr3.host()));
		assertTrue(detector.isBanned(addr4.host()));
		assertFalse(detector.isBanned(addr5.host()));
		assertFalse(detector.isBanned(addr6.host()));
	}

	@Test
	@Timeout(value = 3, unit = TimeUnit.MINUTES)
	public void testInconsistentAddress() throws Exception {
		// DefaultSuspiciousNodeDetector.SUSPICIOUS_OBSERVATION_HITS == 8

		Id id1 = Id.random();
		for (var i = 0; i <= 8; i++)
			detector.inconsistent(SocketAddress.inetSocketAddress(39000, "192.168.8." + i), id1);

		Id id2 = Id.random();
		for (var i = 0; i <= 8; i++)
			detector.inconsistent(SocketAddress.inetSocketAddress(39001 + i, "192.168.8.100"), id2);

		Id id3 = Id.random();
		for (var i = 0; i <= 4; i++)
			detector.inconsistent(SocketAddress.inetSocketAddress(39000, "192.168.100." + i), id3);

		System.out.println(detector);
		assertEquals(12, detector.getObservedSize());
		assertEquals(9, detector.getBannedSize());

		for (var i = 0; i < 8; i++) {
			assertTrue(detector.isSuspicious(SocketAddress.inetSocketAddress(39000, "192.168.8." + i)));
			assertTrue(detector.isBanned("192.168.8." + i));
		}

		assertTrue(detector.isSuspicious(SocketAddress.inetSocketAddress(39001, "192.168.8.100")));
		assertTrue(detector.isBanned("192.168.8.100"));

		for (var i = 0; i <= 4; i++) {
			assertFalse(detector.isSuspicious(SocketAddress.inetSocketAddress(39000, "192.168.100." + i)));
			assertFalse(detector.isBanned("192.168.100." + i));
		}

		Thread.sleep(BAN_DURATION);

		detector.purge();
		System.out.println(detector);
		assertEquals(0, detector.getObservedSize());
		assertEquals(0, detector.getBannedSize());
	}

	@Test
	@Timeout(value = 3, unit = TimeUnit.MINUTES)
	public void testMalformedMessage() throws Exception {
		var addr1 = SocketAddress.inetSocketAddress(39001, "192.168.8.1");
		for (var i = 0; i <= HITS; i++)
			detector.malformedMessage(addr1);

		var addr2 = SocketAddress.inetSocketAddress(39001, "192.168.8.6");
		for (var i = 0; i <= HITS; i++)
			detector.malformedMessage(addr2);

		var addr3 = SocketAddress.inetSocketAddress(39001, "192.168.8.9");
		for (var i = 0; i <= HITS; i++)
			detector.malformedMessage(addr3);

		var addr4 = SocketAddress.inetSocketAddress(39001, "192.168.8.16");
		for (var i = 0; i <= HITS - 3; i++)
			detector.malformedMessage(addr4);

		var addr5 = SocketAddress.inetSocketAddress(39001, "192.168.8.18");
		for (var i = 0; i <= HITS - 2; i++)
			detector.malformedMessage(addr5);

		var addr6 = SocketAddress.inetSocketAddress(39002, "192.168.8.36");

		System.out.println(detector);
		assertEquals(4, detector.getObservedSize());
		assertEquals(3, detector.getBannedSize());

		assertTrue(detector.isSuspicious(addr1));
		assertTrue(detector.isSuspicious(addr2));
		assertTrue(detector.isSuspicious(addr3));
		assertTrue(detector.isSuspicious(addr4));
		assertTrue(detector.isSuspicious(addr5));
		assertFalse(detector.isSuspicious(addr6));

		assertTrue(detector.isBanned(addr1.host()));
		assertTrue(detector.isBanned(addr2.host()));
		assertTrue(detector.isBanned(addr3.host()));
		assertFalse(detector.isBanned(addr4.host()));
		assertFalse(detector.isBanned(addr5.host()));
		assertFalse(detector.isBanned(addr6.host()));

		detector.purge();

		System.out.println(detector);
		assertEquals(4, detector.getObservedSize());
		assertEquals(3, detector.getBannedSize());

		System.out.println("Waiting for several seconds ......");
		Thread.sleep(10 * 1000);

		for (var i = 0; i <= 5; i++)
			detector.malformedMessage(addr4);

		for (var i = 0; i <= HITS - 5; i++)
			detector.malformedMessage(addr6);

		detector.purge();

		System.out.println(detector);
		assertEquals(4, detector.getObservedSize());
		assertEquals(4, detector.getBannedSize());

		assertTrue(detector.isSuspicious(addr1));
		assertTrue(detector.isSuspicious(addr2));
		assertTrue(detector.isSuspicious(addr3));
		assertTrue(detector.isSuspicious(addr4));
		assertTrue(detector.isSuspicious(addr5));
		assertTrue(detector.isSuspicious(addr6));

		assertTrue(detector.isBanned(addr1.host()));
		assertTrue(detector.isBanned(addr2.host()));
		assertTrue(detector.isBanned(addr3.host()));
		assertTrue(detector.isBanned(addr4.host()));
		assertFalse(detector.isBanned(addr5.host()));
		assertFalse(detector.isBanned(addr6.host()));

		System.out.println("Waiting for the observation period to purge ......");
		Thread.sleep(OBSERVATION_PERIOD - 9000);
		detector.purge();

		System.out.println(detector);
		assertEquals(2, detector.getObservedSize());
		assertEquals(1, detector.getBannedSize());

		assertFalse(detector.isSuspicious(addr1));
		assertFalse(detector.isSuspicious(addr2));
		assertFalse(detector.isSuspicious(addr3));
		assertTrue(detector.isSuspicious(addr4));
		assertFalse(detector.isSuspicious(addr5));
		assertTrue(detector.isSuspicious(addr6));

		assertFalse(detector.isBanned(addr1.host()));
		assertFalse(detector.isBanned(addr2.host()));
		assertFalse(detector.isBanned(addr3.host()));
		assertTrue(detector.isBanned(addr4.host()));
		assertFalse(detector.isBanned(addr5.host()));
		assertFalse(detector.isBanned(addr6.host()));
	}
}