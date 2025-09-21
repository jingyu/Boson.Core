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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;

/**
 * Represents a collection of the k closest nodes to a target ID in a Kademlia routing table.
 * <p>
 * Nodes are collected from the closest bucket and expanded bidirectionally to neighboring buckets,
 * sorted by XOR distance to the target, and trimmed to the requested capacity. Supports filtering
 * and optional inclusion of replacement entries. Local node is always excluded to prevent self-referential lookups.
 */
public class KClosestNodes {
	private final RoutingTable routingTable;
	private final Id target;
	private final int capacity;
	private final List<KBucketEntry> entries;
	private Predicate<KBucketEntry> filter;
	private boolean includeReplacements;

	/**
	 * Constructs a new KClosestNodes instance.
	 *
	 * @param routingTable the routing table to query
	 * @param target the target node ID for distance calculation
	 * @param capacity the maximum number of entries to return (k)
	 */
	protected KClosestNodes(RoutingTable routingTable, Id target, int capacity) {
		this.routingTable = routingTable;
		this.target = target;
		this.capacity = capacity;
		this.entries = new ArrayList<>(capacity + KBucket.MAX_ENTRIES);
		this.filter = e -> e.eligibleForNodesList() && !e.getId().equals(routingTable.getLocalId());
		this.includeReplacements = false;
	}

	/**
	 * Gets the target node ID.
	 *
	 * @return the target node ID of the search
	 */
	public Id getTarget() {
		return target;
	}

	/**
	 * Returns the current number of collected entries.
	 *
	 * @return the number of entries
	 */
	public int size() {
		return entries.size();
	}

	/**
	 * Checks if the collection has reached or exceeded the requested capacity.
	 *
	 * @return true if size >= capacity; false otherwise
	 */
	public boolean isFull() {
		return entries.size() >= capacity;
	}

	/**
	 * Checks if the collection contains the full requested capacity.
	 *
	 * @return true if size >= capacity; false if fewer entries were found
	 */
	public boolean isComplete() {
		return entries.size() >= capacity;
	}

	/**
	 * Sets a custom filter for entries. The filter must not include the local node.
	 *
	 * @param filter the predicate to filter entries
	 * @return this instance for chaining
	 * @throws NullPointerException if filter is null
	 */
	public KClosestNodes filter(Predicate<KBucketEntry> filter) {
		this.filter = Objects.requireNonNull(filter, "Filter cannot be null")
				.and(e -> !e.getId().equals(routingTable.getLocalId()));
		return this;
	}

	/**
	 * Sets if include the replacements in the populated closest entries.
	 *
	 * @param includeReplacements if true, includes verified replacement entries
	 * @return this instance for chaining
	 */
	public KClosestNodes includeReplacements(Boolean includeReplacements) {
		this.includeReplacements = includeReplacements;
		return this;
	}

	/**
	 * Sets include the replacements in the populated closest entries.
	 *
	 * @return this instance for chaining
	 */
	public KClosestNodes includeReplacements() {
		this.includeReplacements = true;
		return this;
	}

	/**
	 * Populates the collection with the closest entries to the target ID.
	 * <p>
	 * Starts from the target's bucket, expands bidirectionally to neighbors based on prefix distance,
	 * and collects entries (and optionally replacements) until capacity is met or buckets are exhausted.
	 * Assumes buckets cover the full ID space via sorted, contiguous prefixes. May collect extra entries
	 * before trimming to ensure the closest k nodes are returned.
	 *
	 * @return this instance for chaining
	 */
	public KClosestNodes fill() {
		final List<KBucket> buckets = routingTable.buckets();
		if (buckets.isEmpty()) {
			return this;
		}

		final int idx = RoutingTable.indexOf(buckets, target);
		KBucket bucket = buckets.get(idx);
		addEntries(bucket);

		int low = idx;
		int high = idx;
		while (entries.size() < capacity) {
			KBucket lowBucket = null;
			KBucket highBucket = null;

			if (low > 0)
				lowBucket = buckets.get(low - 1);

			if (high < buckets.size() - 1)
				highBucket = buckets.get(high + 1);

			if (lowBucket == null && highBucket == null)
				break;

			if (lowBucket == null) {
				high++;
				addEntries(highBucket);
			} else if (highBucket == null) {
				low--;
				addEntries(lowBucket);
			} else {
				int dir = target.threeWayCompare(lowBucket.prefix().last(), highBucket.prefix().first());
				if (dir < 0) {
					low--;
					addEntries(lowBucket);
				} else if (dir > 0) {
					high++;
					addEntries(highBucket);
				} else {
					low--;
					high++;
					addEntries(lowBucket);
					addEntries(highBucket);
				}
			}
		}

		shave();
		return this;
	}

	private void addEntries(KBucket bucket) {
		if (bucket == null)
			return;

		bucket.entries().forEach(e -> {
			if (filter.test(e))
				entries.add(e);
		});

		if (includeReplacements) {
			bucket.replacementStream()
					.filter(KBucketEntry::isReachable)
					.filter(filter)
					.forEach(entries::add);
		}
	}

	private void shave() {
		entries.sort((e1, e2) -> target.threeWayCompare(e1.getId(), e2.getId()));
		int overshoot = entries.size() - capacity;
		if (overshoot > 0)
			entries.subList(entries.size() - overshoot, entries.size()).clear();
	}

	/**
	 * Returns an unmodifiable view of the collected entries, sorted by XOR distance to the target.
	 * May return fewer than capacity if the table lacks sufficient entries.
	 *
	 * @return the list of closest entries
	 */
	public List<KBucketEntry> entries() {
		return entries;
	}

	/**
	 * Returns the collected entries as a list of NodeInfo objects.
	 *
	 * @return the list of closest nodes as NodeInfo
	 */
	public List<NodeInfo> nodes() {
		return new ArrayList<>(entries);
	}
}