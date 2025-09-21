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

package io.bosonnetwork.kademlia.tasks;

import java.util.Collection;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.bosonnetwork.Id;

/**
 * A class for managing a set of the closest nodes to a target ID in a Kademlia DHT.
 * Nodes are ordered by their XOR distance to the target ID, with a fixed capacity.
 * Tracks insertion attempts to determine when the set is stable, used to terminate
 * lookup tasks ({@link NodeLookupTask}, {@link PeerLookupTask}, {@link ValueLookupTask}).
 * Designed for single-threaded use in a Vert.x event loop.
 */
public class ClosestSet {
	/** The target ID for distance comparisons. */
	private final Id target;
	/** The maximum number of nodes in the set (Kademlia's k parameter). */
	private final int capacity;
	/** Map of nodes ordered by XOR distance to the target ID. */
	private final NavigableMap<Id, CandidateNode> closest;
	/** Number of insertion attempts since the farthest node was modified. */
	private int insertAttemptsSinceTailModification = 0;
	/** Number of insertion attempts since the closest node was modified. */
	private int insertAttemptsSinceHeadModification = 0;

	private static final Logger log = LoggerFactory.getLogger(ClosestSet.class);

	/**
	 * Constructs a new ClosestSet for the given target ID and capacity.
	 *
	 * @param target   the target ID for distance comparisons
	 * @param capacity the maximum number of nodes
	 */
	public ClosestSet(Id target, int capacity) {
		this.target = target;
		this.capacity = capacity;

		// Use TreeMap ordered by XOR distance to target (via threeWayCompare comparator)
		closest = new TreeMap<>(target::threeWayCompare);
		// closest = new ConcurrentSkipListMap<>(new Id.Comparator(target));
	}

	/**
	 * Checks if the set has reached its capacity.
	 *
	 * @return true if the set is at or above capacity, false otherwise
	 */
	boolean reachedCapacity() {
		return closest.size() >= capacity;
	}

	/**
	 * Returns the current number of nodes in the set.
	 *
	 * @return the size of the set
	 */
	public int size() {
		return closest.size();
	}

	/**
	 * Checks if the closest set is empty.
	 *
	 * @return true if empty, false otherwise
	 */
	public boolean isEmpty() {
		return closest.isEmpty();
	}

	/**
	 * Retrieves a candidate node by its ID.
	 *
	 * @param id the node ID
	 * @return the candidate node, or null if not found
	 */
	public CandidateNode get(Id id) {
		return closest.get(id);
	}

	/**
	 * Checks if the set contains a node with the given ID.
	 *
	 * @param id the node ID
	 * @return true if present, false otherwise
	 */
	public boolean contains(Id id) {
		return closest.containsKey(id);
	}

	/**
	 * Adds a candidate node to the set, maintaining order and capacity.
	 * Updates stability counters for termination logic.
	 *
	 * @param cn the candidate node to add
	 */
	void add(CandidateNode cn) {
		closest.put(cn.getId(), cn);
		log.debug("Added candidate {} to ClosestSet, size now {}", cn.getId(), closest.size());

		if (closest.size() > capacity) {
			CandidateNode last = closest.lastEntry().getValue();
			closest.remove(last.getId());
			if (last == cn)
				insertAttemptsSinceTailModification++;
			else
				insertAttemptsSinceTailModification = 0;

			log.debug("Removed farthest candidate {}, tail modification count: {}", last.getId(), insertAttemptsSinceTailModification);
		}

		if (closest.firstEntry().getValue() == cn)
			insertAttemptsSinceHeadModification = 0;
		else
			insertAttemptsSinceHeadModification++;
	}

	/**
	 * Removes a candidate node by its ID.
	 *
	 * @param id the node ID to remove
	 */
	public void removeCandidate(Id id) {
		if (closest.isEmpty())
			return;

		CandidateNode removed = closest.remove(id);
		if (removed != null) {
			log.debug("Removed candidate {} from ClosestSet, size now {}", id, closest.size());
		}
	}

	/**
	 * Returns a stream of node IDs in the set.
	 *
	 * @return the stream of node IDs
	 */
	public Stream<Id> ids() {
		return closest.keySet().stream();
	}

	/**
	 * Returns the collection of candidate nodes in the set.
	 *
	 * @return the collection of nodes
	 */
	public Collection<CandidateNode> entries() {
		return closest.values();
	}

	/**
	 * Returns a stream of candidate nodes in the set.
	 *
	 * @return the stream of nodes
	 */
	public Stream<CandidateNode> stream() {
		return closest.values().stream();
	}

	/**
	 * Returns the ID of the farthest node, or a fallback maximum distance if empty.
	 *
	 * @return the tail ID
	 */
	public Id tail() {
		if (closest.isEmpty()) {
			log.debug("ClosestSet tail: returning fallback maximum distance for empty set");
			return target.distance(Id.MAX_ID);
		}

		return closest.lastKey();
	}

	/**
	 * Returns the ID of the closest node, or a fallback maximum distance if empty.
	 *
	 * @return the head ID
	 */
	public Id head() {
		if (closest.isEmpty()) {
			log.debug("ClosestSet head: returning fallback maximum distance for empty set");
			return target.distance(Id.MAX_ID);
		}

		return closest.firstKey();
	}

	/**
	 * Checks if the set is eligible for lookup termination (full and stable).
	 * Stability is determined by no modifications to the tail after more than capacity attempts.
	 *
	 * @return true if eligible, false otherwise
	 */
	public boolean isEligible() {
		return reachedCapacity() && insertAttemptsSinceTailModification > capacity;
	}

	/**
	 * Returns the number of insertion attempts since the farthest node was modified.
	 * Used for fine-tuning lookup termination in {@link LookupTask}.
	 *
	 * @return the number of attempts
	 */
	public int getInsertAttemptsSinceTailModification() {
		return insertAttemptsSinceTailModification;
	}

	/**
	 * Returns the number of insertion attempts since the closest node was modified.
	 * Used for advanced stability tracking or debugging.
	 *
	 * @return the number of attempts
	 */
	public int getInsertAttemptsSinceHeadModification() {
		return insertAttemptsSinceHeadModification;
	}

	/**
	 * Checks if the head (closest node) is stable (no modifications after more than capacity attempts).
	 *
	 * @return true if stable, false otherwise
	 */
	public boolean isHeadStable() {
		return insertAttemptsSinceHeadModification > capacity;
	}

	/**
	 * Returns a string representation of the set, including size and head/tail distances.
	 *
	 * @return the string representation
	 */
	@Override
	public String toString() {
		return "ClosestSet: size=" + closest.size() + " head=" + head().approxDistance(target) + " tail=" + tail().approxDistance(target);
	}
}