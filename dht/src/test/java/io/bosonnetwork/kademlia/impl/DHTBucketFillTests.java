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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.datafaker.Faker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.kademlia.routing.KBucket;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.security.Blacklist;

/**
 * Covers {@link DHT#selectBucketsToFill(boolean)} - which buckets a bootstrap tops up with a full
 * iterative lookup.
 * <p>
 * The selection is the only thing standing between a large routing table and a burst of one iterative
 * lookup per bucket, dispatched as often as every {@code BOOTSTRAP_MIN_INTERVAL} while the table sits
 * below the bootstrap threshold. It is deliberately a pure function of the routing table so it can be
 * tested here without a network, an event loop, or a deployed verticle.
 * </p>
 */
public class DHTBucketFillTests {
	// Kademlia parameters, as KadNode would pass them down from NodeConfiguration.KademliaOptions.
	private static final int K = 16;
	private static final int REPLACEMENTS = 8;
	private static final int ALPHA = 3;
	private static final int CONCURRENT_TASKS = 32;

	// As DHT derives them: min(3k, 64) and min(k, 32).
	private static final int BOOTSTRAP_THRESHOLD = 48;
	private static final int USE_BOOTSTRAP_NODES_THRESHOLD = 16;

	private static final Faker faker = new Faker();

	private DHT dht;

	@BeforeEach
	void setUp() {
		// Never deployed: the routing table and the derived thresholds are built by the constructor,
		// and the selection reads nothing else.
		dht = new DHT(new CryptoIdentity(), Network.IPv4, "127.0.0.1", 39101, List.of(),
				ALPHA, K, REPLACEMENTS, CONCURRENT_TASKS,
				null, null, new TokenManager(),
				Blacklist.empty(), false, false, true, null);
	}

	/**
	 * Adds the given number of reachable entries at random ids, which density-driven splitting turns
	 * into a table of many buckets in varying states of fullness.
	 */
	private void populate(int count) {
		for (int i = 0; i < count; i++) {
			KBucketEntry entry = new KBucketEntry(Id.random(), new InetSocketAddress(
					faker.internet().getPublicIpV4Address(), faker.number().numberBetween(1024, 65535)));
			// Marks the entry reachable, exactly as DHT.onMessage does for a responding node. Splitting
			// only ever happens for reachable entries, so the table stays a single bucket without it.
			entry.onResponded(50);
			dht.getRoutingTable().put(entry);
		}
	}

	private List<KBucket> buckets() {
		List<KBucket> all = new ArrayList<>();
		dht.getRoutingTable().forEachBucket(all::add);
		return all;
	}

	/**
	 * The buckets that pass every eligibility rule, ignoring the budget - so a test can tell a cap that
	 * bound from a selection that simply had nothing more to offer.
	 */
	private List<KBucket> eligible() {
		int entries = dht.getRoutingTable().getNumberOfEntries();
		List<KBucket> result = new ArrayList<>();
		for (KBucket bucket : buckets()) {
			if (bucket.isEmpty())
				continue;
			if (bucket.isFull() && entries >= BOOTSTRAP_THRESHOLD)
				continue;
			if (!bucket.needsLookupRefresh())
				continue;
			result.add(bucket);
		}
		return result;
	}

	/**
	 * Below the bootstrap-server fallback threshold the node cannot reach the network unaided, so the
	 * budget belongs to fillHomeBucket - the one lookup seeded with the configured bootstrap servers.
	 */
	@Test
	void testHealthGateSkipsFanOutOnThinTable() {
		populate(USE_BOOTSTRAP_NODES_THRESHOLD - 6);
		assertTrue(dht.getRoutingTable().getNumberOfEntries() < USE_BOOTSTRAP_NODES_THRESHOLD,
				"precondition: the table must be below the fallback threshold");

		assertTrue(dht.selectBucketsToFill(false).isEmpty(), "routine bootstrap should not fan out here");
	}

	/**
	 * The gate is waived on the first bootstrap after startup: the table is small by definition on a
	 * cold start, and waiting a full BOOTSTRAP_MIN_INTERVAL for the next cycle is the one cost the
	 * startup path cannot pay.
	 */
	@Test
	void testHealthGateIsWaivedForTheFirstBootstrap() {
		populate(USE_BOOTSTRAP_NODES_THRESHOLD - 6);

		assertTrue(dht.selectBucketsToFill(false).isEmpty());
		assertFalse(dht.selectBucketsToFill(true).isEmpty(), "the first bootstrap must still fan out");
	}

	@Test
	void testHealthGateDoesNotBindOnAHealthyTable() {
		populate(500);
		assertTrue(dht.getRoutingTable().getNumberOfEntries() >= USE_BOOTSTRAP_NODES_THRESHOLD);

		assertFalse(dht.selectBucketsToFill(false).isEmpty());
	}

	/**
	 * An empty bucket is an artifact of deep splitting - it covers a slice of the keyspace with no
	 * reachable nodes - so a lookup there converges on nothing, every bootstrap, forever. A full one has
	 * nothing to gain unless the table as a whole is thin.
	 */
	@Test
	void testEmptyAndFullBucketsAreExcluded() {
		populate(500);
		assertTrue(dht.getRoutingTable().getNumberOfEntries() >= BOOTSTRAP_THRESHOLD);

		for (KBucket bucket : dht.selectBucketsToFill(false)) {
			assertFalse(bucket.isEmpty(), "empty bucket selected: " + bucket.prefix());
			assertFalse(bucket.isFull(), "full bucket selected on a healthy table: " + bucket.prefix());
		}
	}

	/**
	 * The point of the whole change: the fan-out is a fixed budget, not one lookup per bucket.
	 */
	@Test
	void testFanOutIsCapped() {
		populate(1000);
		assertTrue(eligible().size() > KadConstants.MAX_BUCKET_FILLS_PER_BOOTSTRAP,
				"precondition: more buckets must want a lookup than the budget allows");

		assertEquals(KadConstants.MAX_BUCKET_FILLS_PER_BOOTSTRAP, dht.selectBucketsToFill(false).size());
	}

	@Test
	void testSelectsEverythingEligibleWhenUnderBudget() {
		populate(60);
		List<KBucket> eligible = eligible();
		assertTrue(eligible.size() <= KadConstants.MAX_BUCKET_FILLS_PER_BOOTSTRAP,
				"precondition: the budget must not bind here");

		assertEquals(new HashSet<>(eligible), new HashSet<>(dht.selectBucketsToFill(false)));
	}

	/**
	 * Buckets that miss the budget are not dropped. Filling a bucket stamps it, so it sinks to the back
	 * of the next selection and the deferred ones rise - round-robin with no extra state to keep.
	 */
	@Test
	void testDeferredBucketsAreTakenOnTheNextCycle() {
		populate(1000);

		List<KBucket> first = dht.selectBucketsToFill(false);
		assertEquals(KadConstants.MAX_BUCKET_FILLS_PER_BOOTSTRAP, first.size());
		// What fillBuckets() does on dispatch.
		first.forEach(KBucket::updateLookupRefreshTime);

		List<KBucket> second = dht.selectBucketsToFill(false);
		assertEquals(KadConstants.MAX_BUCKET_FILLS_PER_BOOTSTRAP, second.size());

		Set<KBucket> overlap = new HashSet<>(first);
		overlap.retainAll(second);
		assertTrue(overlap.isEmpty(), "a bucket just filled must not be selected again");
	}

	/**
	 * The per-bucket rate limit. Without it a table below the bootstrap threshold re-runs the same
	 * iterative lookups on the same buckets every BOOTSTRAP_MIN_INTERVAL.
	 */
	@Test
	void testRecentlyFilledBucketsAreSkipped() {
		populate(1000);
		buckets().forEach(KBucket::updateLookupRefreshTime);

		assertTrue(dht.selectBucketsToFill(false).isEmpty());
		assertTrue(dht.selectBucketsToFill(true).isEmpty(),
				"the rate limit applies to the first bootstrap too - only the health gate is waived");
	}

	/**
	 * Ordering among equally stale buckets, which is every bucket on a restart from a cached routing
	 * table: the emptiest gains the most from a lookup and goes first.
	 */
	@Test
	void testEquallyStaleBucketsAreOrderedByDeficit() {
		populate(1000);

		List<KBucket> selected = dht.selectBucketsToFill(false);
		for (int i = 1; i < selected.size(); i++)
			assertTrue(selected.get(i - 1).deficit() >= selected.get(i).deficit(),
					"selection must be ordered by descending deficit when equally stale");

		// And the budget must go to the emptiest buckets overall, not to an arbitrary eight.
		int worstUnselected = eligible().stream()
				.filter(bucket -> !selected.contains(bucket))
				.mapToInt(KBucket::deficit)
				.max()
				.orElse(0);
		assertTrue(selected.get(selected.size() - 1).deficit() >= worstUnselected,
				"a deferred bucket must not be emptier than a selected one");
	}
}
