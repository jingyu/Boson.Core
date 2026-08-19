package io.bosonnetwork.kademlia.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;
import io.bosonnetwork.kademlia.routing.KBucketEntry;

class CandidateNodeTests {
	private CandidateNode candidate;

	@BeforeEach
	void setUp() throws Exception {
		candidate = new CandidateNode(NodeInfo.of(Id.random(), "100.1.1.8", 39001));
	}

	/**
	 * The candidate reads "has a token" off the value, which it can only do because zero is reserved to
	 * mean "no token" and is never one an issuer grants. This is the single place that rule is applied on
	 * the announce path, so it is pinned here as well as end to end.
	 */
	@Test
	void testTokenPresenceIsReadFromTheValue() {
		assertFalse(candidate.hasToken(), "a fresh candidate has been given nothing");
		assertEquals(0, candidate.getToken());

		candidate.setToken(0x87654321);
		assertTrue(candidate.hasToken());
		assertEquals(0x87654321, candidate.getToken());

		candidate.setToken(0);
		assertFalse(candidate.hasToken(), "zero is the absence, not a token that happens to be zero");
	}

	@Test
	void testStateTransitions() {
		assertFalse(candidate.isSent());
		candidate.setSent();
		assertTrue(candidate.isSent());
		assertEquals(1, candidate.getPinged());
		candidate.clearSent();
		assertFalse(candidate.isSent());
		candidate.setReplied();
		assertTrue(candidate.isReplied());
	}

	private void setReachable(KBucketEntry entry, boolean reachable) {
		try {
			Class<KBucketEntry> clazz = KBucketEntry.class;
			Field field = clazz.getDeclaredField("reachable");
			field.setAccessible(true);
			field.set(entry, reachable);
		} catch (Exception e) {
			throw new RuntimeException("setReachable failed", e);
		}
	}

	@Test
	void testReachability() {
		KBucketEntry entry = new KBucketEntry(NodeInfo.of(Id.random(), "100.1.1.8", 39001));
		setReachable(entry, true);
		candidate = new CandidateNode(entry);
		assertTrue(candidate.isReachable());
		candidate.setSent();
		candidate.setSent();
		candidate.setSent();
		assertTrue(candidate.isUnreachable());
	}

	@Test
	void testEligibility() {
		assertTrue(candidate.isEligible());
		candidate.setSent();
		assertFalse(candidate.isEligible());
		candidate.clearSent();
		candidate.setSent();
		candidate.setSent();
		candidate.setSent();
		assertFalse(candidate.isEligible());
	}
}