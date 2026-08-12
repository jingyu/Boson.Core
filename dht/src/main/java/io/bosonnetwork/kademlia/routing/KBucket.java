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

import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.slf4j.Logger;

import io.bosonnetwork.Id;
import io.bosonnetwork.crypto.Random;
import io.bosonnetwork.kademlia.impl.KadConstants;

/**
 * Represents a k-bucket in a Kademlia routing table.
 * <p>
 * A KBucket is a list of {@link KBucketEntry} objects, maintaining up to the configured bucket size (k).
 * This implementation prefers nodes with older creation times for stability.
 * </p>
 * <b>CAUTION:</b> This is a non-thread-safe k-bucket implementation, designed for use
 * inside a Vert.x verticle or other single-threaded environment.
 */
public class KBucket implements Comparable<KBucket> {
	/**
	 * The prefix this bucket covers in the routing table.
	 */
	private final Prefix prefix;

	/**
	 * Indicates if this bucket is the "home" bucket for the local node.
	 */
	private final boolean homeBucket;

	/**
	 * The Kademlia bucket size (k): the maximum number of main entries this bucket holds.
	 */
	private final int maxEntries;

	/**
	 * The maximum number of replacement entries this bucket holds.
	 */
	private final int maxReplacements;

	/**
	 * The main list of entries in this bucket (up to {@link #maxEntries}).
	 */
	// Sorting after every update is inexpensive at realistic bucket sizes.
	// Keeps entries strictly age-ordered for iteration.
	private final List<KBucketEntry> entries;

	/**
	 * The list of replacement entries for this bucket.
	 * Used to replace failed or stale entries in the main list.
	 */
	private final List<KBucketEntry> replacements;

	/**
	 * The last time this bucket was refreshed by the ping path, in milliseconds since the epoch.
	 * <p>
	 * Owned by routing-table maintenance, which repairs a bucket in place with a cheap
	 * {@code PingRefreshTask}: it drives {@link #needsToBeRefreshed()} and
	 * {@link #needsReplacementPing()}. It is also a signal, not just a clock - {@link #put(KBucketEntry)}
	 * resets it to zero when a reachable node arrives at a full bucket, to demand the ping that
	 * decides whether the least-recently-seen entry can be evicted for it.
	 * </p>
	 * <p>
	 * Deliberately separate from {@link #lastLookupRefresh}. Merging the two would let the expensive
	 * mechanism silence the cheap one: a bucket-filling lookup is marked before it runs and regardless
	 * of what it finds, so a lookup into a sparse region - the case that most often finds nothing -
	 * would erase the eviction signal above and suppress ping-refresh for a full
	 * {@link KadConstants#BUCKET_REFRESH_INTERVAL} without having repaired anything.
	 * </p>
	 */
	private long lastRefresh;

	/**
	 * The last time this bucket was targeted by a bucket-filling lookup, in milliseconds since the epoch.
	 * <p>
	 * Owned by {@code DHT.fillBuckets()}, which acquires new contacts for a partially populated bucket
	 * by running a full iterative lookup on a random id within its prefix. That is one to two orders of
	 * magnitude more expensive than a ping refresh, so it needs a rate limiter of its own; this is it,
	 * read through {@link #needsLookupRefresh()}.
	 * </p>
	 * <p>
	 * Stamped before the lookup is dispatched rather than after it completes, which is correct for a
	 * rate limiter: a lookup that hangs or fails must not be re-dispatched on the next bootstrap. It is
	 * intentionally never reset by {@link #put(KBucketEntry)} - a full bucket receiving a new node
	 * wants a ping, not a lookup.
	 * </p>
	 */
	private long lastLookupRefresh;

	/**
	 * Creates a bucket with the given capacities.
	 *
	 * @param prefix          the prefix this bucket covers.
	 * @param maxEntries      the Kademlia bucket size (k), at least 1.
	 * @param maxReplacements the replacement cache size, at least 1.
	 * @param isHome          predicate deciding whether the prefix is the local node's home bucket.
	 */
	protected KBucket(Prefix prefix, int maxEntries, int maxReplacements, Predicate<Prefix> isHome) {
		if (maxEntries < 1)
			throw new IllegalArgumentException("Invalid maxEntries: " + maxEntries);
		if (maxReplacements < 1)
			throw new IllegalArgumentException("Invalid maxReplacements: " + maxReplacements);

		this.prefix = prefix;
		this.homeBucket = isHome.test(prefix);
		this.maxEntries = maxEntries;
		this.maxReplacements = maxReplacements;

		// using ArrayList here since reading/iterating is far more common than writing.
		entries = new ArrayList<>(maxEntries);
		replacements = new ArrayList<>(maxReplacements);
	}

	private static Logger log() {
		return RoutingTable.log;
	}

	/**
	 * Returns the prefix associated with this bucket.
	 *
	 * @return The prefix of this bucket.
	 */
	public Prefix prefix() {
		return prefix;
	}

	/**
	 * Returns whether this bucket is the "home" bucket for the local node.
	 *
	 * @return true if this is the home bucket; false otherwise.
	 */
	public boolean isHomeBucket() {
		return homeBucket;
	}

	/**
	 * Get the number of entries.
	 *
	 * @return The number of entries in this Bucket
	 */
	public int size() {
		return entries.size();
	}

	/**
	 * Returns an unmodifiable view of the entries in this bucket.
	 *
	 * @return the entries
	 */
	public List<KBucketEntry> entries() {
		return Collections.unmodifiableList(entries);
	}

	/**
	 * Returns a stream of the entries in this bucket.
	 *
	 * @return a stream of KBucketEntry objects in this bucket.
	 */
	public Stream<KBucketEntry> stream() {
		return entries.stream();
	}

	/**
	 * Checks if this bucket has no entries.
	 *
	 * @return true if the bucket is empty; false otherwise.
	 */
	public boolean isEmpty() {
		return entries.isEmpty();
	}

	/**
	 * Checks if this bucket is full (i.e., contains maxEntries entries).
	 *
	 * @return true if the bucket is full; false otherwise.
	 */
	public boolean isFull() {
		return entries.size() >= maxEntries;
	}

	/**
	 * Returns how many entries this bucket is short of the Kademlia bucket size (k).
	 * <p>
	 * Used to order bucket-filling lookups when more buckets want one than the per-bootstrap budget
	 * allows: among buckets equally overdue, the emptiest gains the most from a lookup.
	 * </p>
	 *
	 * @return the number of entries needed to fill this bucket, zero if it is already full.
	 */
	public int deficit() {
		return Math.max(0, maxEntries - entries.size());
	}

	/**
	 * Returns the number of replacement entries in this bucket.
	 *
	 * @return the number of replacement entries.
	 */
	public int replacementSize() {
		return replacements.size();
	}

	/**
	 * Returns an unmodifiable view of the replacement entries in this bucket.
	 *
	 * @return the replacement entries.
	 */
	public List<KBucketEntry> replacements() {
		return Collections.unmodifiableList(replacements);
	}

	/**
	 * Returns a stream of the replacement entries in this bucket.
	 *
	 * @return a stream of KBucketEntry objects in the replacement list.
	 */
	public Stream<KBucketEntry> replacementStream() {
		return replacements.stream();
	}

	/**
	 * Returns the entry at the specified index in the main entries list.
	 *
	 * @param index the index of the entry to return.
	 * @return the KBucketEntry at the specified index.
	 */
	protected KBucketEntry get(int index) {
		return entries.get(index);
	}

	/**
	 * Returns the entry with the specified ID, searching the main entries and optionally the replacements.
	 *
	 * @param id the ID to search for.
	 * @param includeReplacement if true, also search the replacement list.
	 * @return the found KBucketEntry, or null if not found.
	 */
	public KBucketEntry get(Id id, boolean includeReplacement) {
		for (KBucketEntry entry : entries) {
			if (entry.getId().equals(id))
				return entry;
		}

		if (includeReplacement) {
			for (KBucketEntry entry : replacements) {
				if (entry.getId().equals(id))
					return entry;
			}
		}

		return null;
	}

	public boolean contains(Id id, boolean includeReplacements) {
		if (findAny(e -> e.getId().equals(id)) != null)
			return true;

		if (includeReplacements)
			return findAnyInReplacements(e -> e.getId().equals(id)) != null;
		else
			return false;
	}

	/**
	 * Returns a random entry from the main entries list, or null if empty.
	 *
	 * @return a random KBucketEntry, or null if the bucket is empty.
	 */
	public KBucketEntry getAny() {
		return entries.isEmpty() ? null : entries.get(Random.random().nextInt(entries.size()));
	}

	private KBucketEntry findAny(Predicate<KBucketEntry> predicate) {
		// return getEntries().stream().filter(predicate).findAny().orElse(null);
		// Stream is heavy and slow, use for loop(more fast) instead
		for (KBucketEntry entry : entries) {
			if (predicate.test(entry))
				return entry;
		}
		return null;
	}

	private KBucketEntry findAnyInReplacements(Predicate<KBucketEntry> predicate) {
		// return getCache().stream().filter(predicate).findAny().orElse(null);
		// Stream is heavy and slow, use for loop(more fast) instead
		for (KBucketEntry entry : replacements) {
			if (predicate.test(entry))
				return entry;
		}
		return null;
	}

	private boolean anyMatch(Predicate<KBucketEntry> predicate) {
		return findAny(predicate) != null;
	}

	private boolean anyMatchInReplacements(Predicate<KBucketEntry> predicate) {
		return findAnyInReplacements(predicate) != null;
	}

	protected KBucketEntry find(Id id, InetSocketAddress addr) {
		return findAny(e -> e.getId().equals(id) || e.getAddress().equals(addr));
	}

	/**
	 * Find a pingable replacement entry.
	 *
	 * @return the pingable replacement entry, or null if not found
	 */
	public KBucketEntry findPingableReplacement() {
		return findAnyInReplacements(KBucketEntry::isNeverContacted);
	}

	/**
	 * Resets the last modified for this Bucket
	 */
	public void updateRefreshTime() {
		updateRefreshTime(System.currentTimeMillis());
	}

	// for internal and testing
	protected void updateRefreshTime(long lastRefresh) {
		this.lastRefresh = lastRefresh;
	}

	/**
	 * Checks if the bucket needs to be refreshed, based on the refresh interval and the presence of entries needing a ping.
	 *
	 * @return true if the bucket needs to be refreshed; false otherwise.
	 */
	public boolean needsToBeRefreshed() {
		long now = System.currentTimeMillis();
		return now - lastRefresh > KadConstants.BUCKET_REFRESH_INTERVAL && anyMatch(KBucketEntry::needsPing);
	}

	/**
	 * Returns the last time a ping refresh covered this bucket, in milliseconds since the epoch.
	 * <p>
	 * Zero means it has never been covered, which is also what a bucket read back by {@code load()}
	 * reports - {@code load()} does not stamp what it reads. Callers that ration refreshes across
	 * buckets order by this, so that a bucket which has never had one is served before any bucket is
	 * served twice.
	 * </p>
	 *
	 * @return the timestamp, zero if no ping refresh has covered this bucket.
	 */
	public long lastRefresh() {
		return lastRefresh;
	}

	/**
	 * Returns the last time a bucket-filling lookup targeted this bucket, in milliseconds since the epoch.
	 *
	 * @return the timestamp, zero if no such lookup has run.
	 */
	public long lastLookupRefresh() {
		return lastLookupRefresh;
	}

	/**
	 * Marks this bucket as having been targeted by a bucket-filling lookup, as of now.
	 */
	public void updateLookupRefreshTime() {
		updateLookupRefreshTime(System.currentTimeMillis());
	}

	// for internal and testing
	protected void updateLookupRefreshTime(long lastLookupRefresh) {
		this.lastLookupRefresh = lastLookupRefresh;
	}

	/**
	 * Checks whether enough time has passed to spend another bucket-filling lookup on this bucket.
	 * <p>
	 * Unlike {@link #needsToBeRefreshed()} this asks only about elapsed time, not about the state of
	 * the entries. Whether the bucket is worth filling at all - partially populated, not already being
	 * repaired by the cheaper ping path - is decided by the caller; this is purely the rate limiter
	 * that keeps the expensive path off the same bucket. It shares
	 * {@link KadConstants#BUCKET_REFRESH_INTERVAL} with the ping path because the two answer the same
	 * question, "is this bucket's information stale"; only the remedy differs.
	 * </p>
	 *
	 * @return true if this bucket may be lookup-filled again; false otherwise.
	 */
	public boolean needsLookupRefresh() {
		long now = System.currentTimeMillis();
		return now - lastLookupRefresh > KadConstants.BUCKET_REFRESH_INTERVAL;
	}

	/**
	 * Checks if a replacement entry should be pinged, based on timing and entry status.
	 *
	 * @return true if a replacement entry should be pinged; false otherwise.
	 */
	public boolean needsReplacementPing() {
		long now = System.currentTimeMillis();
		return now - lastRefresh > KadConstants.BUCKET_REPLACEMENT_PING_MIN_INTERVAL &&
				(anyMatch(KBucketEntry::needsReplacement) || entries.size() < maxEntries) &&
				anyMatchInReplacements(KBucketEntry::isNeverContacted);
	}

	protected boolean needsReplacement() {
		return anyMatch(KBucketEntry::needsReplacement);
	}

	/**
	 * Notify bucket of a new entry learned from a node, perform update or insert
	 * existing nodes where appropriate
	 * <p>
	 * Acceptance means the bucket now holds the entry, whether as a main entry or as a replacement,
	 * or already held it and merged the new observation into it. Being filed as a replacement counts:
	 * a replacement is a real, addressable slot that a later verification can promote, so a caller
	 * that treats it as a rejection would suppress exactly the follow-up that fills the bucket.
	 * </p>
	 * <p>
	 * Rejection means nothing was stored, and there is only one reason for it: another entry already
	 * claims this id or this address. That entry is kept and the new one is dropped, so there is no
	 * slot here for a caller to act on.
	 * </p>
	 *
	 * @param entry The entry to insert
	 * @return true if the bucket holds the entry after this call, false if it was dropped in favour
	 *         of a conflicting entry.
	 */
	protected boolean put(KBucketEntry entry) {
		// find existing
		for (KBucketEntry existing : entries) {
			// Update entry if existing
			if (existing.equals(entry)) {
				existing.merge(entry);
				return true;
			}

			// Node is inconsistent: id and address conflict
			// Log the conflict and keep the existing entry
			if (existing.matches(entry)) {
				//noinspection LoggingSimilarMessage
				log().debug("New node {} claims same ID or IP as {}, might be impersonation attack or IP change. "
						+ "ignoring until old entry times out", entry, existing);

				// Should not be so aggressive, keep the existing entry.
				return false;
			}
		}

		// not found, add the new entry, and remove from replacements if exists avoid duplicated entries
		if (entry.isReachable()) {
			if (entries.size() < maxEntries) {
				putAsMainEntry(entry);
				return true;
			}

			// Try to replace the bad entry
			if (replaceBadWith(entry))
				return true;

			// When bucket full and new reachable entry arrives, Kademlia(original paper) pings the
			// oldest/least-recent when full; if unresponsive, replace from cache, else cache the new one.
			// now we reset the last refresh timestamp
			// This will force a refresh to run PingRefreshTask with probe replacement on the current bucket
			// Assumes PingRefreshTask pings least-recent-seen entries for LRS eviction.
			// Only the ping clock: a full bucket wants the eviction probe, not a lookup for more
			// contacts it has no room for, so lastLookupRefresh is deliberately left alone.
			lastRefresh = 0;
		}

		// put the new entry to the replacements: either it is not reachable yet, or it is reachable but
		// the bucket has no room for it
		log().debug("New node {} can not be a main entry, putting in replacements", entry);
		return putAsReplacement(entry);
	}

	protected void updateIfPresent(KBucketEntry entry) {
		for (KBucketEntry existing : entries) {
			if (existing.equals(entry)) {
				existing.merge(entry);
				return;
			}
		}

		for (KBucketEntry existing : replacements) {
			if (existing.equals(entry)) {
				existing.merge(entry);
				return;
			}
		}
	}

	/**
	 * Tries to insert the entry by replacing a bad entry.
	 *
	 * @param entry Entry to insert
	 * @return true if replace was successful
	 */
	private boolean replaceBadWith(KBucketEntry entry) {
		boolean badRemoved = false;

		for (int i = 0; i < entries.size(); i++) {
			if (entries.get(i).needsReplacement()) {
				entries.remove(i);
				badRemoved = true;
				break;
			}
		}

		if (badRemoved)
			putAsMainEntry(entry);

		return badRemoved;
	}

	/**
	 * @param id entry to remove, if it's bad
	 * @param force    if true, the entry will be removed regardless of its state
	 */
	protected KBucketEntry removeIfBad(Id id, boolean force) {
		for (int i = 0; i < entries.size(); i++) {
			KBucketEntry entry = entries.get(i);
			if (entry.getId().equals(id)) {
				boolean removed = false;

				if (force || entry.needsReplacement()) {
					KBucketEntry replacement = pollVerifiedReplacement();
					// only remove if we have a replacement or really need to
					if (replacement != null) {
						entries.set(i, replacement);
						entries.sort(KBucketEntry::ageOrder);
						removed = true;
					} else if (force) {
						entries.remove(i);
						removed = true;
					}
				}

				return removed ? entry : null;
			}
		}

		for (int i = 0; i < replacements.size(); i++) {
			KBucketEntry entry = replacements.get(i);
			if (entry.getId().equals(id)) {
				// Note: stale replacements under capacity are left until periodic cleanup.
				if (force || (replacements.size() >= maxReplacements && entry.oldAndStale())) {
					replacements.remove(i);
					return entry;
				}
				return null;
			}
		}

		return null;
	}

	protected boolean remove(Id id) {
		return entries.removeIf(entry -> entry.getId().equals(id)) ||
				replacements.removeIf(entry -> entry.getId().equals(id));
	}

	private void putAsMainEntry(KBucketEntry entry) {
		// try to check the youngest entry
		KBucketEntry youngest = entries.isEmpty() ? null : entries.get(entries.size() - 1);

		// insert to the list if it still has room, keep the age order
		entries.add(entry);
		boolean unordered = youngest != null && entry.creationTime() < youngest.creationTime();
		if (unordered)
			entries.sort(KBucketEntry::ageOrder);

		// remove from the replacements if exists avoid duplicated entries
		for (int i = 0; i < replacements.size(); i++) {
			KBucketEntry replacement = replacements.get(i);
			if (replacement.matches(entry)) {
				replacements.remove(i);

				// merge the replacement entry into the main entry if possible: restrict to the same id
				if (replacement.getId().equals(entry.getId()))
					entry.merge(replacement);

				return;
			}
		}
	}

	/**
	 * Puts the entry into the replacement cache, evicting the least valuable replacement if the cache
	 * is over capacity.
	 * <p>
	 * The eviction can fall on the entry that was just added, so acceptance is decided after the trim
	 * rather than before it. A caller that follows up on a stored entry - verifying it, promoting it -
	 * has nothing to follow up on if the cache preferred the entries it already had.
	 * </p>
	 *
	 * @param entry the entry to cache.
	 * @return true if the replacement cache holds the entry after this call, false if it was dropped,
	 *         either in favour of a conflicting entry or by the capacity trim.
	 */
	protected boolean putAsReplacement(KBucketEntry entry) {
		// Check the existing, avoid the duplicated entry
		for (KBucketEntry existing : replacements) {
			// Update entry if existing
			if (existing.equals(entry)) {
				existing.merge(entry);
				return true;
			}

			// Node is inconsistent: id and address conflict
			// Log the conflict and keep the existing entry
			if (existing.matches(entry)) {
				//noinspection LoggingSimilarMessage
				log().debug("New node {} claims same ID or IP as {}, might be impersonation attack or IP change. "
						+ "ignoring until old entry times out", entry, existing);

				// Should not be so aggressive, keep the existing entry.
				return false;
			}
		}

		replacements.add(entry);
		if (replacements.size() > maxReplacements) {
			replacements.sort(KBucketEntry::replacementOrder);
			// Identity, not equality: the entry that loses the sort is the one that was not kept, and a
			// merged duplicate would have returned above.
			return replacements.remove(replacements.size() - 1) != entry;
		}

		return true;
	}

	/**
	 * Promotes a verified replacement entry to the main entries list if possible.
	 * <p>
	 * If the main list is not full, a verified (reachable) replacement is added.
	 * Otherwise, if any entry in the main list needs replacement, it is replaced by a verified replacement.
	 * No operation if no verified replacements are available.
	 */
	protected void promoteVerifiedReplacement() {
		if (replacements.isEmpty())
			return;

		// If not full, promote a verified replacement directly
		// Promotes one per call for controlled updates.
		if (entries.size() < maxEntries) {
			KBucketEntry replacement = pollVerifiedReplacement();
			if (replacement != null) {
				entries.add(replacement);
				entries.sort(KBucketEntry::ageOrder);
				return;
			}
		}

		// Otherwise, try to replace an entry that needs replacement
		for (int i = 0; i < entries.size(); i++) {
			KBucketEntry entry = entries.get(i);
			if (entry.needsReplacement()) {
				KBucketEntry replacement = pollVerifiedReplacement();
				if (replacement != null) {
					entries.set(i, replacement);
					entries.sort(KBucketEntry::ageOrder);
					return;
				}
			}
		}
	}

	private KBucketEntry pollVerifiedReplacement() {
		if (replacements.isEmpty())
			return null;

		if (replacements.size() > 1)
			replacements.sort(KBucketEntry::replacementOrder);

		if (replacements.get(0).isReachable())
			return replacements.remove(0);
		else
			return null;
	}

	/*/
	protected void onIncomingRequest(Id id) {
		for (KBucketEntry entry : entries) {
			if (entry.getId().equals(id)) {
				entry.onIncomingRequest();
				return;
			}
		}

		for (KBucketEntry entry : replacements)
			if (entry.getId().equals(id)) {
				entry.onIncomingRequest();
				return;
			}
	}
	 */

	protected void onRequestSent(Id id) {
		for (KBucketEntry entry : entries) {
			if (entry.getId().equals(id)) {
				entry.onRequestSent();
				return;
			}
		}

		for (KBucketEntry entry : replacements) {
			if (entry.getId().equals(id)) {
				entry.onRequestSent();
				return;
			}
		}
	}

	protected void onResponded(Id id, int rtt) {
		for (KBucketEntry entry : entries) {
			// update last responded
			if (entry.getId().equals(id)) {
				entry.onResponded(rtt);
				return;
			}
		}

		for (int i = 0; i < replacements.size(); i++) {
			KBucketEntry entry = replacements.get(i);
			// update last responded
			if (entry.getId().equals(id)) {
				entry.onResponded(rtt);

				// if the main entries list is not full, promote the verified replacement to the main entries list.
				if (entries.size() < maxEntries) {
					replacements.remove(i);
					entries.add(entry);
					entries.sort(KBucketEntry::ageOrder);
				}

				return;
			}
		}
	}

	/**
	 * A node failed to respond
	 *
	 * @param id id of the node
	 */
	protected boolean onTimeout(Id id) {
		for (int i = 0; i < entries.size(); i++) {
			KBucketEntry entry = entries.get(i);
			if (entry.getId().equals(id)) {
				entry.onTimeout();
				if (entry.needsReplacement()) {
					KBucketEntry replacement = pollVerifiedReplacement();
					// only remove if we have a replacement
					if (replacement != null) {
						entries.set(i, replacement);
						entries.sort(KBucketEntry::ageOrder);
						return true;
					}
				}

				return false;
			}
		}

		for (int i = 0; i < replacements.size(); i++) {
			KBucketEntry entry = replacements.get(i);
			if (entry.getId().equals(id)) {
				entry.onTimeout();
				// Cull stale replacements only if the replacement list is full
				if (replacements.size() >= maxReplacements && entry.oldAndStale()) {
					replacements.remove(i);
					return true;
				}

				return false;
			}
		}

		return false;
	}

	/**
	 * Cleans up the bucket by removing invalid, stale, or out-of-place entries.
	 * <p>
	 * This includes:
	 * <ul>
	 *   <li>Removing entries needing replacement from the replacement list.</li>
	 *   <li>Removing entries that do not match this bucket's prefix.</li>
	 *   <li>Removing the local node or bootstrap nodes from the main list if the bucket is full.</li>
	 * </ul>
	 * The handler is called for each dropped entry not matching the prefix.
	 * Self/bootstrap entries are silently removed (not passed to dropHandler).
	 *
	 * @param localId The local node's ID.
	 * @param bootstrapIds IDs of bootstrap nodes.
	 * @param droppedEntryHandler Handler to be called for each dropped entry.
	 */
	protected void cleanup(Id localId, Collection<Id> bootstrapIds, Consumer<KBucketEntry> droppedEntryHandler) {
		boolean modified = false;

		Iterator<KBucketEntry> iterator = replacements.iterator();
		while (iterator.hasNext()) {
			KBucketEntry entry = iterator.next();
			if (entry.needsReplacement()) {
				// TODO: check me - should we cleanup the entries from replacements?
				iterator.remove();
				continue;
			}

			if (!prefix.isPrefixOf(entry.getId())) {
				log().error("!!!KBucket {} has an replacement {} not belongs to him", prefix, entry);
				iterator.remove();
				droppedEntryHandler.accept(entry);
			}
		}

		for (int i = 0; i < entries.size(); i++) {
			KBucketEntry entry = entries.get(i);
			// remove self and bootstrap nodes if the bucket is full
			if (entry.getId().equals(localId) || (isFull() && bootstrapIds.contains(entry.getId()))) {
				KBucketEntry replacement = pollVerifiedReplacement();
				if (replacement != null) {
					entries.set(i, replacement);
					modified = true;
				} else {
					entries.remove(i);
					modified = true;
					--i;
				}

				continue;
			}

			// Fix the wrong entries
			if (!prefix.isPrefixOf(entry.getId())) {
				log().error("!!!KBucket {} has an entry {} not belongs to him", prefix, entry);
				KBucketEntry replacement = pollVerifiedReplacement();
				if (replacement != null) {
					entries.set(i, replacement);
					modified = true;
				} else {
					entries.remove(i);
					modified = true;
					--i;
				}

				droppedEntryHandler.accept(entry);
			}
		}

		if (modified)
			entries.sort(KBucketEntry::ageOrder);
	}

	@Override
	public int hashCode() {
		return prefix.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (o == null)
			return false;

		if (this == o)
			return true;

		if (o instanceof KBucket that)
			return prefix.equals(that.prefix);

		return false;
	}

	@Override
	public int compareTo(KBucket bucket) {
		return prefix.compareTo(bucket.prefix);
	}

	/**
	 * Appends a human-readable representation of this bucket to the provided StringBuilder.
	 * <p>
	 * The last refresh time is shown as the time elapsed since the last refresh.
	 *
	 * @param repr the StringBuilder to append to.
	 */
	protected void toString(StringBuilder repr) {
		repr.ensureCapacity(1024);

		repr.append("Prefix: ").append(prefix);
		if (isHomeBucket())
			repr.append(" [Home]");
		// Show the duration since the last refresh, not an absolute timestamp.
		repr.append(", lastRefresh: ").append(Duration.ofMillis(System.currentTimeMillis() - lastRefresh)).append(" ago");
		repr.append(", lastLookupRefresh: ").append(Duration.ofMillis(System.currentTimeMillis() - lastLookupRefresh)).append(" ago\n");

		if (!entries.isEmpty()) {
			repr.ensureCapacity(entries.size() * 100);
			repr.append("  entries[").append(entries.size()).append("]:\n");
			entries.forEach(entry -> repr.append("    ").append(entry).append('\n'));
			repr.append('\n');
		}

		if (!replacements.isEmpty()) {
			repr.ensureCapacity(replacements.size() * 100);
			repr.append("  replacements[").append(replacements.size()).append("]:\n");
			replacements.forEach(entry -> repr.append("    ").append(entry).append('\n'));
			repr.append('\n');
		}
	}

	protected void dump(PrintStream out) {
		out.printf("Prefix: %s", prefix);
		if (isHomeBucket())
			out.print(" [Home]");
		// Show the duration since the last refresh, not an absolute timestamp.
		out.printf(", lastRefresh: %s ago, lastLookupRefresh: %s ago\n",
				Duration.ofMillis(System.currentTimeMillis() - lastRefresh),
				Duration.ofMillis(System.currentTimeMillis() - lastLookupRefresh));

		if (!entries.isEmpty()) {
			out.printf("  entries[%d]:\n", entries.size());
			entries.forEach(entry -> out.printf("    %s\n", entry));
			out.println();
		}

		if (!replacements.isEmpty()) {
			out.printf("  replacements[%d]:\n", replacements.size());
			replacements.forEach(entry -> out.printf("    %s\n", entry));
			out.println();
		}
	}

	@Override
	public String toString() {
		StringBuilder repr = new StringBuilder(1024);
		toString(repr);
		return repr.toString();
	}
}
