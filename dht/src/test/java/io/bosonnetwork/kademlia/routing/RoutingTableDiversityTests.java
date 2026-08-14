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

package io.bosonnetwork.kademlia.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;

/**
 * One source may hold only so much of the routing table.
 * <p>
 * The collision test in {@code KBucket.put} refuses a second entry claiming the same {@code ip:port}, and
 * that is all it ever did: it scans one bucket, and it compares the socket address, port included. So a
 * sender rotating its source port was not limited even inside one bucket, and a sender choosing its ids -
 * which are free - chose which buckets to spread across. Each accepted contact could also force a split,
 * and every split is more room for the same sender.
 * </p>
 * <p>
 * The budget here is keyed on the source unit that the throttle and the suspicious-node detector already
 * count in, so that "one source" means one thing everywhere. It is scoped to globally routable addresses,
 * which is every address a production table can hold and none of the ones a test network on one machine
 * uses - the reason this rule can only be exercised here rather than end to end.
 * </p>
 */
class RoutingTableDiversityTests {
	private static final int K = 16;
	private static final int REPLACEMENTS = 8;

	/** Global unicast, so the budget counts it. TEST-NET and 2001:db8:: are bogons and would not be. */
	private static final String ONE_HOST = "12.34.56.78";

	private Id localId;
	private RoutingTable routingTable;
	private int nextHost;

	@BeforeEach
	void setup() {
		localId = Id.random();
		routingTable = new RoutingTable(localId, K, REPLACEMENTS);
		nextHost = 0;
	}

	/** An entry that has answered us, as one admitted to a bucket proper must have. */
	private static KBucketEntry entryAt(Id id, String host, int port) {
		KBucketEntry entry = new KBucketEntry(id, new InetSocketAddress(host, port));
		entry.onResponded(50);
		return entry;
	}

	/** A contact from a source unit of its own, for filling a table out. */
	private KBucketEntry someEntry(Id id) {
		int n = ++nextHost;
		return entryAt(id, "12." + ((n >> 8) & 0xff) + '.' + (n & 0xff) + ".1", 39001);
	}

	/**
	 * Fills the table until it has split into at least the requested number of buckets, so that a test
	 * about spreading across buckets has buckets to spread across.
	 */
	private void populateUntilBuckets(int buckets) {
		for (int i = 0; i < 4096 && routingTable.size() < buckets; i++)
			routingTable.put(someEntry(Id.random()));

		assertTrue(routingTable.size() >= buckets,
				"precondition: the table must split into at least " + buckets + " buckets");
	}

	/**
	 * The finding itself. One address, a fresh source port for each identity - which is what the old test
	 * compared on, and what costs a sender nothing.
	 */
	@Test
	void testAPortRotationBuysNoExtraSlots() {
		int accepted = 0;
		for (int i = 0; i < 8; i++) {
			if (routingTable.put(entryAt(Id.random(), ONE_HOST, 39001 + i)))
				accepted++;
		}

		assertEquals(routingTable.maxBucketEntriesPerSource, accepted,
				"a source took more of one bucket than its allowance");
		assertEquals(accepted, routingTable.getNumberOfEntries() + routingTable.getNumberOfReplacements(),
				"a refused entry must not be stored anywhere");
	}

	/**
	 * The per-bucket allowance follows k, and stops at two.
	 * <p>
	 * Two is a ceiling, not a constant: it exists so a legitimate pair - two nodes in one household /64,
	 * two bootstrap servers on one host - can both be contacts, and there is no reason for it to grow with
	 * the bucket. Nor can it be flat, since at the smallest configurable k two entries would be half a
	 * bucket.
	 * </p>
	 */
	@Test
	void testThePerBucketAllowanceFollowsTheBucketSize() {
		assertEquals(1, new RoutingTable(localId, 4, REPLACEMENTS).maxBucketEntriesPerSource);
		assertEquals(1, new RoutingTable(localId, 8, REPLACEMENTS).maxBucketEntriesPerSource);
		assertEquals(2, new RoutingTable(localId, 16, REPLACEMENTS).maxBucketEntriesPerSource);
		assertEquals(2, new RoutingTable(localId, 32, REPLACEMENTS).maxBucketEntriesPerSource);
		assertEquals(2, new RoutingTable(localId, 128, REPLACEMENTS).maxBucketEntriesPerSource);
	}

	/**
	 * The half the finding is about: the bucket an entry lands in is chosen by its id, and ids are free, so
	 * a per-bucket limit alone is multiplied by however many buckets the sender cares to aim at.
	 */
	@Test
	void testOneSourceCannotSpreadAcrossBuckets() {
		populateUntilBuckets(12);

		// One entry aimed at each bucket, so the per-bucket allowance never binds and what is left is the
		// table-wide budget alone.
		List<Prefix> prefixes = new ArrayList<>();
		for (KBucket bucket : routingTable.buckets())
			prefixes.add(bucket.prefix());

		int accepted = 0;
		for (Prefix prefix : prefixes) {
			if (routingTable.put(entryAt(prefix.createRandomId(), ONE_HOST, 39001 + accepted)))
				accepted++;
		}

		assertEquals(RoutingTable.MAX_TABLE_ENTRIES_PER_SOURCE, accepted,
				"a source spread further across the table than its budget");
	}

	/**
	 * The forward count is an optimization, and this is the property that makes it one rather than a change
	 * of behaviour: a source that <em>looks</em> spent is confirmed by the walk, never refused on the count.
	 * <p>
	 * The count is never decremented, so after removals it stands above the truth. Acting on it directly
	 * would lock an honest peer out of a source whose entries had simply timed out, and nothing outside
	 * would show why - the contact would just stop being admitted until the next maintenance pass.
	 * </p>
	 */
	@Test
	void testAStaleCountIsConfirmedRatherThanActedOn() throws Exception {
		populateUntilBuckets(12);

		List<Prefix> prefixes = new ArrayList<>();
		for (KBucket bucket : routingTable.buckets())
			prefixes.add(bucket.prefix());

		List<Id> admitted = new ArrayList<>();
		List<Prefix> refused = new ArrayList<>();
		for (Prefix prefix : prefixes) {
			Id id = prefix.createRandomId();
			if (routingTable.put(entryAt(id, ONE_HOST, 39001 + admitted.size())))
				admitted.add(id);
			else
				refused.add(prefix);
		}

		InetAddress unit = InetAddress.getByName(ONE_HOST);
		assertEquals(RoutingTable.MAX_TABLE_ENTRIES_PER_SOURCE, admitted.size());
		assertEquals(RoutingTable.MAX_TABLE_ENTRIES_PER_SOURCE, routingTable.countedFor(unit));
		assertFalse(refused.isEmpty(), "precondition: some buckets must hold nothing from this source");

		// One contact goes away, as a contact does. The count does not follow it down.
		routingTable.remove(admitted.get(0));
		assertEquals(RoutingTable.MAX_TABLE_ENTRIES_PER_SOURCE, routingTable.countedFor(unit),
				"precondition: the count is now above the truth, which is the case under test");

		assertTrue(routingTable.put(entryAt(refused.get(0).createRandomId(), ONE_HOST, 39100)),
				"an honest contact was refused on a count that no longer matched the table");
	}

	/**
	 * And the drift is bounded rather than merely harmless: maintenance recounts from the buckets, so a
	 * source whose entries have all gone stops looking spent and stops paying for the walk.
	 */
	@Test
	void testMaintenanceRebuildsTheCount() throws Exception {
		for (int i = 0; i < routingTable.maxBucketEntriesPerSource; i++)
			assertTrue(routingTable.put(entryAt(Id.random(), ONE_HOST, 39001 + i)));

		InetAddress unit = InetAddress.getByName(ONE_HOST);
		int held = routingTable.countedFor(unit);
		assertEquals(routingTable.maxBucketEntriesPerSource, held);

		for (KBucketEntry entry : List.copyOf(routingTable.getBucket(0).entries()))
			routingTable.remove(entry.getId());

		assertEquals(held, routingTable.countedFor(unit), "precondition: the count has not followed them");

		routingTable.maintenance(List.of(), bucket -> { });
		assertEquals(0, routingTable.countedFor(unit), "maintenance must recount from the buckets");
	}

	/**
	 * An IPv6 allocation is one source. The smallest block a subscriber gets is a routed /64, so counting
	 * per address would hand one sender 1.8e19 budgets.
	 */
	@Test
	void testOneIpv6AllocationIsOneSource() {
		int accepted = 0;
		for (int i = 1; i <= 6; i++) {
			if (routingTable.put(entryAt(Id.random(), "2606:4700:1::" + i, 39001)))
				accepted++;
		}

		assertEquals(routingTable.maxBucketEntriesPerSource, accepted,
				"distinct addresses inside one /64 were counted as distinct sources");

		assertTrue(routingTable.put(entryAt(Id.random(), "2606:4700:2::1", 39001)),
				"a different /64 is a different source and must not be refused");
	}

	/**
	 * Addresses nobody had to acquire are not counted at all.
	 * <p>
	 * This is a scope rather than a hole: in production the DHT refuses the routing-table update for any
	 * source that is not global unicast, so there is nothing else in the table to count. What it keeps
	 * working is development, where a whole test network runs on one machine - and it is why developer mode
	 * needs no say in this rule.
	 * </p>
	 */
	@Test
	void testAddressesThatCostNothingAreNotCounted() {
		for (int i = 0; i < 10; i++) {
			assertTrue(routingTable.put(entryAt(Id.random(), "127.0.0.1", 39001 + i)),
					"a loopback address was charged to the diversity budget");
			assertTrue(routingTable.put(entryAt(Id.random(), "192.168.8.1", 39101 + i)),
					"a private address was charged to the diversity budget");
		}
	}

	/**
	 * A contact we already hold makes no new claim: it is the same slot being refreshed, not another one
	 * being taken. Without this a node at a capped source would stop being able to update its own entry.
	 */
	@Test
	void testRefreshingAHeldContactIsNotANewClaim() {
		List<KBucketEntry> held = new ArrayList<>();
		for (int i = 0; i < routingTable.maxBucketEntriesPerSource; i++) {
			KBucketEntry entry = entryAt(Id.random(), ONE_HOST, 39001 + i);
			assertTrue(routingTable.put(entry));
			held.add(entry);
		}

		// The budget is spent, and a new identity from it is refused.
		assertTrue(!routingTable.put(entryAt(Id.random(), ONE_HOST, 39999)),
				"precondition: the source must be at its allowance");

		for (KBucketEntry entry : held) {
			assertTrue(routingTable.put(entryAt(entry.getId(), ONE_HOST, entry.getPort())),
					"a contact we already hold could not refresh its own entry");
			assertNotNull(routingTable.getEntry(entry.getId(), true));
		}
	}

	/**
	 * The warm start applies it too. {@code load} reaches the buckets directly when the saved table is our
	 * own and recent, so a table written before this limit existed - or under a wider one - would otherwise
	 * walk straight back in.
	 */
	@Test
	void testWarmStartAppliesTheBudget() {
		// A table as it might have been persisted without the limit: entries put into their buckets
		// directly, past put() and the budget with it.
		RoutingTable saved = new RoutingTable(localId, K, REPLACEMENTS);
		List<Id> ids = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			KBucketEntry entry = entryAt(Id.random(), ONE_HOST, 39001 + i);
			saved.bucketOf(entry.getId()).put(entry);
			ids.add(entry.getId());
		}

		assertEquals(6, saved.getNumberOfEntries(), "precondition: the saved table is over the allowance");

		byte[] data = saved.save();
		assertNotNull(data);

		RoutingTable loaded = new RoutingTable(localId, K, REPLACEMENTS);
		loaded.load(data);

		int survived = 0;
		for (Id id : ids) {
			if (loaded.getEntry(id, true) != null)
				survived++;
		}

		assertEquals(loaded.maxBucketEntriesPerSource, survived,
				"the warm start let a persisted table past the budget");
		assertNull(loaded.getEntry(ids.get(ids.size() - 1), true),
				"the entries kept should be the ones read first, not the last ones in the file");
	}
}
