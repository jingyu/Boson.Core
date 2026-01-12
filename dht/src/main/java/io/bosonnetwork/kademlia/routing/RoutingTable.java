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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.json.Json;

/**
 * Represents a lock-free, non-thread-safe routing table used in the Kademlia Distributed Hash Table (DHT) implementation.
 * <p>
 * This routing table maintains a list of {@link KBucket} instances, each responsible for managing a subset of node entries
 * based on their XOR distance from the local node's ID. It supports efficient lookup, insertion, and maintenance of node entries,
 * adhering to Kademlia's bucket splitting and replacement policies.
 * <p>
 * Designed for use within single-threaded environments (e.g., Vert.x verticles), this implementation avoids synchronization overhead.
 */
public class RoutingTable {
	private final Id localId;
	private final List<KBucket> buckets;

	protected static final Logger log = LoggerFactory.getLogger(RoutingTable.class);

	public RoutingTable(Id localId) {
		this.localId = localId;
		this.buckets = new ArrayList<>();
		buckets.add(new KBucket(Prefix.all(), x -> true));
	}

	public int size() {
		return buckets.size();
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
	 *
	 * @param entry the node entry to add or update
	 */
	public void put(KBucketEntry entry) {
		log.trace("Putting entry: {}...", entry);

		Id nodeId = entry.getId();
		KBucket bucket = bucketOf(nodeId);

		// Split buckets if required before inserting the new entry
		while (needsSplit(bucket, entry)) {
			log.trace("Splitting bucket {} before put {}...", bucket.prefix(), entry.getId());
			split(bucket);
			bucket = bucketOf(nodeId);
		}

		bucket.put(entry);
		log.trace("New entry {} putted into bucket {}", entry.getId(), bucket.prefix());
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
	 * Buckets are split only if they are splittable, full, and the new entry falls into the higher branch.
	 * Also considers reachability and replacement policies.
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

		// TODO: should we check branch of the existing entries?

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
		KBucket a = new KBucket(bucket.prefix().splitBranch(false), this::isHomeBucket);
		KBucket b = new KBucket(bucket.prefix().splitBranch(true), this::isHomeBucket);

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

		// Perform bucket merge operations where possible
		int i = 0;
		while (true) {
			++i;
			if (i < 1)
				continue;

			if (i >= buckets.size())
				break;

			KBucket b1 = buckets.get(i - 1);
			KBucket b2 = buckets.get(i);

			// Only merge if buckets are siblings (i.e., share the same parent prefix)
			if (b1.prefix().isSiblingOf(b2.prefix())) {
				// Calculate effective size including entries that cannot be removed without replacement
				int effectiveSize1 = (int) (b1.stream().filter(e -> !e.removableWithoutReplacement()).count()
						+ b1.replacementStream().filter(KBucketEntry::eligibleForNodesList).count());
				int effectiveSize2 = (int) (b2.stream().filter(e -> !e.removableWithoutReplacement()).count()
						+ b2.replacementStream().filter(KBucketEntry::eligibleForNodesList).count());

				// Merge only if combined effective size fits within bucket capacity
				if (effectiveSize1 + effectiveSize2 <= KBucket.MAX_ENTRIES) {
					log.debug("Merging buckets {} and {}...", b1.prefix(), b2.prefix());
					// Create a new bucket with the parent prefix to hold merged entries
					KBucket newBucket = new KBucket(b1.prefix().getParent(), this::isHomeBucket);

					// Move all entries and replacements into the new bucket
					b1.stream().forEach(newBucket::put);
					b2.stream().forEach(newBucket::put);
					b1.replacementStream().forEach(newBucket::put);
					b2.replacementStream().forEach(newBucket::put);

					modify(List.of(b1, b2), List.of(newBucket));

					i -= 2; // Adjust index to re-check after merge
				}
			}
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
	 * @param bucketRefreshHandler a consumer invoked to handle bucket refresh operations.
	 *                             The handler implementation should initialize a PingRefreshTask
	 *                             with probe replacements option enabled.
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
	}

	/**
	 * Loads the routing table's state from the specified file.
	 * The file is expected to be in CBOR format containing entries and replacements.
	 * Existing routing table state will be updated accordingly.
	 *
	 * @param file the path to the file to load from
	 */
	public void load(Path file) {
		if (Files.notExists(file) || !Files.isRegularFile(file))
			return;

		final long MAX_AGE = 24 * 60 * 60 * 1000;
		int totalEntries = 0;
		int totalReplacements = 0;

		try (InputStream in = Files.newInputStream(file)) {
			CBORMapper mapper = new CBORMapper();
			JsonNode root = mapper.readTree(in);
			if (root.isEmpty())
				return;

			Id nodeId = null;
			try {
				byte[] idBytes = root.get("nodeId").binaryValue();
				nodeId = Id.of(idBytes);
			} catch (IllegalArgumentException e) {
				throw new IOException("Invalid nodeId", e);
			}

			boolean idMatched = nodeId.equals(localId);
			long timestamp = root.get("timestamp").asLong();
			long age = System.currentTimeMillis() - timestamp;
			boolean staled = age > MAX_AGE;

			JsonNode nodes = root.get("entries");
			if (!nodes.isArray())
				throw new IOException("Invalid node entries");

			// Load and insert entries into the routing table
			for (JsonNode node : nodes) {
				Map<String, Object> map = mapper.convertValue(node, Json.mapType());
				KBucketEntry entry = KBucketEntry.fromMap(map);
				if (entry != null) {
					if (idMatched && !staled) {
						KBucket bucket = bucketOf(entry.getId());
						while (bucket.isFull()) {
							split(bucket);
							bucket = bucketOf(entry.getId());
						}
						bucket.put(entry);
					} else {
						// TODO: need to improve
						put(entry);
					}

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
						if (bucket.find(entry.getId(), entry.getAddress()) == null)
							bucket.putAsReplacement(entry);

						totalReplacements++;
					} else {
						log.warn("Invalid replacement entry: {}", node);
					}
				}
			}

			log.info("Loaded {} entries {} replacements from persistent file. it was {} old.",
					totalEntries, totalReplacements, Duration.ofMillis(System.currentTimeMillis() - timestamp));
		} catch (IOException e) {
			log.error("Can not load the routing table.", e);
		}
	}

	/**
	 * Saves the current state of the routing table to the specified file in CBOR format.
	 * The method writes to a temporary file first and then atomically moves it to the target location to ensure data integrity.
	 * If the routing table is empty or the target file is not a regular file, the save operation is skipped.
	 *
	 * @param file the path to the file where the routing table should be saved
	 * @throws IOException if an I/O error occurs during saving
	 */
	public void save(Path file) throws IOException {
		if (this.getNumberOfEntries() == 0) {
			log.trace("Skip to save the empty routing table.");
			return;
		}

		if (Files.exists(file)) {
			if (!Files.isRegularFile(file))
				return;
		} else {
			Files.createDirectories(file.getParent());
		}

		long now = System.currentTimeMillis();
		Path tempFile = Files.createTempFile(file.getParent(), file.getFileName().toString(), "-" + now);
		try (OutputStream out = Files.newOutputStream(tempFile)) {
			CBORGenerator gen = Json.cborFactory().createGenerator(out);
			gen.writeStartObject();
			gen.writeBinaryField("nodeId", localId.bytes());
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
			out.close();
			Files.move(tempFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} finally {
			// Force delete the tempFile if error occurred
			Files.deleteIfExists(tempFile);
		}
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