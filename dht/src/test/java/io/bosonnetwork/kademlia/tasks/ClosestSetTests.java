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
import io.bosonnetwork.utils.AddressUtils;

class ClosestSetTests {
	private static final int TEST_K = 32;
	private static final Faker faker = new Faker();

	private Id target;
	private ClosestSet closestSet;

	@BeforeEach
	void setUp() {
		target = Id.random();
		closestSet = new ClosestSet(target, TEST_K);
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
		for (int i = 0; i < TEST_K + 3; i++) {
			CandidateNode node = new CandidateNode(NodeInfo.of(Id.random(), randomAddress()));
			closestSet.add(node);
			nodes.add(node);
			int expected = i < TEST_K ? i + 1 : TEST_K;
			assertEquals(expected, closestSet.size());
		}

		nodes.sort((n1, n2) -> target.threeWayCompare(n1.getId(), n2.getId()));
		assertEquals(nodes.subList(0, TEST_K), closestSet.stream().toList());
	}

	@Test
	void testEligibility() {
		assertFalse(closestSet.isEligible());
		// Distances 1..K - the set fills with exactly its capacity.
		for (int i = 0; i < TEST_K; i++) {
			CandidateNode node = new CandidateNode(NodeInfo.of(target.getIdByDistance(i + 1), randomAddress()));
			closestSet.add(node);
		}
		assertFalse(closestSet.isEligible());

		// Distances K+1..2K+1 - all strictly farther, so none of them displaces the tail.
		for (int i = 0; i < TEST_K + 1; i++) {
			CandidateNode node = new CandidateNode(NodeInfo.of(target.getIdByDistance(TEST_K + 1 + i), randomAddress()));
			closestSet.add(node);
		}
		assertTrue(closestSet.isEligible());
	}

	@Test
	void testHeadStability() {
		for (int i = 0; i < TEST_K; i++) {
			CandidateNode node = new CandidateNode(NodeInfo.of(target.getIdByDistance(TEST_K - i), randomAddress()));
			closestSet.add(node);
		}

		assertFalse(closestSet.isHeadStable());

		for (int i = 0; i < TEST_K + 1; i++) {
			CandidateNode node = new CandidateNode(NodeInfo.of(target.getIdByDistance(TEST_K + 1 + i), randomAddress()));
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