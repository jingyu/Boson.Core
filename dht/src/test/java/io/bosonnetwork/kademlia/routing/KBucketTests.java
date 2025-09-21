package io.bosonnetwork.kademlia.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.datafaker.Faker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.bosonnetwork.Id;
import io.bosonnetwork.kademlia.rpc.RpcServer;

public class KBucketTests {
	private static final Faker faker = new Faker();

	private KBucket bucket;
	private Id localId;

	@BeforeEach
	void setUp() {
		Prefix prefix = new Prefix(Id.random(), 16); // adjust according to your Prefix/Id impl
		bucket = new KBucket(prefix, p -> true); // home bucket
		localId = prefix.createRandomId();
	}

	private static KBucketEntry newEntry(Id id) {
		return new KBucketEntry(id, new InetSocketAddress(faker.internet().publicIpV4Address(), faker.number().numberBetween(1024, 65535)));
	}

	@SuppressWarnings("SameParameterValue")
	private KBucketEntry addEntry(boolean reachable) {
		do {
			KBucketEntry entry = newEntry(bucket.prefix().createRandomId());
			if (bucket.contains(entry.getId(), true))
				continue;

			entry.setReachable(reachable);
			bucket.put(entry);
			return entry;
		} while (true);
	}

	private KBucketEntry addEntry() {
		return addEntry(true);
	}

	@SuppressWarnings("SameParameterValue")
	private KBucketEntry addReplacement(boolean reachable) {
		do {
			KBucketEntry entry = newEntry(bucket.prefix().createRandomId());
			if (bucket.contains(entry.getId(), true))
				continue;

			entry.setReachable(reachable);
			bucket.putAsReplacement(entry);
			return entry;
		} while (true);
	}

	private KBucketEntry addReplacement() {
		return addReplacement(true);
	}

	@SuppressWarnings("SameParameterValue")
	private List<KBucketEntry> addEntries(int count, boolean reachable) {
		List<KBucketEntry> entries = new ArrayList<>(count);
		do {
			KBucketEntry entry = newEntry(bucket.prefix().createRandomId());
			if (bucket.contains(entry.getId(), true))
				continue;

			entry.setReachable(reachable);
			bucket.put(entry);
			entries.add(entry);
			count--;
		} while (count > 0);

		return entries;
	}

	private List<KBucketEntry> addEntries(int count) {
		return addEntries(count, true);
	}

	@SuppressWarnings("SameParameterValue")
	private List<KBucketEntry> addReplacements(int count, boolean reachable) {
		List<KBucketEntry> entries = new ArrayList<>(count);
		do {
			KBucketEntry entry = newEntry(bucket.prefix().createRandomId());
			if (bucket.contains(entry.getId(), true))
				continue;

			entry.setReachable(reachable);
			bucket.putAsReplacement(entry);
			entries.add(entry);
			count--;
		} while (count > 0);

		return entries;
	}

	@SuppressWarnings("UnusedReturnValue")
	private List<KBucketEntry> addReplacements(int count) {
		return addReplacements(count, true);
	}

	@Test
	void testPutAndUpdate() {
		// put new entry to main entries
		KBucketEntry entry = addEntry();
		assertEquals(1, bucket.size());
		assertEquals(0, bucket.replacementSize());

		// put again with same id → merge should not increase size
		bucket.put(new KBucketEntry(entry.getId(), entry.getAddress()));
		assertEquals(1, bucket.size());

		// put new entry to replacement
		entry = addEntry(false);
		assertEquals(1, bucket.size());
		assertEquals(1, bucket.replacementSize());

		// put again with same id → merge should not increase size
		bucket.put(new KBucketEntry(entry.getId(), entry.getAddress()));
		assertEquals(1, bucket.size());
		assertEquals(1, bucket.replacementSize());
	}

	@Test
	void testGet() {
		KBucketEntry main = addEntry();
		assertNotNull(bucket.get(main.getId(), false));
		assertNotNull(bucket.get(main.getId(), true));

		KBucketEntry repl = addEntry(false);
		// Not found in main entries
		assertNull(bucket.get(repl.getId(), false));
		// Found when searching replacements
		assertSame(repl, bucket.get(repl.getId(), true));
	}

	@Test
	void testIsFullBehavior() {
		assertFalse(bucket.isFull(), "New bucket should not be full");
		addEntries(KBucket.MAX_ENTRIES);
		assertTrue(bucket.isFull(), "Bucket should be full at MAX_ENTRIES");
	}

	@Test
	void testPromoteVerifiedReplacement() {
		KBucketEntry main = addEntry();
		assertEquals(1, bucket.size());
		assertTrue(bucket.contains(main.getId(), false));
		assertEquals(0, bucket.replacementSize());

		KBucketEntry repl = addReplacement();
		assertEquals(1, bucket.size());
		assertEquals(1, bucket.replacementSize());

		bucket.promoteVerifiedReplacement();
		assertEquals(2, bucket.size());
		assertEquals(0, bucket.replacementSize());
		assertTrue(bucket.entries().contains(repl) || bucket.replacements().isEmpty());
	}

	@Test
	void testRemoveIfBad() {
		addEntries(4);

		KBucketEntry entry = addEntry();
		assertEquals(5, bucket.size());

		KBucketEntry removed = bucket.removeIfBad(entry.getId(), false);
		assertNull(removed);
		assertEquals(5, bucket.size());

		for (int i = 0; i <= KBucketEntry.MAX_FAILURES; i++)
			entry.onTimeout();

		// no replacement, not removed even it's bad
		removed = bucket.removeIfBad(entry.getId(), false);
		assertNull(removed);
		assertEquals(5, bucket.size());

		KBucketEntry repl = addReplacement();
		assertEquals(5, bucket.size());
		assertEquals(1, bucket.replacementSize());

		removed = bucket.removeIfBad(entry.getId(), false);
		assertNotNull(removed);
		assertSame(entry, removed);
		assertEquals(5, bucket.size());
		assertEquals(0, bucket.replacementSize());
		assertTrue(bucket.entries().contains(repl));
		assertFalse(bucket.entries().contains(entry));
	}

	@Test
	void testRemoveIfBadForce() {
		addEntries(4);

		KBucketEntry entry = addEntry();
		assertEquals(5, bucket.size());

		KBucketEntry removed = bucket.removeIfBad(entry.getId(), false);
		assertNull(removed);
		assertEquals(5, bucket.size());

		removed = bucket.removeIfBad(entry.getId(), true);
		assertNotNull(removed);
		assertSame(entry, removed);
		assertEquals(4, bucket.size());
		assertFalse(bucket.entries().contains(entry));
	}

	@Test
	void testRefreshLogic() throws InterruptedException {
		KBucketEntry entry = addEntry();

		// simulate time pass
		Thread.sleep(1000);
		assertFalse(bucket.needsToBeRefreshed());

		// mock last refresh time
		bucket.updateRefreshTime(System.currentTimeMillis() - KBucket.REFRESH_INTERVAL - 10000);
		// mark entry needs ping
		entry.onTimeout();
		entry.setLastSeen(System.currentTimeMillis() - 35_000);
		assertTrue(bucket.needsToBeRefreshed());

		// manually force refresh trigger
		bucket.updateRefreshTime();
		assertFalse(bucket.needsToBeRefreshed());
	}

	@Test
	void testPutResetsRefreshTime() {
		// make the bucket is refreshed
		bucket.updateRefreshTime();

		// Fill the bucket to full
		List<KBucketEntry> entries = addEntries(KBucket.MAX_ENTRIES);
		// make the first entry needs ping
		entries.get(0).onTimeout();
		entries.get(0).setLastSeen(System.currentTimeMillis() - 35_000);

		assertFalse(bucket.needsToBeRefreshed());

		addEntry();
		// After put, refresh time should be reset to 0,
		// meaning needsToBeRefreshed returns true again until updateRefreshTime() is called
		assertTrue(bucket.needsToBeRefreshed(), "Put should reset refresh time to trigger refresh");
	}

	@Test
	void testCleanupRemovesSelfAndBootstrap() {
		addEntries(5);

		KBucketEntry self = newEntry(localId);
		self.setReachable(true);
		bucket.put(self);
		assertEquals(6, bucket.size());
		assertTrue(bucket.entries().contains(self));

		// cleanup will remove self directly, not trigger the drop handler
		AtomicBoolean dropped = new AtomicBoolean(false);
		bucket.cleanup(localId, Collections.emptySet(), e -> dropped.set(true));

		assertEquals(5, bucket.size());
		assertFalse(dropped.get());
		assertFalse(bucket.entries().contains(self));

		KBucketEntry bootstrap1 = addEntry();
		KBucketEntry bootstrap2 = addEntry();

		// make the bucket full
		addEntry();

		assertEquals(8, bucket.size());
		assertTrue(bucket.isFull());
		assertTrue(bucket.entries().contains(bootstrap1) && bucket.entries().contains(bootstrap2));

		// bucket is full, self not in the bucket, so will remove one of bootstraps, not trigger the drop handle
		dropped.set(false);
		bucket.cleanup(localId, Set.of(bootstrap1.getId(), bootstrap2.getId()), e -> dropped.set(true));

		// one of bootstrap should be removed
		assertEquals(7, bucket.size());
		assertFalse(dropped.get());
		assertFalse(bucket.entries().contains(bootstrap1) && bucket.entries().contains(bootstrap2));

		// make the bucket full
		addEntry();

		assertEquals(8, bucket.size());
		assertTrue(bucket.isFull());

		// bucket is full, self not in the bucket, so will remove one of bootstraps, not trigger the drop handle
		dropped.set(false);
		bucket.cleanup(localId, Set.of(bootstrap1.getId(), bootstrap2.getId()), e -> dropped.set(true));

		assertEquals(7, bucket.size());
		assertFalse(dropped.get());
		assertFalse(bucket.entries().contains(bootstrap1) && bucket.entries().contains(bootstrap2));
	}

	@Test
	void testCleanupRemovesWrongPrefixEntries() {
		addEntries(4);

		// Insert entry with ID outside prefix
		Prefix wrongPrefix = new Prefix(Id.random(), bucket.prefix().getDepth() - 1);
		Id wrongId = wrongPrefix.createRandomId();
		KBucketEntry wrongEntry = newEntry(wrongId);
		wrongEntry.setReachable(true);
		bucket.put(wrongEntry);

		assertEquals(5, bucket.size());
		AtomicBoolean dropped = new AtomicBoolean(false);
		bucket.cleanup(localId, Collections.emptySet(), e -> dropped.set(true));
		// the wrong entry should be removed, also trigger the drop handler
		assertEquals(4, bucket.size());
		assertTrue(dropped.get());
		assertFalse(bucket.entries().contains(wrongEntry));
	}

	@Test
	void testMainEntriesEvictionWhenOverCapacity() {
		// Fill the replacements beyond MAX_ENTRIES, ensure pruning to MAX_ENTRIES
		int max = KBucket.MAX_ENTRIES;
		addEntries(max + 3);

		assertTrue(bucket.size() <= max);
		assertEquals(3, bucket.replacementSize());
	}

	@Test
	void testReplacementEvictionWhenOverCapacity() {
		// Fill the replacements beyond MAX_ENTRIES, ensure pruning to MAX_ENTRIES
		int max = KBucket.MAX_ENTRIES;
		addReplacements(max + 3);
		assertTrue(bucket.replacementSize() <= max);
	}

	@Test
	void testOnTimeoutPromotesReplacement() {
		// Fill bucket to capacity
		int max = KBucket.MAX_ENTRIES;
		List<KBucketEntry> entries = addEntries(max);

		// Timeout the first entry until it is bad
		KBucketEntry first = entries.get(0);
		for (int i = 0; i <= KBucketEntry.MAX_FAILURES; i++)
			bucket.onTimeout(first.getId());

		// Add a replacement
		KBucketEntry replacement = addReplacement();
		// Promote verified replacement
		bucket.promoteVerifiedReplacement();
		// The replacement should now be in entries
		assertTrue(bucket.entries().contains(replacement));
		assertTrue(bucket.contains(replacement.getId(), false));
	}

	@Test
	void testPromotesReplacementOnTimeout() {
		// Fill bucket to capacity
		int max = KBucket.MAX_ENTRIES;
		List<KBucketEntry> entries = addEntries(max);

		// Add a replacement
		KBucketEntry repl = addReplacement();
		// now the replacement should not be in replacements
		assertFalse(bucket.entries().contains(repl));
		assertFalse(bucket.contains(repl.getId(), false));

		// Timeout the first entry until it is bad
		KBucketEntry first = entries.get(0);
		for (int i = 0; i <= KBucketEntry.MAX_FAILURES; i++)
			// if the timeout limits exceeded, it will promote verified replacement
			bucket.onTimeout(first.getId());

		// The replacement should now be in entries
		assertTrue(bucket.entries().contains(repl));
		assertTrue(bucket.contains(repl.getId(), false));
	}

	@Test
	void testPromoteWhenBucketNotFull() {
		// Only add a replacement, bucket is empty
		KBucketEntry repl = addReplacement();
		// Promote replacement
		bucket.promoteVerifiedReplacement();
		// Should be in entries, not replacements
		assertTrue(bucket.entries().contains(repl));
		assertTrue(bucket.contains(repl.getId(), false));
		assertFalse(bucket.replacements().contains(repl));
	}

	@Test
	void testPutEntryMerge() {
		KBucketEntry entry = newEntry(bucket.prefix().createRandomId());
		entry.setReachable(true);
		entry.setLastSeen(System.currentTimeMillis() - 30_000);
		bucket.put(entry);

		// update the entry with more recent seen
		KBucketEntry recent = new KBucketEntry(entry.getId(), entry.getAddress());
		long lastSeen = System.currentTimeMillis();
		recent.setReachable(true);
		recent.setLastSeen(lastSeen);
		bucket.put(recent);

		KBucketEntry updated = bucket.get(entry.getId(), false);
		assertNotNull(updated);
		assertEquals(lastSeen, entry.lastSeen());
	}

	@Test
	void testOnRespondedUpdatesEntry() {
		KBucketEntry entry = newEntry(bucket.prefix().createRandomId());
		entry.setReachable(true);
		bucket.put(entry);
		// Simulate a timeout to increment failures and set rtt
		bucket.onTimeout(entry.getId());
		KBucketEntry before = bucket.get(entry.getId(), true);
		assertTrue(before.failedRequests() > 0);
		int oldRtt = before.getRTT();
		assertEquals(RpcServer.RPC_CALL_TIMEOUT_MAX, oldRtt);
		// Responded should reset failures and update rtt
		bucket.onResponded(entry.getId(), 123);
		KBucketEntry after = bucket.get(entry.getId(), true);
		assertEquals(0, after.failedRequests());
		assertEquals(123, after.getRTT());
	}

	@Test
	void testStressRandomOperations() {
		final int iterations = 1000;
		final Random random = new Random(12345L); // deterministic seed for reproducibility

		for (int i = 0; i < iterations; i++) {
			int op = random.nextInt(5);
			switch (op) {
				case 0: // put entry
					Id idPut = bucket.prefix().createRandomId();
					KBucketEntry entryPut = newEntry(idPut);
					entryPut.setReachable(random.nextBoolean());
					bucket.put(entryPut);
					break;
				case 1: // put replacement
					Id idRepl = bucket.prefix().createRandomId();
					KBucketEntry repl = newEntry(idRepl);
					repl.setReachable(random.nextBoolean());
					bucket.putAsReplacement(repl);
					break;
				case 2: // onTimeout on random entry
					if (!bucket.entries().isEmpty()) {
						KBucketEntry entryTimeout = bucket.entries().get(random.nextInt(bucket.entries().size()));
						bucket.onTimeout(entryTimeout.getId());
					}
					break;
				case 3: // onResponded on random entry
					if (!bucket.entries().isEmpty()) {
						KBucketEntry entryResp = bucket.entries().get(random.nextInt(bucket.entries().size()));
						bucket.onResponded(entryResp.getId(), random.nextInt(500));
					}
					break;
				case 4: // promote verified replacement
					bucket.promoteVerifiedReplacement();
					break;
			}

			// Check invariants after each operation
			assertTrue(bucket.entries().size() <= KBucket.MAX_ENTRIES, "Entries size exceeded MAX_ENTRIES");
			assertTrue(bucket.replacementSize() <= KBucket.MAX_ENTRIES, "Replacements size exceeded MAX_ENTRIES");

			// Check no duplicate IDs across entries and replacements
			Set<Id> seenIds = new HashSet<>();
			for (KBucketEntry e : bucket.entries()) {
				assertTrue(seenIds.add(e.getId()), "Duplicate ID found in entries");
				assertTrue(bucket.prefix().isPrefixOf(e.getId()), "Entry does not match bucket prefix");
			}
			for (KBucketEntry e : bucket.replacements()) {
				assertTrue(seenIds.add(e.getId()), "Duplicate ID found in replacements");
				assertTrue(bucket.prefix().isPrefixOf(e.getId()), "Replacement entry does not match bucket prefix");
			}

			// Check the entries order is expected
			List<KBucketEntry> entries = new ArrayList<>(bucket.entries());
			entries.sort(KBucketEntry::ageOrder);
			assertEquals(entries, bucket.entries());
		}
	}
}