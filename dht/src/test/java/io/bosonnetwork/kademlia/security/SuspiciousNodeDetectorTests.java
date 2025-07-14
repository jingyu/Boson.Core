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
	private static final long OBSERVATION_PERIOD_SECONDS = 120;
	private static final int HITS = 8;
	private static final long BAN_DURATION_SECONDS = 120;

	private SuspiciousNodeTracker detector;

	@BeforeEach
	void setup() {
		detector = new SuspiciousNodeTracker(TimeUnit.SECONDS.toMillis(OBSERVATION_PERIOD_SECONDS),
				HITS, TimeUnit.SECONDS.toMillis(BAN_DURATION_SECONDS));
	}

	@Test
	@Timeout(value = 3, unit = TimeUnit.MINUTES)
	public void testInconsistentId() throws Exception {
		var addr1 = SocketAddress.inetSocketAddress(39001, "192.168.8.1");
		for (var i = 0; i <= HITS; i++)
			detector.observe(addr1, Id.random());

		var addr2 = SocketAddress.inetSocketAddress(39001, "192.168.8.6");
		for (var i = 0; i <= HITS; i++)
			detector.observe(addr2, Id.random());

		var addr3 = SocketAddress.inetSocketAddress(39001, "192.168.8.9");
		for (var i = 0; i <= HITS; i++)
			detector.observe(addr3, Id.random());

		var addr4 = SocketAddress.inetSocketAddress(39001, "192.168.8.16");
		for (var i = 0; i <= HITS - 3; i++)
			detector.observe(addr4, Id.random());

		var addr5 = SocketAddress.inetSocketAddress(39001, "192.168.8.18");
		for (var i = 0; i <= HITS - 2; i++)
			detector.observe(addr5, Id.random());

		var addr6 = SocketAddress.inetSocketAddress(39002, "192.168.8.36");

		System.out.println(detector);
		assertEquals(5, detector.getObservedSize());
		assertEquals(0, detector.getBannedSize());

		assertTrue(detector.isSuspicious(addr1));
		assertTrue(detector.isSuspicious(addr2));
		assertTrue(detector.isSuspicious(addr3));
		assertTrue(detector.isSuspicious(addr4));
		assertTrue(detector.isSuspicious(addr5));
		assertFalse(detector.isSuspicious(addr6));

		assertFalse(detector.isBanned(addr1.host()));
		assertFalse(detector.isBanned(addr2.host()));
		assertFalse(detector.isBanned(addr3.host()));
		assertFalse(detector.isBanned(addr4.host()));
		assertFalse(detector.isBanned(addr5.host()));
		assertFalse(detector.isBanned(addr6.host()));

		detector.purge();

		System.out.println(detector);
		assertEquals(5, detector.getObservedSize());
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

		System.out.println("Waiting for several seconds ......");
		TimeUnit.SECONDS.sleep(10);

		for (var i = 0; i <= 5; i++)
			detector.observe(addr4, Id.random());

		for (var i = 0; i <= HITS - 5; i++)
			detector.observe(addr6, Id.random());

		detector.purge();

		System.out.println(detector);
		assertEquals(6, detector.getObservedSize());
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
		TimeUnit.SECONDS.sleep(OBSERVATION_PERIOD_SECONDS - 9);
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
	public void testMalformedMessage() throws Exception {
		var addr1 = SocketAddress.inetSocketAddress(39001, "192.168.8.1");
		for (var i = 0; i <= HITS; i++)
			detector.observe(addr1);

		var addr2 = SocketAddress.inetSocketAddress(39001, "192.168.8.6");
		for (var i = 0; i <= HITS; i++)
			detector.observe(addr2);

		var addr3 = SocketAddress.inetSocketAddress(39001, "192.168.8.9");
		for (var i = 0; i <= HITS; i++)
			detector.observe(addr3);

		var addr4 = SocketAddress.inetSocketAddress(39001, "192.168.8.16");
		for (var i = 0; i <= HITS - 3; i++)
			detector.observe(addr4);

		var addr5 = SocketAddress.inetSocketAddress(39001, "192.168.8.18");
		for (var i = 0; i <= HITS - 2; i++)
			detector.observe(addr5);

		var addr6 = SocketAddress.inetSocketAddress(39002, "192.168.8.36");

		System.out.println(detector);
		assertEquals(5, detector.getObservedSize());
		assertEquals(0, detector.getBannedSize());

		assertTrue(detector.isSuspicious(addr1));
		assertTrue(detector.isSuspicious(addr2));
		assertTrue(detector.isSuspicious(addr3));
		assertTrue(detector.isSuspicious(addr4));
		assertTrue(detector.isSuspicious(addr5));
		assertFalse(detector.isSuspicious(addr6));

		assertFalse(detector.isBanned(addr1.host()));
		assertFalse(detector.isBanned(addr2.host()));
		assertFalse(detector.isBanned(addr3.host()));
		assertFalse(detector.isBanned(addr4.host()));
		assertFalse(detector.isBanned(addr5.host()));
		assertFalse(detector.isBanned(addr6.host()));

		detector.purge();

		System.out.println(detector);
		assertEquals(5, detector.getObservedSize());
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

		System.out.println("Waiting for several seconds ......");
		TimeUnit.SECONDS.sleep(10);

		for (var i = 0; i <= 5; i++)
			detector.observe(addr4);

		for (var i = 0; i <= HITS - 5; i++)
			detector.observe(addr6);

		detector.purge();

		System.out.println(detector);
		assertEquals(6, detector.getObservedSize());
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
		TimeUnit.SECONDS.sleep(OBSERVATION_PERIOD_SECONDS - 9);
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