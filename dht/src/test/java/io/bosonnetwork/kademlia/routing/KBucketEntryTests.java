package io.bosonnetwork.kademlia.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;

public class KBucketEntryTests {
	@Test
	void maxTimeoutsConstantCheck() {
		assertTrue(KBucketEntry.MAX_FAILURES < 8, "MAX_TIMEOUTS should be less than 8");
	}

	@Test
	void testToMap() {
		KBucketEntry entry = new KBucketEntry(Id.random(), new InetSocketAddress("192.168.1.1", 5678));
		entry.setReachable(true);

		Map<String, Object> map = entry.toMap();
		KBucketEntry mapped = KBucketEntry.fromMap(map);
		assertEquals(entry, mapped);
	}
}