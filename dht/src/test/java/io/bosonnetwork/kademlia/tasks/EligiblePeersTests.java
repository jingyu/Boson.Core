package io.bosonnetwork.kademlia.tasks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.kademlia.impl.KadConstants;

/**
 * What a caller's expected-peer count means, and the state it must never produce.
 * <p>
 * Zero used to reach {@code EligiblePeers} unchanged through the public {@code DHT.findPeer}, where
 * {@code reachedCapacity()} is {@code size() >= 0} - true before a single peer arrives. The first response
 * therefore ended the lookup and {@code prune()}'s {@code skip(0)} then selected every peer for removal, so
 * a caller asking for peers with an unspecified count got an empty list back and no way to tell it apart
 * from a peer nobody has published.
 * </p>
 */
class EligiblePeersTests {
	@Test
	void testUnspecifiedCountMeansTheResponseCapNotUnbounded() {
		// The same reading the receive side already applies to the number a peer puts on the wire. Not
		// unbounded: nothing caps how many peers a response may carry, so an unbounded lookup would
		// accumulate whatever it was sent, across every response of every iteration.
		assertEquals(KadConstants.MAX_PEERS_PER_RESPONSE, EligiblePeers.resolveExpectedCount(0));
	}

	@Test
	void testAnExplicitCountIsKept() {
		assertEquals(3, EligiblePeers.resolveExpectedCount(3));
		assertEquals(1, EligiblePeers.resolveExpectedCount(1));
	}

	@Test
	void testANegativeCountIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> EligiblePeers.resolveExpectedCount(-1));
	}

	@Test
	void testTheDiscardEverythingStateCannotBeConstructed() {
		// The invariant, not merely the guard at the entry points: at zero this class collects peers and
		// then throws them all away, so no caller that skipped resolveExpectedCount can reach that state.
		assertThrows(IllegalArgumentException.class, () -> new EligiblePeers(Id.random(), -1, 0));
		assertThrows(IllegalArgumentException.class, () -> new EligiblePeers(Id.random(), -1, -1));
	}
}
