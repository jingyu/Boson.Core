/*
 * Copyright (c) 2022 - 2023 trinity-tech.io
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.json.Json;
import io.bosonnetwork.kademlia.impl.KadConstants;
import io.bosonnetwork.kademlia.security.SourceKey;
import io.bosonnetwork.utils.AddressUtils;

/**
 * Represents a lock-free, non-thread-safe routing table used in the Kademlia Distributed Hash Table (DHT) implementation.
 * <p>
 * This routing table maintains a list of {@link KBucket} instances, each responsible for managing a subset of node entries
 * based on their XOR distance from the local node's ID. It supports efficient lookup, insertion, and maintenance of node entries,
 * adhering to Kademlia's bucket splitting and replacement policies.
 * <p>
 * Designed for use within single-threaded environments (e.g., Vert.x verticles), this implementation avoids synchronization overhead.
 * <p>
 * That thread confinement is also why persistence stops at the encoding here: {@link #save()} returns bytes and
 * {@link #load(byte[])} takes them, and neither knows where those bytes live. Both walk the buckets, so both belong
 * on the owning thread, while the file I/O they used to do must not run there - keeping the two separate lets the
 * caller put each half where it belongs instead of choosing between a blocked event loop and a data race.
 */
public class RoutingTable {
	private final Id localId;
	private final int k;
	private final int replacements;
	/**
	 * How many entries one source unit may hold in a single bucket - {@code max(1, min(2, k / 8))}.
	 * <p>
	 * Two is a ceiling rather than a constant. The allowance exists for the legitimate pair - two nodes in
	 * one household /64, two bootstrap servers on one host - and there is no reason for it to grow with the
	 * bucket, since a larger k already makes two a smaller share. It cannot be flat either: at the smallest
	 * configurable k, two would be half a bucket. Below k=16 a co-located pair is representable once per
	 * bucket and reaches {@link KadConstants#MAX_ROUTING_TABLE_ENTRIES_PER_SOURCE} across distinct buckets
	 * instead, which random ids give it as soon as the table splits.
	 * </p>
	 */
	final int maxBucketEntriesPerSource;
	private final List<KBucket> buckets;

	/**
	 * How many entries each source unit has been given, counted forward from the last rebuild.
	 * <p>
	 * This exists to keep the table-wide budget off the packet-receive path. The count it guards is
	 * otherwise a walk of every bucket, and that walk runs for every id the table does not already hold -
	 * which a remote sender triggers with one unsolicited request. Measured before this was added: 11us on a
	 * default node, 35us on a super node, 94us on a large one, against single-digit microseconds for the
	 * decrypt that packet already paid. The early exit does not help, because it only fires for a source
	 * that is already over-represented.
	 * </p>
	 * <p>
	 * <b>Incremented, never decremented</b>, and that asymmetry is what makes it safe. Every stored entry
	 * counts here, so the true count can only be <em>lower</em> than what this holds - removals are missed
	 * until the rebuild. Therefore {@code counted < limit} proves the source is within budget and the walk
	 * can be skipped, which is the common case; only {@code counted >= limit} needs the walk, and that is
	 * exactly the case where its early exit fires. A refusal is therefore never decided on a stale number.
	 * </p>
	 * <p>
	 * The other direction would be unsound: a missed increment would under-count and let a source past its
	 * budget. That is why increments live on the store paths, which are three places in this class, rather
	 * than decrements living on the six paths an entry can leave a bucket by.
	 * </p>
	 */
	private final Map<InetAddress, Integer> sourceCounts;

	protected static final Logger log = LoggerFactory.getLogger(RoutingTable.class);

	/**
	 * Creates a routing table whose buckets use the given capacities.
	 *
	 * @param localId      the local node id.
	 * @param k            the Kademlia bucket size, at least 1.
	 * @param replacements the per-bucket replacement cache size, at least 1.
	 */
	public RoutingTable(Id localId, int k, int replacements) {
		this.localId = localId;
		this.k = k;
		this.replacements = replacements;
		this.maxBucketEntriesPerSource = Math.max(1, Math.min(2, k / 8));
		this.sourceCounts = new HashMap<>();
		this.buckets = new ArrayList<>();
		buckets.add(newBucket(Prefix.all(), x -> true));
	}

	/**
	 * Creates a bucket carrying this table's configured capacities. All buckets in a table share them,
	 * so every construction site must go through here rather than calling the KBucket constructor.
	 *
	 * @param prefix the prefix the bucket covers.
	 * @param isHome predicate deciding whether the prefix is the local node's home bucket.
	 * @return the new bucket.
	 */
	private KBucket newBucket(Prefix prefix, Predicate<Prefix> isHome) {
		return new KBucket(prefix, k, replacements, isHome);
	}

	public int size() {
		return buckets.size();
	}

	/**
	 * Returns the Kademlia bucket size (k) this table was created with.
	 *
	 * @return the bucket size.
	 */
	public int getK() {
		return k;
	}

	private boolean isHomeBucket(Prefix p) {
		return p.isPrefixOf(localId);
	}

	protected Id getLocalId() {
		return localId;
	}

	public boolean isEmpty() {
		return buckets.isEmpty();
	}

	public KBucket getBucket(int index) {
		return buckets.get(index);
	}

	public KBucketEntry getEntry(Id id, boolean includeReplacement) {
		return bucketOf(id).get(id, includeReplacement);
	}

	public KBucketEntry getEntry(Id id) {
		return bucketOf(id).get(id, true);
	}

	public boolean contains(Id id, boolean includeReplacement) {
		return bucketOf(id).contains(id, includeReplacement);
	}

	public boolean contains(Id id) {
		return bucketOf(id).contains(id, true);
	}

	public List<KBucket> buckets() {
		return Collections.unmodifiableList(buckets);
	}

	public Stream<KBucket> stream() {
		return buckets.stream();
	}

	public KBucket bucketOf(Id id) {
		return buckets.get(indexOf(buckets, id));
	}

	/**
	 * Finds the index of the bucket that corresponds to the given node ID.
	 * Uses a binary search on the sorted list of buckets based on their prefix.
	 *
	 * @param bucketsRef the list of buckets to search
	 * @param id the node ID to locate
	 * @return the index of the bucket containing or closest to the ID
	 */
	protected static int indexOf(List<KBucket> bucketsRef, Id id) {
		int low = 0;
		int mid = 0;
		int high = bucketsRef.size() - 1;
		int cmp = 0;

		// Binary search for the bucket whose prefix matches or is closest to the id
		while (low <= high) {
			mid = (low + high) >>> 1;
			KBucket bucket = bucketsRef.get(mid);
			cmp = id.compareTo(bucket.prefix());
			if (cmp > 0)
				low = mid + 1;
			else if (cmp < 0)
				high = mid - 1;
			else
				return mid; // exact match found
		}

		// When no exact match, return closest bucket index
		return cmp < 0 ? mid - 1 : mid;
	}

	/**
	 * Returns the total number of entries stored across all buckets.
	 *
	 * @return the total number of node entries in the routing table
	 */
	public int getNumberOfEntries() {
		return buckets.stream().mapToInt(KBucket::size).sum();
	}

	/**
	 * Returns the total number of replacement entries stored across all buckets.
	 *
	 * @return the total number of replacement node entries
	 */
	public int getNumberOfReplacements() {
		return buckets.stream().mapToInt(KBucket::replacementSize).sum();
	}

	public KBucketEntry getRandomEntry() {
		int offset = Random.random().nextInt(buckets.size());
		return buckets.get(offset).getAny();
	}

	public KClosestNodes getClosestNodes(Id target, int expected) {
		return new KClosestNodes(this, target, expected);
	}

	/*/
	// TODO: Remove
	public List<KBucketEntry> getRandomEntries(int expect) {
		final int total = getNumberOfEntries();
		if (total == 0)
			return Collections.emptyList();

		if (total <= expect) {
			// Avoid unnecessary stream for small cases
			List<KBucketEntry> result = new ArrayList<>(total);
			buckets.forEach(bucket -> result.addAll(bucket.entries()));
			return result;
		}

		return Random.random().ints(0, total)
				.distinct()
				.limit(expect)
				.sorted()
				.mapToObj(i -> {
					int flatIndex = 0;
					for (KBucket bucket : buckets) {
						int size = bucket.size();
						if (i < flatIndex + size)
							return bucket.get(i - flatIndex);

						flatIndex += size;
					}

					// Should not happen with valid indices
					return null;
				}).filter(Objects::nonNull)
				.collect(Collectors.toList());
	}
	*/

	/**
	 * Inserts or updates a node entry in the routing table.
	 * The routing table may split buckets as necessary to accommodate the new entry.
	 * <p>
	 * The return value reports whether the table took the entry, so that a caller with follow-up work
	 * to do on a stored entry can tell there is something to follow up on. It says nothing about where
	 * the entry landed: an entry filed as a replacement is accepted, because a replacement is a slot a
	 * later verification can promote into the bucket proper.
	 * </p>
	 *
	 * @param entry the node entry to add or update
	 * @return true if the table holds the entry after this call, false if it was dropped in favour of
	 *         an entry that already claims the same id or address.
	 */
	public boolean put(KBucketEntry entry) {
		log.trace("Putting entry: {}...", entry);

		Id nodeId = entry.getId();

		// An entry we already hold makes no new claim on the budget: KBucket.put either merges the
		// observation into it or refuses it as a collision, and neither adds a slot.
		InetAddress source = contains(nodeId, true) ? null : countableSource(entry);

		if (source != null && tableBudgetSpent(source)) {
			log.debug("Source {} already holds {} entries, dropping {}",
					source.getHostAddress(), KadConstants.MAX_ROUTING_TABLE_ENTRIES_PER_SOURCE, entry);
			return false;
		}

		KBucket bucket = bucketOf(nodeId);

		// Split buckets if required before inserting the new entry
		while (needsSplit(bucket, entry)) {
			log.trace("Splitting bucket {} before put {}...", bucket.prefix(), entry.getId());
			split(bucket);
			bucket = bucketOf(nodeId);
		}

		// Checked after the split rather than before it, unlike the table-wide budget above: a split only
		// ever moves entries out of the bucket being split, so the count that matters is the one in the
		// bucket the entry actually lands in. The table-wide budget goes first precisely so that a sender
		// already over it cannot buy the split that would have made room for it.
		if (source != null && countEntries(bucket, source, maxBucketEntriesPerSource) >= maxBucketEntriesPerSource) {
			log.debug("Source {} already holds {} entries in bucket {}, dropping {}",
					source.getHostAddress(), maxBucketEntriesPerSource, bucket.prefix(), entry);
			return false;
		}

		log.trace("Putting new entry {} into bucket {}", entry.getId(), bucket.prefix());
		boolean stored = bucket.put(entry);
		if (stored && source != null)
			sourceCounts.merge(source, 1, Integer::sum);

		return stored;
	}

	/**
	 * Whether one source unit has spent its table-wide budget.
	 * <p>
	 * The count is consulted first and the table walked only if it says the budget looks spent. That is
	 * sound in the one direction that matters: {@link #sourceCounts} is never decremented, so it can only
	 * exceed the truth, and a value below the limit therefore proves the source is within it. A source that
	 * looks spent may not be - entries it held may have been removed since the last rebuild - so that case
	 * is confirmed by the walk rather than acted on, which keeps every refusal exact.
	 * </p>
	 *
	 * @param source the source unit to test.
	 * @return true if the source already holds its full table-wide allowance.
	 */
	private boolean tableBudgetSpent(InetAddress source) {
		int limit = KadConstants.MAX_ROUTING_TABLE_ENTRIES_PER_SOURCE;

		Integer counted = sourceCounts.get(source);
		if (counted == null || counted < limit)
			return false;

		return countEntries(source, limit) >= limit;
	}

	/**
	 * Recounts every source unit from the buckets themselves, discarding the forward count.
	 * <p>
	 * This is what bounds the drift that not decrementing produces. Without it a source whose entries have
	 * all timed out keeps looking spent, and every later contact from it pays the walk that the count exists
	 * to avoid - so the structure degrades into the state it was built to replace rather than into anything
	 * unsafe.
	 * </p>
	 */
	private void rebuildSourceCounts() {
		sourceCounts.clear();
		for (KBucket bucket : buckets) {
			for (KBucketEntry entry : bucket.entries())
				countSource(entry);

			for (KBucketEntry entry : bucket.replacements())
				countSource(entry);
		}
	}

	private void countSource(KBucketEntry entry) {
		InetAddress source = countableSource(entry);
		if (source != null)
			sourceCounts.merge(source, 1, Integer::sum);
	}

	/**
	 * How many entries one source unit is currently counted for.
	 * <p>
	 * Package-private for the tests: the forward count and its rebuild are the parts of this that can go
	 * wrong quietly, and a wrong count is invisible from the outside until it either refuses an honest
	 * contact or admits one too many.
	 * </p>
	 *
	 * @param source the source unit.
	 * @return the counted entries, which may exceed the true number until the next rebuild.
	 */
	int countedFor(InetAddress source) {
		return sourceCounts.getOrDefault(source, 0);
	}

	/**
	 * The unit an entry is counted against, or null if it is not counted at all.
	 * <p>
	 * The diversity budget counts a source unit because an address is a resource somebody had to acquire,
	 * and that is true only of a globally routable address. A loopback, an RFC1918 or a link-local address
	 * costs nothing and there is an unlimited supply, so counting one would measure nothing.
	 * </p>
	 * <p>
	 * This is a scope, not a hole. In production nothing else is ever in the table - the DHT refuses the
	 * routing-table update for any source that is not global unicast, and that is the only path in - so here
	 * "count global unicast" and "count everything" are the same rule. What the exemption keeps working is
	 * development, where a whole test network runs on one machine behind private addresses, and that is
	 * exactly the set the developer-mode address filter admits.
	 * </p>
	 *
	 * @param entry the entry about to be inserted.
	 * @return the source unit to count it under, or null if the entry's address is not a countable resource.
	 */
	private static @Nullable InetAddress countableSource(KBucketEntry entry) {
		InetAddress address = entry.getIpAddress();
		return AddressUtils.isGlobalUnicast(address) ? SourceKey.of(address) : null;
	}

	/**
	 * Counts the entries in the table belonging to one source unit, stopping at the limit.
	 * <p>
	 * A walk rather than a maintained counter, deliberately. The counter would have to stay in step with
	 * every path an entry can leave a bucket by - removal, bad-entry replacement, replacement eviction,
	 * promotion, merge, load - and a counter that drifts either locks out honest contacts or stops counting
	 * an attacker. This runs only for an id the table does not already hold, on a path already rate-bounded
	 * upstream, and costs a walk against a decrypt that has already happened.
	 * </p>
	 *
	 * @param source the source unit to count.
	 * @param limit  stop once this many have been found; the caller only needs to know it reached it.
	 * @return the number found, capped at {@code limit}.
	 */
	@SuppressWarnings("SameParameterValue")
	private int countEntries(InetAddress source, int limit) {
		int count = 0;
		for (KBucket bucket : buckets) {
			count += countEntries(bucket, source, limit - count);
			if (count >= limit)
				break;
		}

		return count;
	}

	/**
	 * Counts the entries in one bucket belonging to one source unit, replacements included, stopping at the
	 * limit.
	 * <p>
	 * Replacements count because that is where a flood parks: an entry arriving unsolicited is not reachable
	 * yet, so it is filed as a replacement, and a full replacement list evicts its worst member to make room.
	 * Leaving them uncounted would let a sender displace honest replacements for free.
	 * </p>
	 *
	 * @param bucket the bucket to scan.
	 * @param source the source unit to count.
	 * @param limit  stop once this many have been found.
	 * @return the number found, capped at {@code limit}.
	 */
	private static int countEntries(KBucket bucket, InetAddress source, int limit) {
		if (limit <= 0)
			return 0;

		int count = 0;
		for (KBucketEntry entry : bucket.entries()) {
			if (belongsTo(entry, source) && ++count >= limit)
				return count;
		}

		for (KBucketEntry entry : bucket.replacements()) {
			if (belongsTo(entry, source) && ++count >= limit)
				return count;
		}

		return count;
	}

	/**
	 * The diversity budget as one test against an already chosen bucket, for the warm-start path.
	 * <p>
	 * {@link #put} takes the two counts on either side of the split loop, so that a sender already over its
	 * table-wide budget cannot provoke a split before being refused. Nothing provokes anything here - this
	 * is our own file being read back - so both counts are taken together against the bucket the entry would
	 * land in.
	 * </p>
	 *
	 * @param entry  the entry about to be placed.
	 * @param bucket the bucket it would be placed in.
	 * @return true if the entry is within both limits, or is not counted at all.
	 */
	private boolean withinDiversityBudget(KBucketEntry entry, KBucket bucket) {
		InetAddress source = contains(entry.getId(), true) ? null : countableSource(entry);
		if (source == null)
			return true;

		return countEntries(bucket, source, maxBucketEntriesPerSource) < maxBucketEntriesPerSource &&
				!tableBudgetSpent(source);
	}

	private static boolean belongsTo(KBucketEntry entry, InetAddress source) {
		InetAddress address = entry.getIpAddress();
		return source.equals(SourceKey.of(address));
	}

	/**
	 * Removes the entry with the specified node ID from the routing table.
	 *
	 * @param id the ID of the node to remove
	 * @return true if the entry was removed, false otherwise
	 */
	public boolean remove(Id id) {
		return  bucketOf(id).remove(id);
	}

	/**
	 * Removes the entry with the specified node ID if it is considered bad or if forced.
	 *
	 * @param id the ID of the node to remove
	 * @param force if true, removal is forced regardless of entry state
	 * @return the removed entry if any, null otherwise
	 */
	public KBucketEntry removeIfBad(Id id, boolean force) {
		KBucket bucket = bucketOf(id);
		return bucket.removeIfBad(id, force);
	}

	/**
	 * Withdraws the table's confidence in an entry without removing it.
	 * <p>
	 * A demoted entry keeps its place - so whatever held that place cannot be displaced by whoever caused
	 * the demotion - but stops being offered to lookups and to other nodes, and becomes retirable after two
	 * failures rather than six. Reachability is the table's own judgement about a contact, so it is revoked
	 * here rather than by handing the entry out to be mutated.
	 * </p>
	 * <p>
	 * The return value is what makes this usable as a one-shot: it reports whether this call is the one that
	 * changed the entry's mind, so a caller can act on the transition instead of on every repeat.
	 * </p>
	 *
	 * @param id the ID of the node to demote.
	 * @return true if the entry was reachable and is not any more, false if there is no such entry or it had
	 *         already been demoted.
	 */
	public boolean markUnreachable(Id id) {
		KBucketEntry entry = getEntry(id, true);
		if (entry == null || !entry.isReachable())
			return false;

		entry.setReachable(false);
		return true;
	}

	/**
	 * Notifies the routing table that a request has been sent to the node with the given ID.
	 * This may be used to update internal timestamps or state.
	 *
	 * @param id the ID of the node to which the request was sent
	 */
	public void onRequestSent(Id id) {
		KBucket bucket = bucketOf(id);
		bucket.onRequestSent(id);
	}

	/**
	 * Notifies the routing table that a response has been received from the node with the given ID,
	 * along with the round-trip time (RTT) in milliseconds.
	 *
	 * @param id the ID of the node that responded
	 * @param rtt the measured round-trip time in milliseconds
	 */
	public void onResponded(Id id, int rtt) {
		KBucket bucket = bucketOf(id);
		bucket.onResponded(id, rtt);
	}

	/**
	 * Notifies the routing table that a request to the node with the given ID has timed out.
	 *
	 * @param id the ID of the node that timed out
	 * @return true if the timeout resulted in any state changes, false otherwise
	 */
	public boolean onTimeout(Id id) {
		KBucket bucket = bucketOf(id);
		return bucket.onTimeout(id);
	}

	/**
	 * Determines whether the given bucket needs to be split to accommodate a new entry.
	 * <p>
	 * A full, splittable bucket is split only when the new (reachable, not-yet-present) entry would fall
	 * into the bucket's <em>high</em> branch. This is a deliberate Boson adaptation: unlike the original
	 * Kademlia paper (section 2.4), which splits only the bucket whose range contains the local node's own ID,
	 * Boson lets density drive splitting and relies on {@link #mergeBuckets()} during maintenance to
	 * coalesce any sibling pair whose combined effective size fits in a single bucket - so unproductive
	 * splits are reclaimed rather than accumulating. The high-branch condition keeps the decision
	 * deterministic and is covered by {@code RoutingTableTests#testNeedsSplitAndSplit}.
	 *
	 * @param bucket the bucket to check
	 * @param newEntry the new entry to insert
	 * @return true if the bucket should be split, false otherwise
	 */
	private boolean needsSplit(KBucket bucket, KBucketEntry newEntry) {
		// Avoid splitting if bucket is not splittable, not full, or entry is unreachable or already exists
		if (!bucket.prefix().isSplittable() || !bucket.isFull() ||
				!newEntry.isReachable() || bucket.contains(newEntry.getId(), false) ||
				bucket.needsReplacement())
			return false;

		// Existing entries need not be pre-checked for branch distribution: split() redistributes them by
		// prefix, and any resulting under-filled sibling pair is merged back by mergeBuckets() (see above).

		// Determine if the new entry belongs to the higher branch after split
		Prefix highBranch = bucket.prefix().splitBranch(true);
		return highBranch.isPrefixOf(newEntry.getId());
	}

	/**
	 * Modifies the routing table by removing and adding specified buckets atomically.
	 *
	 * @param toRemove the collection of buckets to remove
	 * @param toAdd the collection of buckets to add
	 */
	private void modify(Collection<KBucket> toRemove, Collection<KBucket> toAdd) {
		if (toRemove != null && !toRemove.isEmpty())
			buckets.removeAll(toRemove);
		if (toAdd != null && !toAdd.isEmpty())
			buckets.addAll(toAdd);
		buckets.sort(null);
	}

	/**
	 * Splits the specified bucket into two new buckets based on its prefix.
	 * Entries and replacements are redistributed accordingly.
	 *
	 * @param bucket the bucket to split
	 */
	private void split(KBucket bucket) {
		KBucket a = newBucket(bucket.prefix().splitBranch(false), this::isHomeBucket);
		KBucket b = newBucket(bucket.prefix().splitBranch(true), this::isHomeBucket);

		// Distribute entries into the appropriate new buckets
		for (KBucketEntry entry : bucket.entries()) {
			if (a.prefix().isPrefixOf(entry.getId()))
				a.put(entry);
			else
				b.put(entry);
		}

		// Distribute replacement entries similarly
		for (KBucketEntry e : bucket.replacements()) {
			if (a.prefix().isPrefixOf(e.getId()))
				a.put(e);
			else
				b.put(e);
		}

		modify(List.of(bucket), List.of(a, b));
	}

	/**
	 * Attempts to merge adjacent sibling buckets when their combined size does not exceed the maximum allowed.
	 * This helps reduce fragmentation and maintain efficient bucket structure.
	 */
	private void mergeBuckets() {
		log.debug("Trying to merge buckets({})... ", buckets.size());

		// Scan adjacent pairs (i-1, i); on a merge, step back one so the new bucket is re-checked
		// against its (now-adjacent) predecessor, otherwise advance.
		int i = 1;
		while (i < buckets.size()) {
			KBucket b1 = buckets.get(i - 1);
			KBucket b2 = buckets.get(i);

			// Only merge sibling buckets (same parent prefix) whose combined effective size fits one bucket.
			// Effective size counts entries that cannot be dropped without a replacement, plus replacements
			// eligible for the nodes list.
			boolean merged = false;
			if (b1.prefix().isSiblingOf(b2.prefix())) {
				int effectiveSize1 = (int) (b1.stream().filter(e -> !e.removableWithoutReplacement()).count()
						+ b1.replacementStream().filter(KBucketEntry::eligibleForNodesList).count());
				int effectiveSize2 = (int) (b2.stream().filter(e -> !e.removableWithoutReplacement()).count()
						+ b2.replacementStream().filter(KBucketEntry::eligibleForNodesList).count());

				if (effectiveSize1 + effectiveSize2 <= k) {
					log.debug("Merging buckets {} and {}...", b1.prefix(), b2.prefix());
					KBucket newBucket = newBucket(b1.prefix().getParent(), this::isHomeBucket);

					// Move all entries and replacements into the new bucket
					b1.stream().forEach(newBucket::put);
					b2.stream().forEach(newBucket::put);
					b1.replacementStream().forEach(newBucket::put);
					b2.replacementStream().forEach(newBucket::put);

					modify(List.of(b1, b2), List.of(newBucket));
					merged = true;
				}
			}

			i = merged ? Math.max(1, i - 1) : i + 1;
		}

		log.debug("Finished merge buckets({})... ", buckets.size());
	}

	/**
	 * Applies the given consumer function to each bucket in the routing table.
	 *
	 * @param consumer the function to apply to each bucket
	 */
	public void forEachBucket(Consumer<KBucket> consumer) {
		for (KBucket bucket : buckets)
			consumer.accept(bucket);
	}

	/**
	 * Performs maintenance operations on the routing table.
	 * This includes merging buckets, cleaning up entries, refreshing buckets,
	 * and promoting verified replacements as needed.
	 *
	 * @param bootstrapIds         a collection of bootstrap node IDs used during cleanup
	 * @param bucketRefreshHandler a consumer invoked once for every bucket that wants a refresh, in
	 *                             table order. It reports demand and does not oblige the caller to meet
	 *                             it: on a large table this fires for many buckets at once, so a caller
	 *                             that turns each one into a task straight away has an unbounded
	 *                             fan-out. Collect here and decide how many to serve afterwards.
	 */
	public void maintenance(Collection<Id> bootstrapIds, Consumer<KBucket> bucketRefreshHandler) {
		// Merges incrementally to avoid event loop blocking;
		// full coalescence occurs over multiple maintenance cycles.
		mergeBuckets();

		for (KBucket bucket : buckets) {
			boolean isHome = bucket.isHomeBucket();
			bucket.cleanup(localId,  bootstrapIds, this::put);

			boolean refreshNeeded = bucket.needsToBeRefreshed();
			boolean replacementNeeded = bucket.needsReplacementPing() || (isHome && bucket.findPingableReplacement() != null);
			if (refreshNeeded || replacementNeeded) {
				log.debug("Refreshing bucket {}...", bucket.prefix());
				bucketRefreshHandler.accept(bucket);
			}

			// Promotes one per bucket per maintenance cycle to avoid blocking; full recovery over iterations.
			bucket.promoteVerifiedReplacement();
		}

		// Last, and it has to be: the loop above removes entries through cleanup() and adds them back
		// through put(), and mergeBuckets() can drop a removable entry as it coalesces. Recounting here
		// takes the state all of that settled on, and costs one more pass over buckets this method has
		// already walked twice.
		rebuildSourceCounts();
	}

	/**
	 * Restores the routing table's state from a previously {@link #save() saved} encoding.
	 * <p>
	 * Entries are merged into the existing state rather than replacing it, and entries whose stored id
	 * does not match this table's local id, or whose snapshot is older than a day, are inserted through
	 * the normal path instead of being restored to their recorded buckets.
	 * </p>
	 * <p>
	 * Decoding only. Where the bytes came from is the caller's business, and so is reading them: this
	 * walks the buckets, so it belongs on whatever thread owns this table, and blocking that thread on a
	 * file read is exactly what keeping the two apart avoids.
	 * </p>
	 * <p>
	 * Damaged input is survivable by design. A truncated or corrupt encoding is logged and abandoned
	 * partway, keeping whatever was already restored - a cache that turns out to be unusable should cost
	 * a node its warm start, not its startup. The count that comes back is what makes that survivable
	 * case answerable: a caller cannot tell an empty cache from a broken one by looking at the table
	 * afterwards, because a table that restored nothing looks exactly like the one it started with.
	 * </p>
	 *
	 * @param data the saved routing table, in the CBOR format {@link #save()} produces; null or empty
	 *             restores nothing.
	 * @return how many entries and replacements were read out of the encoding - what this call
	 *         contributed, not what the table now holds. Zero means the cache was empty, unreadable, or
	 *         damaged before its first entry, and the three are deliberately not distinguished: to every
	 *         caller so far they mean the same thing, that there is nothing here to warm-start from. Use
	 *         {@link #getNumberOfEntries()} for the state of the table itself.
	 */
	public int load(byte @Nullable [] data) {
		if (data == null || data.length == 0)
			return 0;

		final long MAX_AGE = 24 * 60 * 60 * 1000;
		int totalEntries = 0;
		int totalReplacements = 0;

		try {
			CBORMapper mapper = new CBORMapper();
			JsonNode root = mapper.readTree(data);
			if (root.isEmpty())
				return 0;

			// A corrupt or partial file may be missing required fields; guard against
			// NullPointerException (which would escape the IOException handler below) by
			// validating their presence explicitly.
			JsonNode idNode = root.get("nodeId");
			JsonNode timestampNode = root.get("timestamp");
			if (idNode == null || timestampNode == null)
				throw new IOException("Missing 'nodeId' or 'timestamp' field");

			Id nodeId;
			try {
				nodeId = Id.of(idNode.binaryValue());
			} catch (IllegalArgumentException e) {
				throw new IOException("Invalid nodeId", e);
			}

			boolean idMatched = nodeId.equals(localId);
			long timestamp = timestampNode.asLong();
			long age = System.currentTimeMillis() - timestamp;
			boolean staled = age > MAX_AGE;

			JsonNode nodes = root.get("entries");
			if (nodes == null || !nodes.isArray())
				throw new IOException("Missing or invalid node entries");

			// Load and insert entries into the routing table
			for (JsonNode node : nodes) {
				Map<String, Object> map = mapper.convertValue(node, Json.mapType());
				KBucketEntry entry = KBucketEntry.fromMap(map);
				if (entry != null) {
					boolean stored;
					if (idMatched && !staled) {
						KBucket bucket = bucketOf(entry.getId());
						while (bucket.isFull()) {
							split(bucket);
							bucket = bucketOf(entry.getId());
						}

						// The warm path reaches the bucket directly, so it has to carry the diversity budget
						// itself - a table saved before this limit existed, or under a wider one, would
						// otherwise walk straight back in.
						stored = withinDiversityBudget(entry, bucket) && bucket.put(entry);
						if (stored)
							countSource(entry);
					} else {
						// TODO: need to improve
						stored = put(entry);
					}

					// Counted when stored rather than when read: what the caller does with this number is
					// decide whether it has a table to work with.
					if (stored)
						totalEntries++;
				} else {
					log.warn("Invalid entry: {}", node);
				}
			}

			nodes = root.get("replacements");
			if (nodes != null) {
				if (!nodes.isArray())
					throw new IOException("Invalid node entries");

				for (JsonNode node : nodes) {
					Map<String, Object> map = mapper.convertValue(node, Json.mapType());
					KBucketEntry entry = KBucketEntry.fromMap(map);
					if (entry != null) {
						KBucket bucket = bucketOf(entry.getId());
						if (bucket.find(entry.getId(), entry.getAddress()) == null
								&& withinDiversityBudget(entry, bucket)
								&& bucket.putAsReplacement(entry)) {
							countSource(entry);
							totalReplacements++;
						}
					} else {
						log.warn("Invalid replacement entry: {}", node);
					}
				}
			}

			log.info("Loaded {} entries {} replacements from the saved routing table, it was {} old.",
					totalEntries, totalReplacements, Duration.ofMillis(System.currentTimeMillis() - timestamp));
		} catch (IOException e) {
			log.error("Can not load the routing table.", e);
		}

		return totalEntries + totalReplacements;
	}

	/**
	 * Encodes the current state of the routing table as CBOR, for a later {@link #load(byte[])}.
	 * <p>
	 * Returns the bytes rather than storing them anywhere. Persisting them is the caller's business:
	 * this class is the routing table, not its storage, and the one caller that does persist has to
	 * treat the two differently anyway - this walks the buckets and their entries, so it must run on the
	 * thread that owns the table, while writing a file must not run there at all.
	 * </p>
	 * <p>
	 * Entries needing replacement are left out, so what comes back is the contacts worth trying again
	 * rather than a faithful image of the table.
	 * </p>
	 *
	 * @return the encoded routing table, or null if it holds no entries worth saving.
	 * @throws UncheckedIOException if the encoding fails, which means a defect in the encoder or in an
	 *                              entry rather than an I/O problem - nothing is being written here.
	 */
	public byte @Nullable [] save() {
		if (getNumberOfEntries() == 0) {
			log.trace("Skip to save the empty routing table.");
			return null;
		}

		long now = System.currentTimeMillis();
		ByteArrayOutputStream out = new ByteArrayOutputStream(4096);

		try {
			CBORGenerator gen = Json.cborFactory().createGenerator(out);
			gen.writeStartObject();
			gen.writeBinaryField("nodeId", localId.bytesUnsafe());
			gen.writeNumberField("timestamp", now);

			gen.writeFieldName("entries");
			gen.writeStartArray();
			for (KBucket bucket : buckets) {
				for (KBucketEntry entry : bucket.entries()) {
					if (entry.needsReplacement())
						continue;

					gen.writeStartObject();

					Map<String, Object> map = entry.toMap();
					for (Map.Entry<String, Object> kv : map.entrySet()) {
						gen.writeFieldName(kv.getKey());
						gen.writeObject(kv.getValue());
					}

					gen.writeEndObject();
				}
			}
			gen.writeEndArray();

			gen.writeFieldName("replacements");
			gen.writeStartArray();
			for (KBucket bucket : buckets) {
				for (KBucketEntry entry : bucket.replacements()) {
					gen.writeStartObject();

					Map<String, Object> map = entry.toMap();
					for (Map.Entry<String, Object> kv : map.entrySet()) {
						gen.writeFieldName(kv.getKey());
						gen.writeObject(kv.getValue());
					}

					gen.writeEndObject();
				}
			}
			gen.writeEndArray();

			gen.writeEndObject();
			gen.close();
		} catch (IOException e) {
			// Encoding to memory: an IOException here is not an I/O failure, it is a bug in the encoder
			// or in an entry's toMap(). Nothing the caller could do about it differs from any other bug.
			throw new UncheckedIOException("Can not encode the routing table", e);
		}

		return out.toByteArray();
	}

	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder(2048);

		repr.append("buckets: ").append(buckets.size())
			.append(" , entries: ").append(getNumberOfEntries())
			.append(" , replacements: ").append(getNumberOfReplacements()).append('\n');

		for (KBucket bucket : buckets) {
			bucket.toString(repr);
			repr.append('\n');
		}

		return repr.toString();
	}

	public void dump(PrintStream out) {
		out.printf("buckets: %d, entries: %d, replacements: %d\n",
				buckets.size(), getNumberOfEntries(), getNumberOfReplacements());

		for (KBucket bucket : buckets) {
			bucket.dump(out);
			out.println();
		}
	}
}
