/*
 * Copyright (c) 2023 -      bosonnetwork.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.bosonnetwork.kademlia.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.kademlia.impl.DHT.BootstrapTier;
import io.bosonnetwork.kademlia.security.Blacklist;

/**
 * Covers {@link DHT#selectBootstrapTier(int, long, boolean)} - whether the periodic update bootstraps,
 * and whether it is allowed to seed the attempt with the configured bootstrap servers.
 * <p>
 * Two rules meet here. The tier split keeps routine maintenance off shared infrastructure by reasoning
 * from the routing table, and deafness overrides it because a deaf node's table is not evidence of
 * anything - it cannot be reached and it cannot repair itself. The selection is a pure function of its
 * arguments so all of this is decidable without a socket or a deployed verticle.
 * </p>
 */
public class DHTBootstrapTierTests {
	// Kademlia parameters, as KadNode would pass them down from NodeConfiguration.KademliaOptions.
	private static final int K = 16;
	private static final int REPLACEMENTS = 8;
	private static final int ALPHA = 3;
	private static final int CONCURRENT_TASKS = 32;

	// As DHT derives them: min(3k, 64) and min(k, 32).
	private static final int BOOTSTRAP_THRESHOLD = 48;
	private static final int USE_BOOTSTRAP_NODES_THRESHOLD = 16;

	// lastBootstrap is 0 on a freshly constructed DHT, so "now" doubles as the time since the last
	// bootstrap: a small value means the periodic self-lookup is not due yet, a large one that it is.
	private static final long SELF_LOOKUP_NOT_DUE = 1000;
	private static final long SELF_LOOKUP_DUE = KadConstants.SELF_LOOKUP_INTERVAL + 1000;

	private static final boolean REACHABLE = true;
	private static final boolean DEAF = false;

	private DHT dht;

	@BeforeEach
	void setUp() {
		// Never deployed: the thresholds this reads are derived by the constructor.
		dht = new DHT(new CryptoIdentity(), Network.IPv4, "127.0.0.1", 39102, List.of(),
				ALPHA, K, REPLACEMENTS, CONCURRENT_TASKS,
				null, null, new TokenManager(),
				Blacklist.empty(), false, false, true, null);
	}

	@Test
	void testHealthyTableWithNoSelfLookupDueDoesNothing() {
		assertEquals(BootstrapTier.NONE,
				dht.selectBootstrapTier(BOOTSTRAP_THRESHOLD, SELF_LOOKUP_NOT_DUE, REACHABLE));
	}

	/**
	 * The self-lookup is not about this node's table - it refreshes our presence in other nodes' tables -
	 * so a healthy node runs it from contacts it already holds and leaves the shared servers alone.
	 */
	@Test
	void testHealthyTableWithSelfLookupDueSelfBootstraps() {
		assertEquals(BootstrapTier.SELF,
				dht.selectBootstrapTier(BOOTSTRAP_THRESHOLD, SELF_LOOKUP_DUE, REACHABLE));
	}

	@Test
	void testThinTableSelfBootstrapsWhileItCanStillRoute() {
		assertEquals(BootstrapTier.SELF,
				dht.selectBootstrapTier(USE_BOOTSTRAP_NODES_THRESHOLD, SELF_LOOKUP_NOT_DUE, REACHABLE));
		assertEquals(BootstrapTier.SELF,
				dht.selectBootstrapTier(BOOTSTRAP_THRESHOLD - 1, SELF_LOOKUP_NOT_DUE, REACHABLE));
	}

	@Test
	void testTableBelowTheFallbackThresholdUsesTheBootstrapServers() {
		assertEquals(BootstrapTier.SERVERS,
				dht.selectBootstrapTier(USE_BOOTSTRAP_NODES_THRESHOLD - 1, SELF_LOOKUP_NOT_DUE, REACHABLE));
		assertEquals(BootstrapTier.SERVERS,
				dht.selectBootstrapTier(0, SELF_LOOKUP_NOT_DUE, REACHABLE));
	}

	/**
	 * A deaf node asks the bootstrap servers whatever its table looks like, and without waiting for the
	 * self-lookup clock.
	 * <p>
	 * The entry count is not evidence here. A deaf node's contacts are all still in the table and none
	 * of them answers, and the table cannot repair itself either - every eviction path is gated on
	 * reachability or does not evict for staleness - so the count stays wherever the outage left it.
	 * Reasoning from it would leave a node that went deaf with a full table permanently stranded: never
	 * below a threshold, never on the server tier, and pinging dead contacts forever. This is the
	 * laptop that suspends on one network and wakes on another.
	 * </p>
	 */
	@Test
	void testDeafNodeAlwaysAsksTheBootstrapServers() {
		// A healthy table with nothing otherwise due - the case that used to strand the node for good.
		assertEquals(BootstrapTier.SERVERS,
				dht.selectBootstrapTier(BOOTSTRAP_THRESHOLD, SELF_LOOKUP_NOT_DUE, DEAF));
		assertEquals(BootstrapTier.SERVERS,
				dht.selectBootstrapTier(BOOTSTRAP_THRESHOLD * 4, SELF_LOOKUP_NOT_DUE, DEAF));

		// And every thinner table, with or without the self-lookup due.
		assertEquals(BootstrapTier.SERVERS,
				dht.selectBootstrapTier(USE_BOOTSTRAP_NODES_THRESHOLD, SELF_LOOKUP_DUE, DEAF));
		assertEquals(BootstrapTier.SERVERS,
				dht.selectBootstrapTier(USE_BOOTSTRAP_NODES_THRESHOLD - 1, SELF_LOOKUP_NOT_DUE, DEAF));
		assertEquals(BootstrapTier.SERVERS,
				dht.selectBootstrapTier(0, SELF_LOOKUP_NOT_DUE, DEAF));
	}

	/**
	 * The mirror of the above: a reachable node is never pushed onto the shared servers by anything the
	 * deafness rule does, so routine maintenance keeps off that infrastructure exactly as before.
	 */
	@Test
	void testReachableNodeIsUnaffectedByTheDeafnessRule() {
		assertEquals(BootstrapTier.NONE,
				dht.selectBootstrapTier(BOOTSTRAP_THRESHOLD, SELF_LOOKUP_NOT_DUE, REACHABLE));
		assertEquals(BootstrapTier.SELF,
				dht.selectBootstrapTier(BOOTSTRAP_THRESHOLD, SELF_LOOKUP_DUE, REACHABLE));
		assertEquals(BootstrapTier.SELF,
				dht.selectBootstrapTier(USE_BOOTSTRAP_NODES_THRESHOLD, SELF_LOOKUP_NOT_DUE, REACHABLE));
	}

	/**
	 * The jitter that keeps a population of nodes from retrying in lockstep. Two properties matter and
	 * neither is obvious from the call site: the band is symmetric, so randomising an individual wait
	 * does not quietly slow the long-run cadence, and it is wider than one DHT_UPDATE_INTERVAL tick,
	 * without which the rounding to the next tick would absorb it and the jitter would do nothing.
	 */
	@Test
	void testBootstrapIntervalJitterIsSymmetricAndWiderThanOneTick() {
		long jitter = (long) KadConstants.BOOTSTRAP_INTERVAL
				/ 100 * KadConstants.BOOTSTRAP_INTERVAL_JITTER_PERCENT;
		assertTrue(2 * jitter > KadConstants.DHT_UPDATE_INTERVAL,
				"a band narrower than one update tick would be rounded away and achieve nothing");

		int samples = 10000;
		long total = 0;
		boolean below = false;
		boolean above = false;
		for (int i = 0; i < samples; i++) {
			long interval = DHT.bootstrapInterval();
			assertTrue(interval >= KadConstants.BOOTSTRAP_INTERVAL - jitter
					&& interval <= KadConstants.BOOTSTRAP_INTERVAL + jitter,
					"interval out of band: " + interval);
			below |= interval < KadConstants.BOOTSTRAP_INTERVAL;
			above |= interval > KadConstants.BOOTSTRAP_INTERVAL;
			total += interval;
		}

		assertTrue(below && above, "the band must fall on both sides of the interval, not just one");

		// The point of the symmetry: the long-run cadence stays the documented one. A one-sided jitter
		// would land half its width high, which over a day of a running node is a real slowdown. The
		// tolerance is 1% of the interval, many standard errors away from a mean of this many samples.
		long mean = total / samples;
		assertTrue(Math.abs(mean - KadConstants.BOOTSTRAP_INTERVAL) < KadConstants.BOOTSTRAP_INTERVAL / 100,
				"the jitter must not bias the average interval, but the mean was " + mean);
	}
}
