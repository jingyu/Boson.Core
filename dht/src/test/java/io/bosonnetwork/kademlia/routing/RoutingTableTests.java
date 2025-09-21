package io.bosonnetwork.kademlia.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import net.datafaker.Faker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;

class RoutingTableTests {
	static final Faker faker = new Faker();
	RoutingTable routingTable;
	Id localId;

	static class StubEntry extends KBucketEntry {
		StubEntry(Id id) {
			this(id, true);
		}

		StubEntry(Id id, boolean reachable) {
			super(id, new InetSocketAddress(faker.internet().getPublicIpV4Address(), faker.number().numberBetween(2048,65535)));
			setReachable(reachable);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if ((o instanceof StubEntry that))
				return Objects.equals(getId(), that.getId());

			return false;
		}

		@Override
		public int hashCode() {
			return getId().hashCode();
		}
	}

	private Id genId(int fill) {
		byte[] bytes = new byte[Id.BYTES];
		Arrays.fill(bytes, (byte) fill);
		return Id.of(bytes);
	}

	@BeforeEach
	void setup() {
		localId = genId(0x01);
		routingTable = new RoutingTable(localId);
	}

	@Test
	void testPutAndGetEntry() {
		StubEntry entry = new StubEntry(genId(2));
		routingTable.put(entry);
		KBucketEntry retrieved = routingTable.getEntry(entry.getId());
		assertNotNull(retrieved);
		assertSame(entry, retrieved);
	}

	@Test
	void testBucketOf() {
		// Insert entries to fill first two bucket unfulled
		Prefix p = new Prefix(Id.zero(), 1);
		for (int i = 0; i < KBucket.MAX_ENTRIES - 2; i++) {
			StubEntry entry = new StubEntry(p.createRandomId());
			routingTable.put(entry);
		}
		p = new Prefix(Id.ofBit(0), 1);
		for (int i = 0; i < KBucket.MAX_ENTRIES - 2; i++) {
			StubEntry entry = new StubEntry(p.createRandomId());
			routingTable.put(entry);
		}

		assertEquals(2, routingTable.size());
		assertFalse(routingTable.getBucket(0).isFull());
		assertFalse(routingTable.getBucket(1).isFull());

		StubEntry min = new StubEntry(Id.min());
		routingTable.put(min);

		StubEntry max = new StubEntry(Id.max());
		routingTable.put(max);

		KBucket bucketMin = routingTable.bucketOf(min.getId());
		assertNotNull(bucketMin);
		KBucket bucketMax = routingTable.bucketOf(max.getId());
		assertNotNull(bucketMax);
		assertNotEquals(bucketMin, bucketMax);
	}

	@Test
	void testRemoveAndRemoveIfBad() {
		StubEntry entry = new StubEntry(genId(3));
		routingTable.put(entry);
		assertNotNull(routingTable.getEntry(entry.getId()));

		boolean removed = routingTable.remove(entry.getId());
		assertTrue(removed);
		assertNull(routingTable.getEntry(entry.getId()));

		// removeIfBad should remove if entry exists
		routingTable.put(entry);
		// mark the entry as bad
		for (int i = 0; i <= KBucketEntry.MAX_FAILURES; i++)
			entry.onTimeout();
		// add a verified replacement
		routingTable.bucketOf(entry.getId()).putAsReplacement(new StubEntry(Id.random()));

		assertNotNull(routingTable.removeIfBad(entry.getId(), false));
		assertNull(routingTable.getEntry(entry.getId()));

		// removeIfBad should not remove if entry does not exist
		Id other = genId(4);
		assertNull(routingTable.removeIfBad(other, false));
	}

	@Test
	void testOnTimeoutAndOnResponded() {
		StubEntry entry = new StubEntry(genId(5));
		routingTable.put(entry);
		// onTimeout returns true if entry is removed due to timeout
		boolean timedOut = routingTable.onTimeout(entry.getId());
		// Since entry is fresh, timeout likely returns false (depends on implementation)
		// We allow either true or false but test that entry is removed if true
		if (timedOut) {
			assertNull(routingTable.getEntry(entry.getId()));
		} else {
			assertNotNull(routingTable.getEntry(entry.getId()));
		}

		// onResponded should refresh or add entry
		routingTable.onResponded(entry.getId(), 123);
		assertNotNull(routingTable.getEntry(entry.getId()));
	}

	@Test
	void testMaintenanceAndMerge() {
		// Insert many entries to fill first two bucket
		List<StubEntry> entries = new ArrayList<>();
		for (int i = 0; i < KBucket.MAX_ENTRIES * 2; i++) {
			StubEntry entry = new StubEntry(Id.random());
			routingTable.put(entry);
			entries.add(entry);
		}

		// now at least the first two(maybe more) buckets are filled(not all full filled)
		assertTrue(routingTable.size() >= 2);

		// now remove entries from buckets make total number of entries less than KBucket.MAX_ENTRIES
		routingTable.forEachBucket(bucket -> {
			List<Id> toRemove = bucket.stream().skip(2).map(KBucketEntry::getId).toList();
			for (Id id : toRemove) {
				bucket.remove(id);
				entries.removeIf(e -> e.getId().equals(id));
			}
		});

		routingTable.maintenance(Collections.emptyList(), unused -> {});

		// After maintenance, bucket should be merged
		assertEquals(1, routingTable.size());
		// We check that entries still exist
		for (StubEntry entry : entries)
			assertNotNull(routingTable.getEntry(entry.getId()));
	}

	@Test
	void testNeedsSplitAndSplit() {
		// Insert enough entries to fill first bucket
		List<StubEntry> entries = new ArrayList<>();
		for (int i = 0; i < KBucket.MAX_ENTRIES; i++) {
			StubEntry entry = new StubEntry(Id.random());
			routingTable.put(entry);
			entries.add(entry);
		}

		// now the only bucket is full
		assertEquals(1, routingTable.size());

		// should not split because the new entry belongs to the low branch
		StubEntry entry = new StubEntry(routingTable.getBucket(0).prefix().splitBranch(false).createRandomId());
		routingTable.put(entry);
		entries.add(entry);
		assertEquals(1, routingTable.size());

		// Should split to two now
		// Make sure the new entry belongs to the high branch
		entry = new StubEntry(routingTable.getBucket(0).prefix().splitBranch(true).createRandomId());
		routingTable.put(entry);
		entries.add(entry);

		// After split, the only bucket should be split to two
		assertEquals(2, routingTable.size());
		// We check that entries still exist
		for (StubEntry e : entries)
			assertNotNull(routingTable.getEntry(e.getId()));
	}

	@Test
	void testGetRandomEntryAndGetRandomEntries() {
		List<KBucketEntry> entries = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			KBucketEntry entry = new StubEntry(genId(i + 1));
			entries.add(entry);
			routingTable.put(entry);
		}

		KBucketEntry randomEntry = routingTable.getRandomEntry();
		assertNotNull(randomEntry);
		assertTrue(entries.contains(randomEntry));

		/*/
		List<KBucketEntry> randomEntries = routingTable.getRandomEntries(5);
		assertEquals(5, randomEntries.size());
		for (KBucketEntry e : randomEntries) {
			assertTrue(entries.contains(e));
		}
		*/
	}

	@Test
	void testRecursiveSplit() {
		List<StubEntry> entries = new ArrayList<>();
		int totalEntries = KBucket.MAX_ENTRIES * 4; // enough to cause multiple splits
		for (int i = 0; i < totalEntries; i++) {
			StubEntry entry = new StubEntry(Id.random());
			routingTable.put(entry);
			entries.add(entry);
		}
		// After multiple splits, bucket count should be > 1
		assertTrue(routingTable.size() > 1);
		// All entries should still be retrievable
		for (StubEntry e : entries) {
			assertNotNull(routingTable.getEntry(e.getId()));
		}

		System.out.println(routingTable);
	}

	@Test
	void testMergeEdgeCase() {
		// Create two sibling buckets with combined effective size exactly MAX_ENTRIES
		// First fill one bucket with MAX_ENTRIES entries
		Prefix low = new Prefix(Id.min(), 1);
		for (int i = 0; i < KBucket.MAX_ENTRIES; i++) {
			StubEntry entry = new StubEntry(low.createRandomId());
			routingTable.put(entry);
		}

		// Split to create two buckets
		Prefix high = new Prefix(Id.ofBit(0), 1);
		StubEntry splitter = new StubEntry(high.createRandomId());
		routingTable.put(splitter);
		assertEquals(2, routingTable.size());

		// Confirm combined size is MAX_ENTRIES
		int combinedSize = routingTable.getBucket(0).size() + routingTable.getBucket(1).size();
		assertEquals(KBucket.MAX_ENTRIES + 1, combinedSize);

		assertTrue(routingTable.getBucket(0).isFull());
		assertEquals(1, routingTable.getBucket(1).size());
		KBucket bucket = routingTable.getBucket(0);
		bucket.remove(bucket.get(0).getId());

		// Call maintenance and expect buckets to merge
		routingTable.maintenance(Collections.emptyList(), unused -> {});

		// Buckets should merge to one
		assertEquals(1, routingTable.size());
	}


	@Test
	void testGetClosestNodesWithEmptyTable() {
		Id targetId = Id.random();
		// Test with empty routing table
		KClosestNodes closest = routingTable.getClosestNodes(targetId, 5);
		closest.fill();
		assertEquals(0, closest.size(), "Empty table should return no nodes");
		assertFalse(closest.isComplete(), "Should not be complete with no nodes");
		assertTrue(closest.entries().isEmpty(), "Entries list should be empty");
		assertTrue(closest.nodes().isEmpty(), "Nodes list should be empty");
	}

	@Test
	void testGetClosestNodesWithSingleBucket() {
		List<KBucketEntry> entries = new ArrayList<>(5);
		// Add 5 nodes to one bucket
		for (int i = 0; i < 5; i++) {
			StubEntry entry = new StubEntry(Id.random());
			routingTable.put(entry);
			entries.add(entry);
		}

		Id targetId = Id.random();
		KClosestNodes closest = routingTable.getClosestNodes(targetId, 3).fill();
		assertEquals(3, closest.size(), "Should return 3 closest nodes");
		assertTrue(closest.isComplete(), "Should be complete for k=3");
		// Check sorted by distance
		entries.sort((e1, e2) -> targetId.threeWayCompare(e1.getId(), e2.getId()));
		assertEquals(entries.subList(0, 3), closest.entries());
	}

	@Test
	void testGetClosestNodesExcludeLocalNode() {
		// Add local node and others
		KBucketEntry localEntry = new StubEntry(localId);
		routingTable.put(localEntry);

		List<KBucketEntry> entries = new ArrayList<>(5);
		// Add 5 nodes to one bucket
		for (int i = 0; i < KBucket.MAX_ENTRIES - 1; i++) {
			StubEntry entry = new StubEntry(Id.random());
			routingTable.put(entry);
			entries.add(entry);
		}

		Id targetId = Id.random();
		KClosestNodes closest = routingTable.getClosestNodes(targetId, 5).fill();
		assertEquals(5, closest.size(), "Should return 5 nodes excluding local");
		assertFalse(closest.entries().stream().anyMatch(e -> e.getId().equals(localId)),
				"Local node should be excluded");

		// Check sorted by distance
		entries.sort((e1, e2) -> targetId.threeWayCompare(e1.getId(), e2.getId()));
		assertEquals(entries.subList(0, 5), closest.entries());
	}

	@Test
	void testGetClosestNodesWithReplacements() {
		Id targetId = Id.random();

		// Add 3 main entries, 2 replacements
		KBucket bucket = routingTable.bucketOf(targetId);
		// Add 5 nodes to one bucket
		for (int i = 0; i < 5; i++)
			bucket.put(new StubEntry(targetId.getIdByDistance(16+i)));

		KBucketEntry rep1 = new StubEntry(targetId.getIdByDistance(1));
		bucket.putAsReplacement(rep1);
		KBucketEntry rep2 = new StubEntry(targetId.getIdByDistance(2));
		bucket.putAsReplacement(rep2);
		// Include replacements
		KClosestNodes closest =routingTable.getClosestNodes(targetId, 5).includeReplacements().fill();
		closest.fill();
		assertEquals(5, closest.size(), "Should include replacements");
		assertTrue(closest.entries().contains(rep1), "Should include close replacement");
		assertTrue(closest.entries().contains(rep2), "Should include replacement");
	}

	@Test
	void testGetClosestNodesWithDefaultFilterExcludesBad() {
		// Add mix of good/bad entries
		StubEntry bad = new StubEntry(Id.random());
		routingTable.put(bad);
		bad.setReachable(false);

		for (int i = 0; i < 5; i++)
			routingTable.put(new StubEntry(Id.random()));

		Id targetId = Id.random();
		KClosestNodes closest = routingTable.getClosestNodes(targetId, 5).fill();
		assertEquals(5, closest.size(), "Should return only good entries");
		assertFalse(closest.entries().stream().anyMatch(KBucketEntry::needsReplacement),
				"Filtered nodes should not need replacement");
		assertFalse(closest.entries().contains(bad));
	}

	@Test
	void testGetClosestNodesWithSparseTableUnderfill() {
		// Add fewer than k nodes
		for (int i = 0; i < 3; i++)
			routingTable.put(new StubEntry(Id.random()));

		// Add a bad entries
		StubEntry bad = new StubEntry(Id.random());
		routingTable.put(bad);
		bad.setReachable(false);

		Id targetId = Id.random();
		KClosestNodes closest = routingTable.getClosestNodes(targetId, 5).fill();
		assertEquals(3, closest.size(), "Should return all available nodes");
		assertFalse(closest.isComplete(), "Should not be complete for k=5");
	}

	@Test
	void testGetClosestNodesWithMultipleBuckets() {
		// Simulate bucket splitting
		for (int i = 0; i < 256; i++)
			routingTable.put(new StubEntry(Id.random()));

		// Force the target bucket entries is less then K
		Id targetId = Id.random();
		KBucket bucket = routingTable.bucketOf(targetId);
		bucket.stream()
				.skip(Math.min(bucket.size(), 3))
				.map(KBucketEntry::getId)
				.toList()
				.forEach(id -> routingTable.remove(id));

		KClosestNodes closest = routingTable.getClosestNodes(targetId, 8).fill();
		assertEquals(8, closest.size(), "Should collect from multiple buckets");
		// Verify distances
		List<KBucketEntry> sorted = new ArrayList<>(closest.entries());
		sorted.sort((e1, e2) -> targetId.threeWayCompare(e1.getId(), e2.getId()));
		assertEquals(sorted, closest.entries());

		// assert target bucket entries are in closest
		routingTable.bucketOf(targetId).entries().forEach(e -> assertTrue(closest.entries().contains(e)));
	}

	@Test
	void testGetClosestNodesWithMultipleBucketsMoreThanK() {
		// Simulate bucket splitting
		for (int i = 0; i < 256; i++)
			routingTable.put(new StubEntry(Id.random()));

		// Force the target bucket entries is less then K
		Id targetId = Id.random();
		KBucket bucket = routingTable.bucketOf(targetId);
		bucket.stream()
				.skip(Math.min(bucket.size(), 3))
				.map(KBucketEntry::getId)
				.toList()
				.forEach(id -> routingTable.remove(id));

		KClosestNodes closest = routingTable.getClosestNodes(targetId, 24).fill();
		assertEquals(24, closest.size(), "Should collect from multiple buckets");
		// Verify distances
		List<KBucketEntry> sorted = new ArrayList<>(closest.entries());
		sorted.sort((e1, e2) -> targetId.threeWayCompare(e1.getId(), e2.getId()));
		assertEquals(sorted, closest.entries());

		// assert target bucket entries are in closest
		routingTable.bucketOf(targetId).entries().forEach(e -> assertTrue(closest.entries().contains(e)));
	}

	@Test
	void testGetClosestNodesWithZeroCapacity() {
		Id targetId = Id.random();
		KClosestNodes closest = routingTable.getClosestNodes(targetId, 0);
		closest.fill();
		assertEquals(0, closest.size(), "Zero capacity should return empty");
		assertTrue(closest.isComplete(), "Zero capacity is complete");
	}

	/*/
	@Test
	void testRandomEntriesBoundary() {
		List<KBucketEntry> entries = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			KBucketEntry entry = new StubEntry(genId(i + 1));
			entries.add(entry);
			routingTable.put(entry);
		}
		// Request 0 entries
		List<KBucketEntry> zeroEntries = routingTable.getRandomEntries(0);
		assertNotNull(zeroEntries);
		assertEquals(0, zeroEntries.size());

		// Request more than total entries
		List<KBucketEntry> manyEntries = routingTable.getRandomEntries(10);
		assertNotNull(manyEntries);
		assertEquals(entries.size(), manyEntries.size());
		for (KBucketEntry e : manyEntries) {
			assertTrue(entries.contains(e));
		}
	}
	*/

	@Test
	void testSaveAndLoad() throws Exception {
		Random rnd = new Random();
		for (int i = 0; i < 1000; i++)
			routingTable.put(new StubEntry(Id.random(), rnd.nextBoolean()));

		Path tempFile = Files.createTempFile("routingTable", ".cbor");
		routingTable.save(tempFile);

		RoutingTable loaded = new RoutingTable(localId);
		loaded.load(tempFile);
		System.out.printf(">>>>>>>> The loaded routing table[entries: %d, replacements: %d]\n", loaded.getNumberOfEntries(), loaded.getNumberOfReplacements());
		assertEquals(routingTable.size(), loaded.size());
		assertEquals(routingTable.getNumberOfEntries(), loaded.getNumberOfEntries());
		assertEquals(routingTable.getNumberOfReplacements(), loaded.getNumberOfReplacements());

		System.out.println(tempFile);
		//Files.delete(tempFile);
	}

	@Test
	void testEmptyTableSaveAndLoad() throws Exception {
		Path tempFile = Files.createTempFile("emptyRoutingTable", ".cbor");
		routingTable.save(tempFile);

		RoutingTable loaded = new RoutingTable(localId);
		loaded.load(tempFile);
		assertEquals(1, loaded.size());
		assertEquals(0, loaded.getNumberOfEntries());

		Files.delete(tempFile);
	}

	@Test
	void testStressRandomOperations() {
		final int NUM_OPERATIONS = 10_000;
		Random rnd = new Random();

		List<StubEntry> entries = new ArrayList<>();

		for (int i = 0; i < 256 * KBucket.MAX_ENTRIES; i++) {
			StubEntry entry = new StubEntry(Id.random());
			routingTable.put(entry);
			entries.add(entry);
		}

		for (int op = 0; op < NUM_OPERATIONS; op++) {
			int action = rnd.nextInt(5);
			switch (action) {
				case 0: // insert
					StubEntry newEntry = new StubEntry(Id.random());
					routingTable.put(newEntry);
					entries.add(newEntry);
					break;
				case 1: // remove
					if (!entries.isEmpty()) {
						int idx = rnd.nextInt(entries.size());
						StubEntry remEntry = entries.remove(idx);
						routingTable.remove(remEntry.getId());
					}
					break;
				case 2: // onTimeout
					if (!entries.isEmpty()) {
						StubEntry entry = entries.get(rnd.nextInt(entries.size()));
						routingTable.onTimeout(entry.getId());
					}
					break;
				case 3: // onResponded
					if (!entries.isEmpty()) {
						StubEntry entry = entries.get(rnd.nextInt(entries.size()));
						routingTable.onResponded(entry.getId(), rnd.nextInt(1000));
					}
					break;
				case 4: // maintenance
					routingTable.maintenance(Collections.emptyList(), unused -> {});
					break;
			}

			// Every 100 operations, check invariants
			if (op % 100 == 0) {
				// Total entries ≤ max possible entries
				int totalEntries = 0;
				Set<Id> seen = new HashSet<>();
				for (int i = 0; i < routingTable.size(); i++) {
					KBucket bucket = routingTable.getBucket(i);
					totalEntries += bucket.size();
					for (KBucketEntry e : bucket.entries()) {
						// No duplicates
						assertFalse(seen.contains(e.getId()), "Duplicate entry found");
						seen.add(e.getId());
						// All entries retrievable via contains
						assertTrue(routingTable.contains(e.getId()), "Entry missing in contains");
					}
				}
				assertTrue(totalEntries <= entries.size(), "Total entries exceed max possible");
			}
		}
	}
}