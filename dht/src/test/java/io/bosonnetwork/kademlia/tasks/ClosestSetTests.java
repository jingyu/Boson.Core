package io.bosonnetwork.kademlia.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import net.datafaker.Faker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.routing.KBucket;
import io.bosonnetwork.utils.AddressUtils;

class ClosestSetTests {
	private static final Faker faker = new Faker();

	private Id target;
	private ClosestSet closestSet;

	@BeforeEach
	void setUp() {
		target = Id.random();
		closestSet = new ClosestSet(target, KBucket.MAX_ENTRIES);
	}

	private InetSocketAddress randomAddress() {
		try {
			InetAddress addr;
			do {
				addr = InetAddress.getByName(faker.internet().publicIpV4Address());
			} while (!AddressUtils.isGlobalUnicast(addr));

			return new InetSocketAddress(addr, Random.random().nextInt(1024, 65535));
		} catch (Exception e) {
			throw new RuntimeException("randomAddress", e);
		}
	}

	@Test
	void testInsertion() {
		List<CandidateNode> nodes = new ArrayList<>();
		for (int i = 0; i < KBucket.MAX_ENTRIES + 3; i++) {
			CandidateNode node = new CandidateNode(new NodeInfo(Id.random(), randomAddress()));
			closestSet.add(node);
			nodes.add(node);
			int expected = i < KBucket.MAX_ENTRIES ? i + 1 : KBucket.MAX_ENTRIES;
			assertEquals(expected, closestSet.size());
		}

		nodes.sort((n1, n2) -> target.threeWayCompare(n1.getId(), n2.getId()));
		assertEquals(nodes.subList(0, KBucket.MAX_ENTRIES), closestSet.stream().toList());
	}

	@Test
	void testEligibility() {
		assertFalse(closestSet.isEligible());
		for (int i = 0; i < KBucket.MAX_ENTRIES; i++) {
			CandidateNode node = new CandidateNode(new NodeInfo(target.getIdByDistance(8 - i), randomAddress()));
			closestSet.add(node);
		}
		assertFalse(closestSet.isEligible());

		for (int i = 0; i < KBucket.MAX_ENTRIES + 1; i++) {
			CandidateNode node = new CandidateNode(new NodeInfo(target.getIdByDistance(16 + i), randomAddress()));
			closestSet.add(node);
		}
		System.out.println(closestSet.getInsertAttemptsSinceTailModification());
		assertTrue(closestSet.isEligible());
	}

	@Test
	void testHeadStability() {
		for (int i = 0; i < KBucket.MAX_ENTRIES; i++) {
			CandidateNode node = new CandidateNode(new NodeInfo(target.getIdByDistance(16 - i), randomAddress()));
			closestSet.add(node);
		}

		assertFalse(closestSet.isHeadStable());

		for (int i = 0; i < KBucket.MAX_ENTRIES + 1; i++) {
			CandidateNode node = new CandidateNode(new NodeInfo(target.getIdByDistance(16 + i), randomAddress()));
			closestSet.add(node);
		}

		assertTrue(closestSet.isHeadStable());
	}

	@Test
	void testEmptySet() {
		assertEquals(0, closestSet.size());
		assertFalse(closestSet.contains(Id.random()));
	}
}