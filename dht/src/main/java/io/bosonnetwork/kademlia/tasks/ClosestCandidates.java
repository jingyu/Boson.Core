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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

import io.bosonnetwork.Id;
import io.bosonnetwork.NodeInfo;

/**
 * A class for managing a prioritized queue of candidate nodes in Kademlia lookup tasks.
 * Nodes are ordered by XOR distance to a target ID, with deduplication by ID and address
 * (IP in production, IP:port in developer mode). Provides candidates for RPC queries in
 * {@link LookupTask}, prioritizing nodes with closer distance then fewer ping attempts.
 * Processed nodes remain deduplicated to prevent re-addition. Designed for single-threaded
 * use in a Vert.x event loop; not thread-safe.
 */
public class ClosestCandidates {
	/** The target ID for distance comparisons. */
	private final Id target;
	/** The maximum number of nodes in the queue. */
	private final int capacity;
	/** Map of nodes ordered by XOR distance to the target ID. */
	private final SortedMap<Id, CandidateNode> closest;
	/** Set of node IDs and addresses (IP or SocketAddress) for deduplication. */
	private final Set<Object> dedup;
	/** Whether to use developer mode (deduplicate by IP:port). */
	private final boolean developerMode;

	/**
	 * Constructs a new ClosestCandidates queue for the given target ID and capacity.
	 *
	 * @param target        the target ID for distance comparisons, must not be null
	 * @param capacity      the maximum number of nodes
	 * @param developerMode true to deduplicate by IP:port, false for IP only
	 */
	public ClosestCandidates(Id target, int capacity, boolean developerMode) {
		this.target = target;
		this.capacity = capacity;
		this.developerMode = developerMode;

		closest = new TreeMap<>(target::threeWayCompare);
		dedup = new HashSet<>(capacity * 2);
	}

	/**
	 * Constructs a new ClosestCandidates queue with developer mode disabled.
	 *
	 * @param target   the target ID for distance comparisons
	 * @param capacity the maximum number of nodes
	 */
	protected ClosestCandidates(Id target, int capacity) {
		this(target, capacity, false);
	}

	/**
	 * Checks if the queue has reached its capacity.
	 *
	 * @return true if at or above capacity, false otherwise
	 */
	boolean reachedCapacity() {
		return closest.size() >= capacity;
	}

	/**
	 * Returns the current number of nodes in the queue.
	 *
	 * @return the size
	 */
	public int size() {
		return closest.size();
	}

	/**
	 * Checks if the queue is empty.
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
	 * Compares two candidate nodes for prioritization, favoring closer distance then fewer pings.
	 *
	 * @param cn1 the first candidate node
	 * @param cn2 the second candidate node
	 * @return negative if cn1 is preferred, positive if cn2, zero if equal
	 */
	private int candidateOrder(CandidateNode cn1, CandidateNode cn2) {
		// Kademlia typically prioritizes distance, with ping counts as a tiebreaker.
		int diff = target.threeWayCompare(cn1.getId(), cn2.getId());
		return diff != 0 ? diff : Integer.compare(cn1.getPinged(), cn2.getPinged());
	}

	/**
	 * Adds nodes to the queue, deduplicating by ID and address, and prunes excess nodes to retain closer ones.
	 *
	 * @param nodes the nodes to add
	 */
	public void add(Collection<? extends NodeInfo> nodes) {
		for (NodeInfo node : nodes) {
			// Check existing node id
			if (!dedup.add(node.getId()))
				continue;

			// Check existing:
			// - production mode: ip address
			// - developer mode: socket address(ip:port)
			Object addr = developerMode ? node.getAddress() : node.getAddress().getAddress();
			if (!dedup.add(addr))
				continue;

			CandidateNode cn = new CandidateNode(node);
			closest.put(cn.getId(), cn);
		}

		if (reachedCapacity()) {
			List<CandidateNode> toRemove = closest.values().stream()
					.filter(cn -> !cn.isInFlight())
					.sorted(this::candidateOrder)
					.skip(capacity)
					.toList();

			for (CandidateNode cn : toRemove) {
				closest.remove(cn.getId());
				dedup.remove(cn.getId());
				Object addr = developerMode ? cn.getAddress() : cn.getAddress().getAddress();
				dedup.remove(addr);
			}
		}
	}

	/**
	 * Removes nodes from the queue based on a filter, retaining deduplication entries.
	 *
	 * @param filter the predicate to select nodes for removal
	 */
	public void remove(Predicate<CandidateNode> filter) {
		if (closest.isEmpty())
			return;

		// Retain dedup to prevent re-addition
		closest.entrySet().removeIf(e -> filter.test(e.getValue()));
	}

	/**
	 * Removes a candidate node by its ID, retaining deduplication entries.
	 *
	 * @param id the node ID
	 * @return the removed candidate node, or null if not found
	 */
	public CandidateNode remove(Id id) {
		if (closest.isEmpty())
			return null;

		// Retain dedup to prevent re-addition
		return closest.remove(id);
	}

	/**
	 * Retrieves the next candidate node to query, prioritizing eligible nodes by distance and ping count.
	 *
	 * @return the next candidate node, or null if none eligible
	 */
	public CandidateNode next() {
		return closest.values().stream()
				.filter(CandidateNode::isEligible)
				.min(this::candidateOrder)
				.orElse(null);
	}

	/**
	 * Returns a stream of node IDs in the queue.
	 *
	 * @return the stream of IDs
	 */
	public Stream<Id> ids() {
		return closest.keySet().stream();
	}

	/**
	 * Returns a stream of candidate nodes in the queue.
	 *
	 * @return the stream of nodes
	 */
	public Stream<CandidateNode> entries() {
		return closest.values().stream();
	}

	/**
	 * Returns the ID of the farthest node, or a fallback maximum distance if empty.
	 *
	 * @return the tail ID
	 */
	public Id tail() {
		if (closest.isEmpty())
			return target.distance(Id.MAX_ID);

		return closest.lastKey();
	}

	/**
	 * Returns the ID of the closest node, or a fallback maximum distance if empty.
	 *
	 * @return the head ID
	 */
	public Id head() {
		if (closest.isEmpty())
			return target.distance(Id.MAX_ID);

		return closest.firstKey();
	}

	/**
	 * Returns a string representation of the queue, including size and head/tail distances.
	 *
	 * @return the string representation
	 */
	@Override
	public String toString() {
		return "ClosestCandidates: size=" + closest.size() + " head=" + head().approxDistance(target) +
				" tail=" + tail().approxDistance(target);
	}
}