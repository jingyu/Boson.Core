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

	/**
	 * Fills the set to capacity with the {@code capacity} nearest ids, then feeds it strictly farther
	 * ones - which cannot displace the tail - until it calls itself stable.
	 *
	 * @param set      the set under test.
	 * @param capacity the set's capacity.
	 * @return how many non-improving insertions it took, counted after the set was full.
	 */
	private int nonImprovingInsertionsUntilEligible(ClosestSet set, int capacity) {
		for (int i = 0; i < capacity; i++)
			set.add(new CandidateNode(NodeInfo.of(target.getIdByDistance(i + 1), randomAddress())));

		assertTrue(set.reachedCapacity(), "the set should be full");
		assertFalse(set.isEligible(), "a full set has not yet been shown to be stable");

		int insertions = 0;
		// Bounded so a rule that never fires fails the test rather than hanging it.
		while (!set.isEligible() && insertions <= 4 * capacity + 8) {
			set.add(new CandidateNode(NodeInfo.of(target.getIdByDistance(capacity + 1 + insertions), randomAddress())));
			insertions++;
		}

		assertTrue(set.isEligible(), "the set never became eligible");
		return insertions;
	}

	@Test
	void testEligibilityBoundary() {
		assertFalse(closestSet.isEligible());

		// The rule is "> margin", so eligibility arrives on the insertion after the margin is reached,
		// and this pins that boundary rather than merely observing eligibility somewhere past it.
		int margin = ClosestSet.stabilityMargin(TEST_K);
		assertEquals(margin + 1, nonImprovingInsertionsUntilEligible(closestSet, TEST_K));
	}

	@Test
	void testStabilityMarginDoesNotScaleWithK() {
		// The margin used to be the capacity itself, so raising k silently made every lookup collect
		// twice as many non-improving responses before it could converge. It is now capped: the cost of
		// convergence above the fill still grows with nothing.
		int atK8 = nonImprovingInsertionsUntilEligible(new ClosestSet(target, 8), 8);
		int atK16 = nonImprovingInsertionsUntilEligible(new ClosestSet(target, 16), 16);
		int atK64 = nonImprovingInsertionsUntilEligible(new ClosestSet(target, 64), 64);

		assertEquals(atK8, atK16, "a larger k must not demand a longer stability run");
		assertEquals(atK8, atK64, "a larger k must not demand a longer stability run");

		// And a node running k=8 behaves exactly as it did before the cap existed, which is what makes
		// this a decoupling rather than a retune.
		assertEquals(8 + 1, atK8);
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