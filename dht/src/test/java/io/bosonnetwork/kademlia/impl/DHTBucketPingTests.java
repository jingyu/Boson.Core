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

import net.datafaker.Faker;

import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.CryptoIdentity;
import io.bosonnetwork.kademlia.routing.KBucket;
import io.bosonnetwork.kademlia.routing.KBucketEntry;
import io.bosonnetwork.kademlia.security.Blacklist;

/**
 * Covers {@link DHT#selectBucketsToPing()} - which cached buckets a warm start revalidates now.
 * <p>
 * The budget here is not about bandwidth. Ping tasks are cheap, but each one holds a
 * {@code TaskManager} slot for as long as its bucket takes to drain, and a bucket of dead cached
 * contacts drains at the RPC timeout. A sweep that claims every slot leaves the bootstrap queued
 * behind it - and the bootstrap is what reconnects a node whose cache has gone stale.
 * </p>
 * <p>
 * Like the other selections, this one is a pure function of the routing table, so it is tested here
 * without a network, an event loop or a deployed verticle.
 * </p>
 */
public class DHTBucketPingTests {
	// Kademlia parameters, as KadNode would pass them down from NodeConfiguration.KademliaOptions.
	private static final int K = 16;
	private static final int REPLACEMENTS = 8;
	private static final int ALPHA = 3;
	private static final int CONCURRENT_TASKS = 32;

	private static final Faker faker = new Faker();

	/**
	 * Builds a DHT that is never deployed: the routing table is created by the constructor, and the
	 * selection reads nothing else. {@code concurrentTasks} is a parameter because it is what the
	 * budget is derived from.
	 */
	private static DHT newDht(int concurrentTasks) {
		return new DHT(new CryptoIdentity(), Network.IPv4, "127.0.0.1", 39111, List.of(),
				ALPHA, K, REPLACEMENTS, concurrentTasks,
				null, null, new TokenManager(),
				Blacklist.empty(), false, false, true, null);
	}

	/**
	 * Adds the given number of reachable entries at random ids, which density-driven splitting turns
	 * into a table of many buckets in varying states of fullness - the shape a persisted table has when
	 * it is loaded back on a warm start.
	 */
	private static void populate(DHT dht, int count) {
		for (int i = 0; i < count; i++) {
			KBucketEntry entry = new KBucketEntry(Id.random(), new InetSocketAddress(
					faker.internet().getPublicIpV4Address(), faker.number().numberBetween(1024, 65535)));
			// Marks the entry reachable, exactly as DHT.onMessage does for a responding node. Splitting
			// only ever happens for reachable entries, so the table stays a single bucket without it.
			entry.onResponded(50);
			dht.getRoutingTable().put(entry);
		}
	}

	private static List<KBucket> nonEmptyBuckets(DHT dht) {
		List<KBucket> result = new ArrayList<>();
		dht.getRoutingTable().forEachBucket(bucket -> {
			if (!bucket.isEmpty())
				result.add(bucket);
		});
		return result;
	}

	/**
	 * An empty bucket would produce a task with an empty todo queue: dispatched, holding a slot,
	 * pinging nobody. The inline loop this selection replaced did exactly that.
	 */
	@Test
	void testEmptyBucketsAreExcluded() {
		DHT dht = newDht(CONCURRENT_TASKS);
		populate(dht, 1000);

		for (KBucket bucket : dht.selectBucketsToPing())
			assertFalse(bucket.isEmpty(), "empty bucket selected: " + bucket.prefix());
	}

	/**
	 * Nothing is held back, whatever the table size or the slot count. The whole loaded cache is
	 * revalidated, so what this selection decides is priority, not membership - the bound is on how
	 * many are in flight at once, and that belongs to {@code pingRoutingTable}. A cache cleaned at the
	 * front and left dirty at the back would be the worst of both.
	 */
	@Test
	void testSelectsEveryNonEmptyBucket() {
		DHT dht = newDht(CONCURRENT_TASKS);
		populate(dht, 1000);

		List<KBucket> nonEmpty = nonEmptyBuckets(dht);
		assertTrue(nonEmpty.size() > CONCURRENT_TASKS / 2,
				"precondition: the table must be bigger than one batch, or this proves nothing");

		assertEquals(new HashSet<>(nonEmpty), new HashSet<>(dht.selectBucketsToPing()));
	}

	/**
	 * And it does not shrink with the slot count either - a node configured down to very few task slots
	 * sweeps its whole table, just fewer buckets at a time.
	 */
	@Test
	void testCoverageDoesNotDependOnTaskSlots() {
		DHT dht = newDht(1);
		populate(dht, 1000);

		assertEquals(nonEmptyBuckets(dht).size(), dht.selectBucketsToPing().size());
	}

	/**
	 * Ordering. Staleness cannot discriminate on a warm start - load() does not stamp the buckets it
	 * reads, so they are all equally stale - and the contacts worth revalidating first are the ones a
	 * lookup actually routes through, which are the ones nearest our own id.
	 * <p>
	 * Closeness is XOR distance from the local id to the bucket's prefix, not prefix depth. Depth
	 * counts a prefix's fixed bits and says nothing about whether they match ours; it would coincide
	 * with closeness only if the home bucket were the only one that ever split, and
	 * {@code RoutingTable.needsSplit} splits any full bucket whose new entry lands in the high branch.
	 * This test caught that: it failed on a table where a far branch had split deeper than home.
	 * </p>
	 */
	@Test
	void testClosestToHomeIsSweptFirst() {
		DHT dht = newDht(8);
		populate(dht, 1000);

		Id localId = dht.getIdentity().getId();
		List<KBucket> selected = dht.selectBucketsToPing();
		assertTrue(selected.size() > 1, "precondition: there must be an order to check");

		// The home bucket needs no special case in the selection and gets none here: its prefix is a
		// prefix of our id, so it is nearest by construction and has to fall out of the distance sort.
		assertTrue(selected.get(0).isHomeBucket(), "the home bucket must be swept first");

		for (int i = 1; i < selected.size(); i++)
			assertTrue(localId.threeWayCompare(selected.get(i - 1).prefix(), selected.get(i).prefix()) <= 0,
					"selection must run from the nearest prefix outwards");
	}
}
