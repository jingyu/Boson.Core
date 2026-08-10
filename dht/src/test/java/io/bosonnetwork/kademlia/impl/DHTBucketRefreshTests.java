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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import net.datafaker.Faker;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.kademlia.routing.KBucket;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.security.Blacklist;

/**
 * Covers {@link DHT#selectBucketsToRefresh(List)} - how many of the buckets asking for a maintenance
 * refresh a single pass is willing to serve.
 * <p>
 * {@code RoutingTable.maintenance} reports demand, not a work order: on a large table it names many
 * buckets at once, and turning each into a task straight away was the third and longest-lived of the
 * unbounded per-bucket fan-outs. Unlike the other two this one repeats for the life of the node, so a
 * warm start that deferred buckets did not avoid the burst - it postponed it to the first pass.
 * </p>
 */
public class DHTBucketRefreshTests {
	// Kademlia parameters, as KadNode would pass them down from NodeConfiguration.KademliaOptions.
	private static final int K = 16;
	private static final int REPLACEMENTS = 8;
	private static final int ALPHA = 3;
	private static final int CONCURRENT_TASKS = 32;

	private static final Faker faker = new Faker();

	private static DHT newDht(int concurrentTasks) {
		return new DHT(new CryptoIdentity(), Network.IPv4, "127.0.0.1", 39121, List.of(),
				ALPHA, K, REPLACEMENTS, concurrentTasks,
				null, null, new TokenManager(),
				Blacklist.empty(), false, false, true, null);
	}

	private static void populate(DHT dht, int count) {
		for (int i = 0; i < count; i++) {
			KBucketEntry entry = new KBucketEntry(Id.random(), new InetSocketAddress(
					faker.internet().getPublicIpV4Address(), faker.number().numberBetween(1024, 65535)));
			entry.onResponded(50);
			dht.getRoutingTable().put(entry);
		}
	}

	/**
	 * Stands in for what {@code RoutingTable.maintenance} hands the handler: the buckets that want a
	 * refresh, in table order rather than any useful one.
	 */
	private static List<KBucket> candidates(DHT dht) {
		List<KBucket> all = new ArrayList<>();
		dht.getRoutingTable().forEachBucket(all::add);
		return all;
	}

	/**
	 * The point of the change: a pass serves a fixed budget, not however many buckets happened to ask.
	 */
	@Test
	void testRefreshIsCapped() {
		DHT dht = newDht(CONCURRENT_TASKS);
		populate(dht, 1000);

		List<KBucket> candidates = candidates(dht);
		assertTrue(candidates.size() > CONCURRENT_TASKS / 4,
				"precondition: more buckets must want a refresh than the budget allows");

		assertEquals(CONCURRENT_TASKS / 4, dht.selectBucketsToRefresh(candidates).size());
	}

	/**
	 * A quarter of the slots, not the half the warm-start sweep takes: this runs forever rather than
	 * once, so it has to leave more behind.
	 */
	@Test
	void testBudgetTracksConcurrentTasks() {
		DHT dht = newDht(8);
		populate(dht, 1000);
		assertTrue(candidates(dht).size() > 2, "precondition: the budget must bind");

		assertEquals(2, dht.selectBucketsToRefresh(candidates(dht)).size());
	}

	/**
	 * Halving twice must not round the maintenance away entirely on a node configured down to very few
	 * task slots.
	 */
	@Test
	void testBudgetIsNeverZero() {
		DHT dht = newDht(1);
		populate(dht, 1000);

		assertEquals(1, dht.selectBucketsToRefresh(candidates(dht)).size());
	}

	/**
	 * Under budget nothing is held back, and the list is handed straight back - a pass that can serve
	 * everything should not pay for a sort.
	 */
	@Test
	void testServesEverythingWhenUnderBudget() {
		DHT dht = newDht(CONCURRENT_TASKS);
		populate(dht, 60);

		List<KBucket> candidates = candidates(dht);
		assertTrue(candidates.size() <= CONCURRENT_TASKS / 4, "precondition: the budget must not bind");

		assertSame(candidates, dht.selectBucketsToRefresh(candidates));
	}

	/**
	 * Nothing to serve is not the same as a budget of zero, and must not throw or over-read the list.
	 */
	@Test
	void testNoCandidates() {
		DHT dht = newDht(CONCURRENT_TASKS);

		assertTrue(dht.selectBucketsToRefresh(new ArrayList<>()).isEmpty());
	}

	/**
	 * The first sort key, and the reason there is one: a bucket already refreshed yields to a bucket
	 * that never has been, however much nearer it is.
	 * <p>
	 * Ordering by distance alone starves the tail outright. A pass serves the nearest few, they fall
	 * due again one {@code BUCKET_REFRESH_INTERVAL} later, and being nearest they win again - ahead of
	 * buckets never served at all. Whenever more buckets are eligible than a refresh interval has
	 * capacity for, everything past that capacity is refreshed never.
	 * </p>
	 */
	@Test
	void testUnrefreshedBucketsOutrankNearerRefreshedOnes() {
		DHT dht = newDht(CONCURRENT_TASKS);
		populate(dht, 1000);

		Id localId = dht.getIdentity().getId();
		List<KBucket> all = candidates(dht);
		all.sort((a, b) -> localId.threeWayCompare(a.prefix(), b.prefix()));
		assertTrue(all.size() > CONCURRENT_TASKS / 4 + 1, "precondition: the budget must bind");

		// What a pass does to the buckets it serves.
		KBucket nearest = all.get(0);
		nearest.updateRefreshTime();

		List<KBucket> selected = dht.selectBucketsToRefresh(candidates(dht));
		assertFalse(selected.contains(nearest),
				"the nearest bucket was just refreshed, so it must yield to ones that never have been");
		assertSame(all.get(1), selected.get(0), "and the nearest of those goes first");
	}

	/**
	 * The property the ordering exists to guarantee: every bucket is served before any bucket is served
	 * a second time. This is the regression test for the starvation above.
	 */
	@Test
	void testEveryBucketIsServedBeforeAnyIsServedTwice() {
		DHT dht = newDht(CONCURRENT_TASKS);
		populate(dht, 1000);

		List<KBucket> all = candidates(dht);
		HashSet<KBucket> served = new HashSet<>();

		// Enough passes to cover the table several times over, so a starving order cannot pass by
		// running out of rounds before it runs out of buckets.
		for (int pass = 0; pass < 4 * all.size(); pass++) {
			for (KBucket bucket : dht.selectBucketsToRefresh(candidates(dht))) {
				if (served.size() < all.size())
					assertTrue(served.add(bucket),
							"bucket " + bucket.prefix() + " served twice while " + (all.size() - served.size())
									+ " others had never been served");

				// What PingRefreshTask.prepare() does when the task starts.
				bucket.updateRefreshTime();
			}

			if (served.size() == all.size())
				break;
		}

		assertEquals(all.size(), served.size(), "every bucket must be reached");
	}

	/**
	 * The tie-break, which is what makes a warm start behave as asked without a special case: a loaded
	 * table arrives with every bucket unstamped, so all are tied on staleness and distance decides.
	 */
	@Test
	void testNearestAreServedFirst() {
		DHT dht = newDht(CONCURRENT_TASKS);
		populate(dht, 1000);

		Id localId = dht.getIdentity().getId();
		List<KBucket> candidates = candidates(dht);
		List<KBucket> selected = dht.selectBucketsToRefresh(candidates);
		assertEquals(CONCURRENT_TASKS / 4, selected.size(), "precondition: the budget must bind");

		for (int i = 1; i < selected.size(); i++)
			assertTrue(localId.threeWayCompare(selected.get(i - 1).prefix(), selected.get(i).prefix()) <= 0,
					"a pass must run from the nearest bucket outwards");

		KBucket last = selected.get(selected.size() - 1);
		HashSet<KBucket> served = new HashSet<>(selected);
		for (KBucket deferred : candidates) {
			if (served.contains(deferred))
				continue;

			assertTrue(localId.threeWayCompare(last.prefix(), deferred.prefix()) <= 0,
					"a deferred bucket must not be nearer than a served one: " + deferred.prefix());
		}
	}
}
