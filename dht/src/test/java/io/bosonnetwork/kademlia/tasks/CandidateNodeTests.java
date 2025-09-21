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

class CandidateNodeTest {
	private CandidateNode candidate;

	@BeforeEach
	void setUp() throws Exception {
		candidate = new CandidateNode(new NodeInfo(Id.random(), "100.1.1.8", 39001));
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
		KBucketEntry entry = new KBucketEntry(new NodeInfo(Id.random(), "100.1.1.8", 39001));
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